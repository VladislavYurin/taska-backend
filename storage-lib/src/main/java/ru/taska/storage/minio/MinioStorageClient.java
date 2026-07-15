package ru.taska.storage.minio;

import lombok.extern.slf4j.Slf4j;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.PresignedUploadResult;
import ru.taska.storage.StorageClient;
import ru.taska.storage.config.StorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.util.UUID;

@Slf4j
public class MinioStorageClient implements StorageClient {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    public MinioStorageClient(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    @Override
    public String putObject(InputStream data, String contentType, long contentLength) {
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Content type not allowed: " + contentType);
        }
        if (contentLength > properties.getMaxFileSizeBytes()) {
            throw new DomainException(DomainStatus.OUT_OF_RANGE, "File size " + contentLength +
                    " bytes exceeds maximum allowed size of " + properties.getMaxFileSizeBytes() + " bytes");
        }

        String bucket = properties.getBucket();
        String objectKey = UUID.randomUUID().toString();

        log.debug("Putting object to bucket={}, objectKey={}, contentType={}, contentLength={}",
                bucket, objectKey, contentType, contentLength);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));

        log.debug("Successfully put object to bucket={}, objectKey={}", bucket, objectKey);

        return objectKey;
    }

    @Override
    public InputStream getObject(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Getting object from bucket={}, objectKey={}", bucket, objectKey);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        try {
            return s3Client.getObject(request);
        } catch (NoSuchKeyException e) {
            log.warn("Object not found in bucket={}, objectKey={}", bucket, objectKey);
            throw new DomainException(DomainStatus.NOT_FOUND, "Object not found: " + objectKey);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Deleting object from bucket={}, objectKey={}", bucket, objectKey);

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();

        s3Client.deleteObject(request);

        log.debug("Successfully deleted object from bucket={}, objectKey={}", bucket, objectKey);
    }

    @Override
    public PresignedUploadResult createPresignedUploadUrl(String contentType) {
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Content type not allowed: " + contentType);
        }

        String bucket = properties.getBucket();
        String objectKey = UUID.randomUUID().toString();

        log.debug("Creating presigned upload URL for bucket={}, objectKey={}, contentType={}", bucket, objectKey, contentType);

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignedUrlTtl())
                .putObjectRequest(r -> r
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType))
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

        return new PresignedUploadResult(objectKey, presigned.url().toString());
    }

    @Override
    public String createPresignedDownloadUrl(String objectKey) {
        String bucket = properties.getBucket();
        log.debug("Creating presigned download URL for bucket={}, objectKey={}", bucket, objectKey);

        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (NoSuchKeyException e) {
            log.warn("Object not found in bucket={}, objectKey={}", bucket, objectKey);
            throw new DomainException(DomainStatus.NOT_FOUND, "Object not found: " + objectKey);
        }

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(properties.getPresignedUrlTtl())
                .getObjectRequest(r -> r
                        .bucket(bucket)
                        .key(objectKey))
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);

        return presigned.url().toString();
    }

    @Override
    public void validateObjectSizeAndDeleteIfNeeded(String objectKey) {
        String bucket = properties.getBucket();
        long maxFileSize = properties.getMaxFileSizeBytes();

        long size;
        try {
            size = s3Client.headObject(HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .contentLength();
        } catch (NoSuchKeyException e) {
            log.warn("Object not found in bucket={}, objectKey={}", bucket, objectKey);
            throw new DomainException(DomainStatus.NOT_FOUND, "Object not found: " + objectKey);
        }

        log.debug("Validating uploaded object size: bucket={}, objectKey={}, size={}, maxAllowed={}",
                bucket, objectKey, size, maxFileSize);

        if (size > maxFileSize) {
            deleteTooLargeObject(objectKey, size, maxFileSize, bucket);
            throw new DomainException(DomainStatus.OUT_OF_RANGE, "File size " + size +
                    " bytes exceeds maximum allowed size of " + maxFileSize + " bytes");
        }
    }

    private void deleteTooLargeObject(String objectKey, long size, long maxFileSize, String bucket) {
        log.warn("Uploaded object exceeds size limit, deleting: bucket={}, objectKey={}, size={}, maxAllowed={}",
                bucket, objectKey, size, maxFileSize);
        try {
            deleteObject(objectKey);
        } catch (Exception e) {
            log.error("Failed to delete oversized object: bucket={}, objectKey={}, size={}: {}",
                    bucket, objectKey, size, e.getMessage());
            throw new DomainException(DomainStatus.INTERNAL, "Failed to delete oversized object: " + objectKey);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(objectKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
