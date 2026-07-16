package ru.taska.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.client.StorageClient;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

class MinioStorageClientIT extends AbstractIT {

    @Autowired
    private StorageClient storageClient;

    @MockitoSpyBean
    private S3AsyncClient s3AsyncClient;

    @Test
    void validateObjectSizeAndDeleteIfNeeded_throwsOutOfRangeAndDeletesWhenTooLarge() {
        String objectKey = UUID.randomUUID().toString();
        byte[] content = new byte[120]; // превышает лимит в 100 байт

        // вручную добавляем объект в хранилище
        s3AsyncClient.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(objectKey)
                        .contentLength((long) content.length)
                        .build(),
                AsyncRequestBody.fromBytes(content)
        ).join();

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfTooLarge(objectKey).block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));

        assertThat(storageClient.objectExists(objectKey).block()).isFalse();
    }

    @Test
    void validateObjectSizeAndDeleteIfNeeded_doesNotDeleteWhenSizeWithinLimit() {
        byte[] content = new byte[100]; // ровно на границе лимита
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length).block();

        assertThatNoException()
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfTooLarge(objectKey).block());

        assertThat(storageClient.objectExists(objectKey).block()).isTrue();
    }

    @Test
    void validateObjectSizeAndDeleteIfTooLarge_throwsOutOfRangeWhenDeletionFails() {
        String objectKey = UUID.randomUUID().toString();
        byte[] content = new byte[120]; // превышает лимит в 100 байт

        // вручную добавляем объект в хранилище
        s3AsyncClient.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(objectKey)
                        .contentLength((long) content.length)
                        .build(),
                AsyncRequestBody.fromBytes(content)
        ).join();

        doReturn(CompletableFuture.<DeleteObjectResponse>failedFuture(new RuntimeException("Connection refused")))
                .when(s3AsyncClient).deleteObject(any(DeleteObjectRequest.class));

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfTooLarge(objectKey).block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));
    }

    @Test
    void getObject_returnsContentForExistingObject() {
        byte[] content = "hello from minio".getBytes();
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length).block();

        byte[] result = storageClient.getObject(objectKey).block();

        assertThat(result).isEqualTo(content);
    }

    @Test
    void validateObjectSizeAndDeleteIfNeeded_throwsNotFoundForMissingObject() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfTooLarge(UUID.randomUUID().toString()).block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    @Test
    void deleteObject_objectNoLongerExistsAfterDeletion() {
        byte[] content = new byte[10];
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length).block();

        storageClient.deleteObject(objectKey).block();

        assertThat(storageClient.objectExists(objectKey).block()).isFalse();
    }
}
