package ru.taska.storage.versioned;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import ru.taska.exception.*;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

/** Additive pinned-version API. Legacy avatar consumers retain their own lifecycle and limits. */
public final class S3VersionedObjects implements VersionedObjects {
  private final S3AsyncClient client;
  private final String bucket;
  private final Duration timeout;

  public S3VersionedObjects(S3AsyncClient client, String bucket, Duration timeout) {
    this.client = Objects.requireNonNull(client);
    if (bucket == null
        || bucket.isBlank()
        || timeout == null
        || timeout.isNegative()
        || timeout.isZero()) throw new IllegalArgumentException();
    this.bucket = bucket;
    this.timeout = timeout;
  }

  @Override
  public Mono<Void> requireVersioning() {
    return io(Mono.fromFuture(() -> client.getBucketVersioning(b -> b.bucket(bucket))))
        .flatMap(
            r ->
                r.status() == BucketVersioningStatus.ENABLED
                    ? Mono.empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "BUCKET_VERSIONING_REQUIRED")));
  }

  @Override
  public Mono<Measurement> measure(String key, String version, long maximumBytes) {
    valid(key, version);
    if (maximumBytes <= 0)
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "INVALID_OBJECT_BOUND"));
    return requireVersioning()
        .then(
            io(
                Mono.fromFuture(
                    () -> client.headObject(b -> b.bucket(bucket).key(key).versionId(version)))))
        .flatMap(
            head -> {
              if (head.contentLength() == null
                  || head.contentLength() <= 0
                  || head.contentLength() > maximumBytes
                  || !version.equals(head.versionId()))
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "OBJECT_SIZE_OR_VERSION_MISMATCH"));
              return io(Mono.fromFuture(
                      () ->
                          client.getObject(
                              b -> b.bucket(bucket).key(key).versionId(version),
                              AsyncResponseTransformer.toPublisher())))
                  .flatMap(
                      body -> {
                        if (!version.equals(body.response().versionId())
                            || !Objects.equals(
                                head.contentLength(), body.response().contentLength()))
                          return Flux.from(body)
                              .take(1)
                              .then(Mono.error(fail(DomainStatus.DATA_LOSS, "OBJECT_CHANGED")));
                        return Flux.from(body)
                            .publishOn(Schedulers.parallel(), 1)
                            .reduceWith(
                                Digest::new,
                                (digest, bytes) -> {
                                  digest.bytes = Math.addExact(digest.bytes, bytes.remaining());
                                  if (digest.bytes > head.contentLength()
                                      || digest.bytes > maximumBytes)
                                    throw fail(DomainStatus.DATA_LOSS, "OBJECT_SIZE_CHANGED");
                                  digest.value.update(bytes.asReadOnlyBuffer());
                                  return digest;
                                })
                            .map(
                                digest -> {
                                  if (digest.bytes != head.contentLength())
                                    throw fail(DomainStatus.DATA_LOSS, "OBJECT_SIZE_CHANGED");
                                  return new Measurement(
                                      version,
                                      digest.bytes,
                                      head.contentType(),
                                      HexFormat.of().formatHex(digest.value.digest()));
                                })
                            .timeout(timeout);
                      });
            })
        .transform(this::io);
  }

  @Override
  public Mono<Void> deleteVersion(String key, String version) {
    valid(key, version);
    return requireVersioning()
        .then(
            io(
                Mono.fromFuture(
                    () -> client.deleteObject(b -> b.bucket(bucket).key(key).versionId(version)))))
        .then(absent(key, version))
        .flatMap(
            gone ->
                gone
                    ? Mono.empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "OBJECT_DELETION_UNCONFIRMED")));
  }

  @Override
  public Mono<Boolean> purgeKey(String key) {
    validKey(key);
    return requireVersioning()
        .then(
            io(
                Mono.fromFuture(
                    () ->
                        client.listObjectVersions(b -> b.bucket(bucket).prefix(key).maxKeys(100)))))
        .flatMap(
            page -> {
              var ids = new ArrayList<String>();
              for (ObjectVersion v : page.versions())
                if (key.equals(v.key())) ids.add(v.versionId());
              for (DeleteMarkerEntry v : page.deleteMarkers())
                if (key.equals(v.key())) ids.add(v.versionId());
              return Flux.fromIterable(ids)
                  .concatMap(id -> deleteVersion(key, id))
                  .then(page.isTruncated() ? Mono.just(false) : absent(key, null));
            });
  }

  private static final class Digest {
    long bytes;
    final MessageDigest value;

    Digest() {
      try {
        value = MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private Mono<Boolean> absent(String key, String version) {
    return Mono.fromFuture(
            () ->
                client.headObject(
                    b -> {
                      b.bucket(bucket).key(key);
                      if (version != null) b.versionId(version);
                    }))
        .map(r -> false)
        .onErrorResume(
            S3Exception.class,
            e ->
                e.statusCode() == 404
                    ? Mono.just(true)
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "OBJECT_STORE_UNAVAILABLE")))
        .timeout(timeout)
        .onErrorMap(
            e ->
                e instanceof DomainException
                    ? e
                    : fail(DomainStatus.UNAVAILABLE, "OBJECT_STORE_UNAVAILABLE"));
  }

  private <T> Mono<T> io(Mono<T> work) {
    return work.timeout(timeout)
        .onErrorMap(
            e ->
                e instanceof DomainException
                    ? e
                    : fail(DomainStatus.UNAVAILABLE, "OBJECT_STORE_UNAVAILABLE"));
  }

  private static void valid(String key, String version) {
    validKey(key);
    if (version == null || version.isBlank() || "null".equals(version))
      throw fail(DomainStatus.INVALID_ARGUMENT, "PINNED_OBJECT_VERSION_REQUIRED");
  }

  private static void validKey(String key) {
    if (key == null
        || key.isBlank()
        || !key.startsWith("tenant/")
        || key.contains("..")
        || key.startsWith("/")
        || key.contains("\\"))
      throw fail(DomainStatus.INVALID_ARGUMENT, "TENANT_MANIFEST_KEY_REQUIRED");
  }

  private static DomainException fail(DomainStatus status, String reason) {
    return new DomainException(status, reason);
  }
}
