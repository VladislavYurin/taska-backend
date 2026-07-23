package ru.taska.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.config.StorageProperties;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.storage.s3.S3StorageClient;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageClientTest {

    @Mock
    private S3AsyncClient s3AsyncClient;

    @Mock
    private S3Presigner s3Presigner;

    private final StorageProperties properties = new StorageProperties();
    private S3StorageClient storageClient;

    private static final String BUCKET = "test-bucket";
    private static final String ALLOWED_CONTENT_TYPE = "image/jpeg";
    private static final String DISALLOWED_CONTENT_TYPE = "application/octet-stream";
    private static final long MAX_FILE_SIZE_BYTES = 100L;

    @BeforeEach
    void setUp() {
        properties.setBucket(BUCKET);
        properties.setAllowedContentTypes(List.of(ALLOWED_CONTENT_TYPE, "image/png"));
        properties.setMaxFileSizeBytes(MAX_FILE_SIZE_BYTES);
        properties.setPresignedUrlTtl(Duration.ofHours(1));
        storageClient = new S3StorageClient(s3AsyncClient, s3Presigner, properties);
    }

    // ─── generate object key ──────────────────────────────────────────────────

    @Test
    void putObject_generatesValidUuidKey() {
        when(s3AsyncClient.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));

        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(new byte[50]), ALLOWED_CONTENT_TYPE, 50L).block();

        assertThatNoException().isThrownBy(() -> UUID.fromString(objectKey));
    }

    @Test
    void putObject_generatesDifferentKeyOnEachCall() {
        when(s3AsyncClient.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));

        String key1 = storageClient.putObject(
                new ByteArrayInputStream(new byte[50]), ALLOWED_CONTENT_TYPE, 50L).block();
        String key2 = storageClient.putObject(
                new ByteArrayInputStream(new byte[50]), ALLOWED_CONTENT_TYPE, 50L).block();

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void createPresignedUploadUrl_generatesValidUuidKey() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE, 50L).block();

        assertThatNoException().isThrownBy(() -> UUID.fromString(result.objectKey()));
    }

    @Test
    void createPresignedUploadUrl_generatesDifferentKeyOnEachCall() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result1 = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE, 50L).block();
        PresignedUploadResult result2 = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE, 50L).block();

        assertThat(result1.objectKey()).isNotEqualTo(result2.objectKey());
    }

    // ─── create presigned upload URL ──────────────────────────────────────────

    @Test
    void createPresignedUploadUrl_returnsUrlAndKey() throws Exception {
        String expectedUrl = "http://minio:9000/test-bucket/key?X-Amz-Signature=abc";
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE, 50L).block();

        assertThat(result.url()).isEqualTo(expectedUrl);
        assertThat(result.objectKey()).isNotEmpty();
    }

    @Test
    void createPresignedUploadUrl_usesConfiguredTtl() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        when(s3Presigner.presignPutObject(captor.capture())).thenReturn(presigned);

        storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE, 50L).block();

        assertThat(captor.getValue().signatureDuration()).isEqualTo(properties.getPresignedUrlTtl());
    }

    // ─── create presigned download URL ────────────────────────────────────────

    @Test
    void createPresignedDownloadUrl_returnsUrlForExistingObject() throws Exception {
        String objectKey = UUID.randomUUID().toString();
        String expectedUrl = "http://minio:9000/test-bucket/" + objectKey + "?X-Amz-Signature=abc";

        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()));
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = storageClient.createPresignedDownloadUrl(objectKey).block();

        assertThat(url).isEqualTo(expectedUrl);
    }

    @Test
    void createPresignedDownloadUrl_throwsNotFoundForMissingObject() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().build()));

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedDownloadUrl("missing-key").block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    @Test
    void createPresignedDownloadUrl_usesConfiguredTtl() throws Exception {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()));
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        when(s3Presigner.presignGetObject(captor.capture())).thenReturn(presigned);

        storageClient.createPresignedDownloadUrl(UUID.randomUUID().toString()).block();

        assertThat(captor.getValue().signatureDuration()).isEqualTo(properties.getPresignedUrlTtl());
    }

    // ─── get object ──────────────────────────────────────────────────────────

    @Test
    void getObject_throwsNotFoundForMissingObject() {
        when(s3AsyncClient.getObject(any(GetObjectRequest.class), any(AsyncResponseTransformer.class)))
                .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().build()));

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.getObject("missing-key").block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    // ─── object exists ────────────────────────────────────────────────────────

    @Test
    void objectExists_returnsTrueWhenObjectExists() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()));

        assertThat(storageClient.objectExists(UUID.randomUUID().toString()).block()).isTrue();
    }

    @Test
    void objectExists_returnsFalseWhenObjectNotFound() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().build()));

        assertThat(storageClient.objectExists(UUID.randomUUID().toString()).block()).isFalse();
    }

    // ─── reject invalid content type ──────────────────────────────────────────

    @Test
    void putObject_throwsInvalidArgumentForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[50]), DISALLOWED_CONTENT_TYPE, 50L).block())
                .satisfies(ex -> {
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(ex.getMessage()).contains(DISALLOWED_CONTENT_TYPE);
                });
    }

    @Test
    void putObject_doesNotCallS3ForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[50]), DISALLOWED_CONTENT_TYPE, 50L).block());

        verifyNoInteractions(s3AsyncClient);
    }

    @Test
    void createPresignedUploadUrl_throwsInvalidArgumentForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(DISALLOWED_CONTENT_TYPE, 50L).block())
                .satisfies(ex -> {
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(ex.getMessage()).contains(DISALLOWED_CONTENT_TYPE);
                });
    }

    @Test
    void createPresignedUploadUrl_doesNotCallPresignerForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(DISALLOWED_CONTENT_TYPE, 50L).block());

        verifyNoInteractions(s3Presigner);
    }

    // ─── reject too large file ────────────────────────────────────────────────

    @Test
    void putObject_throwsOutOfRangeWhenFileSizeExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[(int) MAX_FILE_SIZE_BYTES + 1]),
                        ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1).block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));
    }

    @Test
    void putObject_doesNotCallS3WhenFileSizeExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[(int) MAX_FILE_SIZE_BYTES + 1]),
                        ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1).block());

        verifyNoInteractions(s3AsyncClient);
    }

    @Test
    void createPresignedUploadUrl_throwsOutOfRangeWhenFileSizeExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(
                        ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1).block())
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));
    }

    @Test
    void createPresignedUploadUrl_doesNotCallPresignerWhenFileSizeExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(
                        ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1).block());

        verifyNoInteractions(s3Presigner);
    }

    @Test
    void createPresignedUploadUrl_succeedsWhenFileSizeEqualsLimit() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        assertThatNoException().isThrownBy(() -> storageClient.createPresignedUploadUrl(
                ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES).block());
    }

    @Test
    void putObject_succeedsWhenFileSizeEqualsLimit() {
        when(s3AsyncClient.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));

        assertThatNoException().isThrownBy(() -> storageClient.putObject(
                new ByteArrayInputStream(new byte[(int) MAX_FILE_SIZE_BYTES]),
                ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES).block());
    }
}
