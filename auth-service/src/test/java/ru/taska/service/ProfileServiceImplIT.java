package ru.taska.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProfileServiceImpl Integration Tests")
class ProfileServiceImplIT extends AbstractIT {

    private static final String CONTENT_TYPE = "image/png";
    private static final String FILE_NAME = "avatar.png";
    private static final byte[] FILE_CONTENT = "fake-image-content".getBytes(StandardCharsets.UTF_8);
    public static final String TEST_USER_DISPLAY_NAME = "Test User";
    public static final String TEST_USER_EMAIL = "test@example.com";

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

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .login("testuser")
                .email(TEST_USER_EMAIL)
                .displayName(TEST_USER_DISPLAY_NAME)
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
        String oldObjectKey = uploadFileToS3(FILE_CONTENT);
        AvatarDto firstAvatar = profileService.confirmAvatarUpload(
                userId, oldObjectKey, "old-avatar.png", CONTENT_TYPE
        ).block();

        assertThat(firstAvatar).isNotNull();
        assertThat(storageClient.objectExists(oldObjectKey).block()).isTrue();

        byte[] newContent = "new-fake-image-content".getBytes(StandardCharsets.UTF_8);
        String newObjectKey = uploadFileToS3(newContent);

        AvatarDto replacedAvatar = profileService.confirmAvatarUpload(
                userId, newObjectKey, "new-avatar.png", CONTENT_TYPE
        ).block();

        assertThat(replacedAvatar).isNotNull();
        assertThat(replacedAvatar.getObjectKey()).isEqualTo(newObjectKey);
        assertThat(replacedAvatar.getFileName()).isEqualTo("new-avatar.png");
        assertThat(replacedAvatar.getDownloadUrl()).isNotBlank();

        UserAvatar dbAvatar = userAvatarRepository.findByUserId(userId).block();
        assertThat(dbAvatar).isNotNull();
        assertThat(dbAvatar.getObjectKey()).isEqualTo(newObjectKey);

