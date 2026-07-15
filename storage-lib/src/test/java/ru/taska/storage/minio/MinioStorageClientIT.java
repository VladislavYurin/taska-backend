package ru.taska.storage.minio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.AbstractIT;
import ru.taska.storage.StorageClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class MinioStorageClientIT extends AbstractIT {

    @Autowired
    private StorageClient storageClient;

    @MockitoSpyBean
    private S3Client s3Client;

    @Test
    void validateObjectSizeAndDeleteIfNeeded_throwsOutOfRangeAndDeletesWhenTooLarge() {
        String objectKey = UUID.randomUUID().toString();
        byte[] content = new byte[120]; // превышает лимит в 100 байт

        // вручную добавляем объект в хранилище
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(objectKey)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content)
        );

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfNeeded(objectKey))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));

        assertThat(storageClient.objectExists(objectKey)).isFalse();
    }

    @Test
    void validateObjectSizeAndDeleteIfNeeded_doesNotDeleteWhenSizeWithinLimit() {
        byte[] content = new byte[100]; // ровно на границе лимита
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length);

        assertThatNoException()
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfNeeded(objectKey));

        assertThat(storageClient.objectExists(objectKey)).isTrue();
    }

    @Test
    void validateObjectSizeAndDeleteIfNeeded_throwsInternalWhenDeletionFails() {
        String objectKey = UUID.randomUUID().toString();
        byte[] content = new byte[120]; // превышает лимит в 100 байт

        // вручную добавляем объект в хранилище
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(objectKey)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content)
        );

        doThrow(new RuntimeException("Connection refused"))
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfNeeded(objectKey))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.INTERNAL));
    }

    @Test
    void getObject_returnsInputStreamForExistingObject() throws Exception {
        byte[] content = "hello from minio".getBytes();
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length);

        try (InputStream result = storageClient.getObject(objectKey)) {
            assertThat(result.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void validateObjectSizeAndDeleteIfNeeded_throwsNotFoundForMissingObject() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.validateObjectSizeAndDeleteIfNeeded(UUID.randomUUID().toString()))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    @Test
    void deleteObject_objectNoLongerExistsAfterDeletion() {
        byte[] content = new byte[10];
        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(content), "image/jpeg", content.length);

        storageClient.deleteObject(objectKey);

        assertThat(storageClient.objectExists(objectKey)).isFalse();
    }
}
