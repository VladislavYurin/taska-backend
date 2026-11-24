package ru.taska.storage.versioned;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.exception.*;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

/** Bounded range collector: an S3 backend ignoring Range cannot allocate an entire file. */
public final class S3AttachmentContentObjects implements AttachmentContentObjects {
  private final S3AsyncClient s3;
  private final String bucket;

  public S3AttachmentContentObjects(S3AsyncClient s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  public Mono<Void> head(String key, String version, long total) {
    return Mono.defer(
            () -> {
              valid(key, version, total);
              return Mono.fromFuture(
                      s3.headObject(
                          HeadObjectRequest.builder()
                              .bucket(bucket)
                              .key(key)
                              .versionId(version)
                              .build()))
                  .flatMap(
                      h ->
                          Long.valueOf(total).equals(h.contentLength())
                                  && version.equals(h.versionId())
                              ? Mono.<Void>empty()
                              : Mono.error(corrupt()));
            })
        .timeout(Duration.ofSeconds(5))
        .onErrorMap(e -> e instanceof DomainException ? e : unavailable());
  }

  public Mono<byte[]> read(String key, String version, long total, long offset, int length) {
    return Mono.defer(
            () -> {
              valid(key, version, total);
              if (offset < 0
                  || length < 1
                  || length > MAX_CHUNK
                  || offset >= total
                  || length > total - offset)
                return Mono.error(
                    new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_ATTACHMENT_RANGE"));
              long end = offset + length - 1;
              String range = "bytes " + offset + "-" + end + "/" + total;
              return Mono.fromFuture(
                      s3.getObject(
                          GetObjectRequest.builder()
                              .bucket(bucket)
                              .key(key)
                              .versionId(version)
                              .range("bytes=" + offset + "-" + end)
                              .build(),
                          AsyncResponseTransformer.toPublisher()))
                  .flatMap(
                      p -> {
                        var r = p.response();
                        if (!Long.valueOf(length).equals(r.contentLength())
                            || !version.equals(r.versionId())
                            || !range.equals(r.contentRange())) {
                          // Subscribe then cancel so the SDK releases the HTTP connection on a bad
                          // response.
                          return Flux.from(p).take(0).then(Mono.<byte[]>error(corrupt()));
                        }
                        return Flux.from(p)
                            .reduce(
                                new ByteArrayOutputStream(length),
                                (out, b) -> {
                                  if (b.remaining() > length - out.size()) throw corrupt();
                                  byte[] part = new byte[b.remaining()];
                                  b.get(part);
                                  out.writeBytes(part);
                                  return out;
                                })
                            .map(
                                out -> {
                                  if (out.size() != length) throw corrupt();
                                  return out.toByteArray();
                                });
                      });
            })
        .timeout(Duration.ofSeconds(5))
        .onErrorMap(e -> e instanceof DomainException ? e : unavailable());
  }

  private static void valid(String key, String version, long total) {
    if (key == null
        || !key.startsWith("tenant/")
        || version == null
        || version.isBlank()
        || version.equals("null")
        || total < 1) throw corrupt();
  }

  private static DomainException corrupt() {
    return new DomainException(DomainStatus.DATA_LOSS, "ATTACHMENT_OBJECT_MISMATCH");
  }

  private static DomainException unavailable() {
    return new DomainException(DomainStatus.UNAVAILABLE, "ATTACHMENT_OBJECT_UNAVAILABLE");
  }
}