        assertThat(storageClient.objectExists(oldObjectKey).block()).isFalse();
        assertThat(storageClient.objectExists(newObjectKey).block()).isTrue();
    }

    @Test
    @DisplayName("Полный цикл: createUploadUrl → confirm → getUserProfile → delete")
    void fullAvatarLifecycle() {
        PresignedUploadResult uploadResult = profileService.createAvatarUploadUrl(
                userId, CONTENT_TYPE, FILE_CONTENT.length
        ).block();

        assertThat(uploadResult).isNotNull();
        assertThat(uploadResult.objectKey()).isNotBlank();
        assertThat(uploadResult.url()).isNotBlank();

        putObjectDirect(uploadResult.objectKey(), FILE_CONTENT);

        AvatarDto confirmed = profileService.confirmAvatarUpload(
                userId, uploadResult.objectKey(), FILE_NAME, CONTENT_TYPE
        ).block();

        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getObjectKey()).isEqualTo(uploadResult.objectKey());
        assertThat(confirmed.getFileName()).isEqualTo(FILE_NAME);
        assertThat(confirmed.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(confirmed.getDownloadUrl()).isNotBlank();
        assertThat(confirmed.getCreatedAt()).isNotNull();

        UserProfileDto profile = profileService.getUserProfile(userId).block();

        assertThat(profile).isNotNull();
        assertThat(profile.getAvatar()).isNotNull();
        assertThat(profile.getAvatar().getObjectKey()).isEqualTo(uploadResult.objectKey());
        assertThat(profile.getAvatar().getDownloadUrl()).isNotBlank();

        String objectKeyToDelete = uploadResult.objectKey();

        StepVerifier.create(profileService.deleteMyAvatar(userId))
                .verifyComplete();

        UserProfileDto profileAfterDelete = profileService.getUserProfile(userId).block();
        assertThat(profileAfterDelete).isNotNull();
        assertThat(profileAfterDelete.getAvatar()).isNull();

        assertThat(storageClient.objectExists(objectKeyToDelete).block()).isFalse();
    }

    @Test
    @DisplayName("confirmAvatarUpload: файл в S3 превышает лимит — ошибка, файл удалён из S3, запись не создана")
    void confirmAvatarUpload_oversizedFile_deletedFromS3() {
        byte[] oversizedContent = new byte[2_097_153];
        String objectKey = uploadFileToS3(oversizedContent);
        assertThat(storageClient.objectExists(objectKey).block()).isTrue();

        StepVerifier.create(profileService.confirmAvatarUpload(
                        userId, objectKey, "big-avatar.png", CONTENT_TYPE))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    assertThat(ex.getStatus()).isEqualTo(DomainStatus.OUT_OF_RANGE);
                })
                .verify();

        assertThat(storageClient.objectExists(objectKey).block()).isFalse();
        assertThat(userAvatarRepository.findByUserId(userId).block()).isNull();
    }

    @Test
    @DisplayName("confirmAvatarUpload: объект не существует в S3 — ошибка, запись не создана")
    void confirmAvatarUpload_objectNotInS3_error() {
        String nonExistentKey = UUID.randomUUID().toString();

        StepVerifier.create(profileService.confirmAvatarUpload(
                        userId, nonExistentKey, "ghost.png", CONTENT_TYPE))
                .expectError()
                .verify();

        assertThat(userAvatarRepository.findByUserId(userId).block()).isNull();
    }

    @Test
    @DisplayName("getUserDetailsByIds должен возвращать данные пользователей с обогащенными аватарами и сохранять порядок")
    void getUserDetailsByIds_shouldReturnEnrichedUserDetailsInOrder() {
        String objectKey = uploadFileToS3(FILE_CONTENT);

        UserAvatar avatar = new UserAvatar();
        avatar.setUserId(userId);
        avatar.setObjectKey(objectKey);
        avatar.setFileName(FILE_NAME);
        avatar.setContentType(CONTENT_TYPE);
        avatar.setSizeBytes((long) FILE_CONTENT.length);
        avatar.setCreatedAt(Instant.now());

        userAvatarRepository.save(avatar).block();

        User userWithoutAvatar = User.builder()
                .login("noavatar")
                .email("noavatar@example.com")
                .displayName("No Avatar User")
                .status(UserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();
        UUID user2Id = userRepository.save(userWithoutAvatar).block().getId();

        profileService.getUserDetailsByIds(List.of(userId, user2Id))
                .as(StepVerifier::create)
                .assertNext(dto -> {
                    assertThat(dto.userId()).isEqualTo(userId);
                    assertThat(dto.displayName()).isEqualTo(TEST_USER_DISPLAY_NAME);
                    assertThat(dto.email()).isEqualTo(TEST_USER_EMAIL);

                    assertThat(dto.avatar()).isNotNull();
                    assertThat(dto.avatar().getFileName()).isEqualTo(FILE_NAME);
                    assertThat(dto.avatar().getContentType()).isEqualTo(CONTENT_TYPE);
                    assertThat(dto.avatar().getSizeBytes()).isEqualTo(FILE_CONTENT.length);
                    assertThat(dto.avatar().getDownloadUrl())
                            .isNotNull()
                            .contains(BUCKET_NAME)
                            .contains("http");
                })
                .assertNext(dto -> {
                    assertThat(dto.userId()).isEqualTo(user2Id);
                    assertThat(dto.displayName()).isEqualTo("No Avatar User");
                    assertThat(dto.email()).isEqualTo("noavatar@example.com");
                    assertThat(dto.avatar()).isNull();
                })
                .verifyComplete();
    }

    private String uploadFileToS3(byte[] content) {
        String objectKey = UUID.randomUUID().toString();
        putObjectDirect(objectKey, content);
        return objectKey;
    }

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