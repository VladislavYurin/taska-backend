package ru.taska.storage.minio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.storage.PresignedUploadResult;
import ru.taska.storage.config.StorageProperties;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioStorageClientTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private final StorageProperties properties = new StorageProperties();
    private MinioStorageClient storageClient;

    private static final String BUCKET = "test-bucket";
    private static final String ALLOWED_CONTENT_TYPE = "image/jpeg";
    private static final String DISALLOWED_CONTENT_TYPE = "application/octet-stream";
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L; // 10 МБ

    @BeforeEach
    void setUp() {
        properties.setBucket(BUCKET);
        properties.setAllowedContentTypes(List.of(ALLOWED_CONTENT_TYPE, "image/png"));
        properties.setMaxFileSizeBytes(MAX_FILE_SIZE_BYTES);
        properties.setPresignedUrlTtl(Duration.ofHours(1));
        storageClient = new MinioStorageClient(s3Client, s3Presigner, properties);
    }

    // ─── generate object key ──────────────────────────────────────────────────

    @Test
    void putObject_generatesValidUuidKey() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String objectKey = storageClient.putObject(
                new ByteArrayInputStream(new byte[100]), ALLOWED_CONTENT_TYPE, 100L);

        assertThatNoException().isThrownBy(() -> UUID.fromString(objectKey));
    }

    @Test
    void putObject_generatesDifferentKeyOnEachCall() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key1 = storageClient.putObject(
                new ByteArrayInputStream(new byte[100]), ALLOWED_CONTENT_TYPE, 100L);
        String key2 = storageClient.putObject(
                new ByteArrayInputStream(new byte[100]), ALLOWED_CONTENT_TYPE, 100L);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void createPresignedUploadUrl_generatesValidUuidKey() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE);

        assertThatNoException().isThrownBy(() -> UUID.fromString(result.objectKey()));
    }

    @Test
    void createPresignedUploadUrl_generatesDifferentKeyOnEachCall() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result1 = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE);
        PresignedUploadResult result2 = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE);

        assertThat(result1.objectKey()).isNotEqualTo(result2.objectKey());
    }

    // ─── create presigned upload URL ──────────────────────────────────────────

    @Test
    void createPresignedUploadUrl_returnsUrlAndKey() throws Exception {
        String expectedUrl = "http://minio:9000/test-bucket/key?X-Amz-Signature=abc";
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

        PresignedUploadResult result = storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE);

        assertThat(result.url()).isEqualTo(expectedUrl);
        assertThat(result.objectKey()).isNotEmpty();
    }

    @Test
    void createPresignedUploadUrl_usesConfiguredTtl() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        when(s3Presigner.presignPutObject(captor.capture())).thenReturn(presigned);

        storageClient.createPresignedUploadUrl(ALLOWED_CONTENT_TYPE);

        assertThat(captor.getValue().signatureDuration()).isEqualTo(properties.getPresignedUrlTtl());
    }

    // ─── create presigned download URL ────────────────────────────────────────

    @Test
    void createPresignedDownloadUrl_returnsUrlForExistingObject() throws Exception {
        String objectKey = UUID.randomUUID().toString();
        String expectedUrl = "http://minio:9000/test-bucket/" + objectKey + "?X-Amz-Signature=abc";

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL(expectedUrl));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = storageClient.createPresignedDownloadUrl(objectKey);

        assertThat(url).isEqualTo(expectedUrl);
    }

    @Test
    void createPresignedDownloadUrl_throwsNotFoundForMissingObject() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedDownloadUrl("missing-key"))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    @Test
    void createPresignedDownloadUrl_usesConfiguredTtl() throws Exception {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("http://minio:9000/test-bucket/key?sig=abc"));

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        when(s3Presigner.presignGetObject(captor.capture())).thenReturn(presigned);

        storageClient.createPresignedDownloadUrl(UUID.randomUUID().toString());

        assertThat(captor.getValue().signatureDuration()).isEqualTo(properties.getPresignedUrlTtl());
    }

    // ─── get object ──────────────────────────────────────────────────────────

    @Test
    void getObject_throwsNotFoundForMissingObject() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.getObject("missing-key"))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND));
    }

    // ─── object exists ────────────────────────────────────────────────────────

    @Test
    void objectExists_returnsTrueWhenObjectExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        assertThat(storageClient.objectExists(UUID.randomUUID().toString())).isTrue();
    }

    @Test
    void objectExists_returnsFalseWhenObjectNotFound() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThat(storageClient.objectExists(UUID.randomUUID().toString())).isFalse();
    }

    // ─── reject invalid content type ──────────────────────────────────────────

    @Test
    void putObject_throwsInvalidArgumentForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[100]), DISALLOWED_CONTENT_TYPE, 100L))
                .satisfies(ex -> {
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(ex.getMessage()).contains(DISALLOWED_CONTENT_TYPE);
                });
    }

    @Test
    void putObject_doesNotCallS3ForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[100]), DISALLOWED_CONTENT_TYPE, 100L));

        verifyNoInteractions(s3Client);
    }

    @Test
    void createPresignedUploadUrl_throwsInvalidArgumentForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(DISALLOWED_CONTENT_TYPE))
                .satisfies(ex -> {
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    assertThat(ex.getMessage()).contains(DISALLOWED_CONTENT_TYPE);
                });
    }

    @Test
    void createPresignedUploadUrl_doesNotCallPresignerForDisallowedContentType() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.createPresignedUploadUrl(DISALLOWED_CONTENT_TYPE));

        verifyNoInteractions(s3Presigner);
    }

    // ─── reject too large file ────────────────────────────────────────────────

    @Test
    void putObject_throwsOutOfRangeWhenContentLengthExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[0]), ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1))
                .satisfies(ex -> assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE));
    }

    @Test
    void putObject_doesNotCallS3WhenContentLengthExceedsLimit() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> storageClient.putObject(
                        new ByteArrayInputStream(new byte[0]), ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES + 1));

        verifyNoInteractions(s3Client);
    }

    @Test
    void putObject_succeedsWhenContentLengthEqualsLimit() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertThatNoException().isThrownBy(() -> storageClient.putObject(
                new ByteArrayInputStream(new byte[0]), ALLOWED_CONTENT_TYPE, MAX_FILE_SIZE_BYTES));
    }
}
