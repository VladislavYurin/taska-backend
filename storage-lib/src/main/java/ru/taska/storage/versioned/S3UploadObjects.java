package ru.taska.storage.versioned;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** Bounded PUT и копирование конкретной версии; ошибки не удаляют файлы. */
public final class S3UploadObjects implements UploadObjects {
  private final S3AsyncClient s3;
  private final S3Presigner presigner;
  private final VersionedObjects objects;
  private final String bucket;
  private final Clock clock;

  public S3UploadObjects(
      S3AsyncClient s3,
      S3Presigner presigner,
      VersionedObjects objects,
      String bucket,
      Clock clock) {
    this.s3 = s3;
    this.presigner = presigner;
    this.objects = objects;
    this.bucket = bucket;
    this.clock = clock;
  }

  public Mono<Capability> presign(String key, String mime, long bytes, Instant until) {
    return objects
        .requireVersioning()
        .then(
            Mono.fromCallable(
                    () -> {
                      long seconds = Duration.between(clock.instant(), until).getSeconds() - 1;
                      if (seconds < 1 || seconds > 300) throw invalid("UPLOAD_WINDOW_CLOSED");
                      var request =
                          PutObjectRequest.builder()
                              .bucket(bucket)
                              .key(key)
                              .contentType(mime)
                              .contentLength(bytes)
                              .build();
                      return new Capability(
                          presigner
                              .presignPutObject(
                                  PutObjectPresignRequest.builder()
                                      .signatureDuration(Duration.ofSeconds(seconds))
                                      .putObjectRequest(request)
                                      .build())
                              .url()
                              .toString());
                    })
                .subscribeOn(Schedulers.boundedElastic()));
  }

  public Mono<String> stagingVersion(String key, String mime, long bytes) {
    return objects
        .requireVersioning()
        .then(head(key))
        .map(
            h -> {
              if (h.contentLength() == null
                  || h.contentLength() < 1
                  || h.contentLength() > bytes
                  || !mime.equals(h.contentType())) throw invalid("UPLOAD_METADATA_MISMATCH");
              return version(h.versionId());
            });
  }

  public Mono<String> existingFinal(String destination, String sourceVersion, String intent) {
    return objects
        .requireVersioning()
        .then(
            head(destination)
                .map(
                    h -> {
                      if (!intent.equals(h.metadata().get("taska-intent"))
                          || !sourceVersion.equals(h.metadata().get("taska-source-version")))
                        throw invalid("FINAL_OBJECT_CONFLICT");
                      return version(h.versionId());
                    })
                .onErrorResume(
                    S3Exception.class, e -> e.statusCode() == 404 ? Mono.empty() : Mono.error(e)));
  }

  public Mono<String> copyPinned(
      String staging, String sourceVersion, String destination, String intent, String mime) {
    return objects
        .requireVersioning()
        .then(
            existingFinal(destination, sourceVersion, intent)
                .switchIfEmpty(
                    Mono.defer(
                        () ->
                            Mono.fromFuture(
                                    s3.copyObject(
                                        CopyObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(destination)
                                            .copySource(
                                                bucket
                                                    + "/"
                                                    + staging
                                                    + "?versionId="
                                                    + URLEncoder.encode(
                                                        sourceVersion, StandardCharsets.UTF_8))
                                            .metadataDirective(MetadataDirective.REPLACE)
                                            .contentType(mime)
                                            .metadata(
                                                Map.of(
                                                    "taska-intent",
                                                    intent,
                                                    "taska-source-version",
                                                    sourceVersion))
                                            .build()))
                                .map(r -> version(r.versionId())))))
        .timeout(Duration.ofSeconds(5));
  }

  private Mono<HeadObjectResponse> head(String key) {
    return Mono.defer(
            () ->
                Mono.fromFuture(
                    s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())))
        .timeout(Duration.ofSeconds(5));
  }

  private static String version(String v) {
    if (v == null || v.isBlank() || v.equals("null")) throw invalid("VERSIONED_OBJECT_REQUIRED");
    return v;
  }

  private static DomainException invalid(String code) {
    return new DomainException(DomainStatus.FAILED_PRECONDITION, code);
  }
}
