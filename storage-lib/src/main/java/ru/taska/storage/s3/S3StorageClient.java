package ru.taska.storage.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.config.StorageProperties;
import ru.taska.storage.dto.PresignedUploadResult;
import software.amazon.awssdk.core.BytesWrapper;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class S3StorageClient implements StorageClient {

    private final S3AsyncClient s3AsyncClient;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    @Override
    public Mono<String> putObject(InputStream data, String contentType, long contentLength) {
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Content type not allowed: " + contentType));
        }

        if (contentLength > properties.getMaxFileSizeBytes()) {
            return Mono.error(new DomainException(DomainStatus.OUT_OF_RANGE, "File size " + contentLength +
                    " bytes exceeds maximum allowed size of " + properties.getMaxFileSizeBytes() + " bytes"));
        }

        String bucket = properties.getBucket();
        String objectKey = UUID.randomUUID().toString();

        log.debug("Putting object to bucket={}, objectKey={}, contentType={}, contentLength={}",
                bucket, objectKey, contentType, contentLength);

        return Mono.fromCallable(data::readAllBytes)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(bytes -> {
                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(contentLength)
                            .build();
                    return Mono.fromFuture(() -> s3AsyncClient.putObject(request, AsyncRequestBody.fromBytes(bytes)));
                })
                .map(r -> objectKey)
                .doOnSuccess(key -> log.debug("Successfully put object to bucket={}, objectKey={}", bucket, key))
                .transform(S3ExceptionHandler.withErrorHandling("putObject[" + bucket + "/" + objectKey + "]"));
    }

    @Override
    public Mono<byte[]> getObject(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Getting object from bucket={}, objectKey={}", bucket, objectKey);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.getObject(request, AsyncResponseTransformer.toBytes()))
                .map(BytesWrapper::asByteArray)
                .transform(S3ExceptionHandler.withErrorHandling("getObject[" + bucket + "/" + objectKey + "]"));
    }

    @Override
    public Mono<Void> deleteObject(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Deleting object from bucket={}, objectKey={}", bucket, objectKey);

        return deleteObjectRaw(objectKey, bucket)
                .transform(S3ExceptionHandler.withErrorHandling("deleteObject[" + bucket + "/" + objectKey + "]"));
    }

    @Override
    public Mono<PresignedUploadResult> createPresignedUploadUrl(String contentType) {
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Content type not allowed: " + contentType));
        }

        String bucket = properties.getBucket();
        String objectKey = UUID.randomUUID().toString();

        log.debug("Creating presigned upload URL for bucket={}, objectKey={}, contentType={}", bucket, objectKey, contentType);

        return Mono.fromCallable(() -> {
                    PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                            .signatureDuration(properties.getPresignedUrlTtl())
                            .putObjectRequest(r -> r
                                    .bucket(bucket)
                                    .key(objectKey)
                                    .contentType(contentType))
                            .build();
                    return new PresignedUploadResult(objectKey, s3Presigner.presignPutObject(presignRequest).url().toString());
                })
                .transform(S3ExceptionHandler.withErrorHandling("createPresignedUploadUrl[" + bucket + "/" + objectKey + "]"));
    }

    @Override
    public Mono<String> createPresignedDownloadUrl(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Creating presigned download URL for bucket={}, objectKey={}", bucket, objectKey);

        return Mono.fromFuture(() -> s3AsyncClient.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()))
                .map(r -> {
                    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                            .signatureDuration(properties.getPresignedUrlTtl())
                            .getObjectRequest(req -> req
                                    .bucket(bucket)
                                    .key(objectKey))
                            .build();
                    return s3Presigner.presignGetObject(presignRequest).url().toString();
                })
                .transform(S3ExceptionHandler.withErrorHandling("createPresignedDownloadUrl[" + bucket + "/" + objectKey + "]"));
    }

    @Override
    public Mono<Void> validateObjectSizeAndDeleteIfTooLarge(String objectKey) {
        String bucket = properties.getBucket();
        long maxFileSize = properties.getMaxFileSizeBytes();

        return Mono.fromFuture(() -> s3AsyncClient.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()))
                .flatMap(response -> {
                    long size = response.contentLength();
                    log.debug("Validating uploaded object size: bucket={}, objectKey={}, size={}, maxAllowed={}",
                            bucket, objectKey, size, maxFileSize);

                    if (size > maxFileSize) {
                        log.warn("Uploaded object exceeds size limit, deleting: bucket={}, objectKey={}, size={}, maxAllowed={}",
                                bucket, objectKey, size, maxFileSize);
                        return deleteObjectRaw(objectKey, bucket)
                                // onErrorResume добавлен, чтобы в случае ошибки при удалении перехватить её,
                                // чтобы она не спрятала сообщение о слишком большом файле
                                .onErrorResume(e -> {
                                    log.error("Failed to delete oversized object: bucket={}, objectKey={}: {}", bucket, objectKey, e.getMessage());
                                    return Mono.empty();
                                })
                                .then(Mono.<Void>error(new DomainException(DomainStatus.OUT_OF_RANGE, "File size " + size +
                                        " bytes exceeds maximum allowed size of " + maxFileSize + " bytes")));
                    }
                    return Mono.empty();
                })
                .transform(S3ExceptionHandler.withErrorHandling(
                        "validateObjectSizeAndDeleteIfTooLarge[" + bucket + "/" + objectKey + "]"));
    }

    private Mono<Void> deleteObjectRaw(String objectKey, String bucket) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.deleteObject(request))
                .doOnSuccess(r -> log.debug("Successfully deleted object from bucket={}, objectKey={}", bucket, objectKey))
                .then();
    }

    @Override
    public Mono<Boolean> objectExists(String objectKey) {
        String bucket = properties.getBucket();

        return Mono.fromFuture(() -> s3AsyncClient.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()))
                .map(r -> true)
                // Перехватываем NoSuchKeyException и возвращаем false, так как в objectExists это не ошибка, а нормальный результат
                .onErrorReturn(NoSuchKeyException.class, false)
                .transform(S3ExceptionHandler.withErrorHandling("objectExists[" + bucket + "/" + objectKey + "]"));
    }
}
