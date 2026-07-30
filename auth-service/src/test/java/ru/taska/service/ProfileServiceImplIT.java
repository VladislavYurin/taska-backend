package ru.taska.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import reactor.test.StepVerifier;
import ru.taska.AbstractIT;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.entity.User;
import ru.taska.entity.UserAvatar;
import ru.taska.entity.UserStatus;
import ru.taska.repository.UserAvatarRepository;
import ru.taska.repository.UserRepository;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProfileServiceImpl Integration Tests")
class ProfileServiceImplIT extends AbstractIT {

    private static final String BUCKET_NAME = "test-bucket";
    private static final String CONTENT_TYPE = "image/png";
    private static final String FILE_NAME = "avatar.png";
    private static final byte[] FILE_CONTENT = "fake-image-content".getBytes(StandardCharsets.UTF_8);

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAvatarRepository userAvatarRepository;

    @Autowired
    private StorageClient storageClient;

    @Autowired
    private S3AsyncClient s3AsyncClient;

    private UUID userId;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("storage.endpoint", minio::getS3URL);
        registry.add("storage.public-url", minio::getS3URL);
        registry.add("storage.access-key", minio::getUserName);
        registry.add("storage.secret-key", minio::getPassword);
        registry.add("storage.region", () -> "us-east-1");
        registry.add("storage.presigned-url-ttl", () -> "PT1H");
        registry.add("storage.allowed-content-types", () -> "image/jpeg,image/png,image/webp");
        registry.add("storage.max-file-size-bytes", () -> "2097152");
        registry.add("storage.bucket", () -> BUCKET_NAME);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client adminClient = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .forcePathStyle(true)
                .build()) {
            adminClient.createBucket(b -> b.bucket(BUCKET_NAME));
        }
    }

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .login("testuser")
                .email("test@example.com")
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();
        userId = userRepository.save(user).block().getId();
    }

    @AfterEach
    void tearDown() {
        userAvatarRepository.deleteAll().block();
        userRepository.deleteAll().block();
    }

    @Test
    @DisplayName("confirmAvatarUpload: замена аватара — старая запись удалена из БД, старый файл удалён из S3")
    void confirmAvatarUpload_replaceExistingAvatar() {
        // Given: загружаем первый файл в S3 и подтверждаем аватар
        String oldObjectKey = uploadFileToS3(FILE_CONTENT);
        AvatarDto firstAvatar = profileService.confirmAvatarUpload(
                userId, oldObjectKey, "old-avatar.png", CONTENT_TYPE
        ).block();

        assertThat(firstAvatar).isNotNull();
        assertThat(storageClient.objectExists(oldObjectKey).block()).isTrue();

        // Загружаем второй файл в S3
        byte[] newContent = "new-fake-image-content".getBytes(StandardCharsets.UTF_8);
        String newObjectKey = uploadFileToS3(newContent);

        // When: подтверждаем новый аватар (замена)
        AvatarDto replacedAvatar = profileService.confirmAvatarUpload(
                userId, newObjectKey, "new-avatar.png", CONTENT_TYPE
        ).block();

        // Then: новый аватар сохранён в БД
        assertThat(replacedAvatar).isNotNull();
        assertThat(replacedAvatar.getObjectKey()).isEqualTo(newObjectKey);
        assertThat(replacedAvatar.getFileName()).isEqualTo("new-avatar.png");
        assertThat(replacedAvatar.getDownloadUrl()).isNotBlank();

        // В БД ровно одна запись — новая
        UserAvatar dbAvatar = userAvatarRepository.findByUserId(userId).block();
        assertThat(dbAvatar).isNotNull();
        assertThat(dbAvatar.getObjectKey()).isEqualTo(newObjectKey);

        // Старый файл удалён из S3, новый на месте
        assertThat(storageClient.objectExists(oldObjectKey).block()).isFalse();
        assertThat(storageClient.objectExists(newObjectKey).block()).isTrue();
    }

    @Test
    @DisplayName("Полный цикл: createUploadUrl → confirm → getUserProfile → delete")
    void fullAvatarLifecycle() {
        // 1. Получаем presigned URL для загрузки
        PresignedUploadResult uploadResult = profileService.createAvatarUploadUrl(
                userId, CONTENT_TYPE, FILE_CONTENT.length
        ).block();

        assertThat(uploadResult).isNotNull();
        assertThat(uploadResult.objectKey()).isNotBlank();
        assertThat(uploadResult.url()).isNotBlank();

        // Загружаем файл напрямую в S3 по objectKey (эмулируем загрузку фронтендом)
        putObjectDirect(uploadResult.objectKey(), FILE_CONTENT);

        // 2. Подтверждаем загрузку
        AvatarDto confirmed = profileService.confirmAvatarUpload(
                userId, uploadResult.objectKey(), FILE_NAME, CONTENT_TYPE
        ).block();

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getObjectKey()).isEqualTo(uploadResult.objectKey());
        assertThat(confirmed.getFileName()).isEqualTo(FILE_NAME);
        assertThat(confirmed.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(confirmed.getDownloadUrl()).isNotBlank();
        assertThat(confirmed.getCreatedAt()).isNotNull();

        // 3. Получаем профиль — аватар присутствует
        UserProfileDto profile = profileService.getUserProfile(userId).block();

        assertThat(profile).isNotNull();
        assertThat(profile.getAvatar()).isNotNull();
        assertThat(profile.getAvatar().getObjectKey()).isEqualTo(uploadResult.objectKey());
        assertThat(profile.getAvatar().getDownloadUrl()).isNotBlank();

        // 4. Удаляем аватар
        String objectKeyToDelete = uploadResult.objectKey();

        StepVerifier.create(profileService.deleteMyAvatar(userId))
                .verifyComplete();

        // 5. Профиль без аватара, файл удалён из S3
        UserProfileDto profileAfterDelete = profileService.getUserProfile(userId).block();
        assertThat(profileAfterDelete).isNotNull();
        assertThat(profileAfterDelete.getAvatar()).isNull();

        assertThat(storageClient.objectExists(objectKeyToDelete).block()).isFalse();
    }

    @Test
    @DisplayName("confirmAvatarUpload: файл в S3 превышает лимит — ошибка, файл удалён из S3, запись не создана")
    void confirmAvatarUpload_oversizedFile_deletedFromS3() {
        // Given: загружаем файл, превышающий maxFileSizeBytes (2 097 152)
        byte[] oversizedContent = new byte[2_097_153];
        String objectKey = uploadFileToS3(oversizedContent);
        assertThat(storageClient.objectExists(objectKey).block()).isTrue();

        // When & Then: подтверждение должно вернуть ошибку OUT_OF_RANGE
        StepVerifier.create(profileService.confirmAvatarUpload(
                        userId, objectKey, "big-avatar.png", CONTENT_TYPE))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE);
                })
                .verify();

        // Файл удалён из S3
        assertThat(storageClient.objectExists(objectKey).block()).isFalse();

        // Запись аватара в БД не создана
        assertThat(userAvatarRepository.findByUserId(userId).block()).isNull();
    }

    @Test
    @DisplayName("confirmAvatarUpload: объект не существует в S3 — ошибка, запись не создана")
    void confirmAvatarUpload_objectNotInS3_error() {
        // Given: objectKey, которого нет в S3
        String nonExistentKey = UUID.randomUUID().toString();

        // When & Then: подтверждение должно вернуть ошибку
        StepVerifier.create(profileService.confirmAvatarUpload(
                        userId, nonExistentKey, "ghost.png", CONTENT_TYPE))
                .expectError()
                .verify();

        // Запись аватара в БД не создана
        assertThat(userAvatarRepository.findByUserId(userId).block()).isNull();
    }

    /**
     * Загружает файл в S3, возвращает objectKey.
     */
    private String uploadFileToS3(byte[] content) {
        String objectKey = UUID.randomUUID().toString();
        putObjectDirect(objectKey, content);
        return objectKey;
    }

    /**
     * Кладёт объект напрямую в S3 по заданному ключу.
     */
    private void putObjectDirect(String objectKey, byte[] content) {
        s3AsyncClient.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(objectKey)
                        .contentType(CONTENT_TYPE)
                        .contentLength((long) content.length)
                        .build(),
                AsyncRequestBody.fromBytes(content)
        ).join();
    }
}
