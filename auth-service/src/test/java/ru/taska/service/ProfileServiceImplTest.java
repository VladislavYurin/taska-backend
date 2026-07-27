package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.dto.AvatarDto;
import ru.taska.entity.User;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.entity.UserAvatar;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProfileMapper;
import ru.taska.repository.UserAvatarRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.impl.ProfileServiceImpl;
import ru.taska.storage.client.StorageClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileServiceImpl Unit Tests")
class ProfileServiceImplTest {
    private static final String URL = "https://storage.example.com/avatars/test-avatar.png?signature=abc";
    private static final String FILE_NAME = "test-avatar.png";
    private static final String CONTENT_TYPE = "image/png";
    private static final long SIZE_BYTES = 102400L;
    private static final String NEW_OBJECT_KEY = "new-object-key";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAvatarRepository userAvatarRepository;

    @Mock
    private StorageClient storageClient;

    private final ProfileMapper profileMapper = new ProfileMapper();

    @Mock
    private TransactionalOperator transactionalOperator;

    private ProfileServiceImpl profileServiceImpl;

    private UUID testUserId;
    private User testUser;
    private UserAvatar testAvatar;

    @BeforeEach
    void setUp() {
        profileServiceImpl = new ProfileServiceImpl(userRepository, userAvatarRepository, storageClient, profileMapper, transactionalOperator);
        testUserId = UUID.randomUUID();

        testUser = User.builder()
                .id(testUserId)
                .email("test@example.com")
                .login("testuser")
                .status(UserStatus.ACTIVE)
                .build();

        testAvatar = new UserAvatar();
        testAvatar.setId(UUID.randomUUID());
        testAvatar.setUserId(testUserId);
        testAvatar.setObjectKey(UUID.randomUUID().toString());
        testAvatar.setFileName(FILE_NAME);
        testAvatar.setContentType(CONTENT_TYPE);
        testAvatar.setSizeBytes(SIZE_BYTES);
    }

    @Nested
    @DisplayName("getAvatarDownloadUrl Tests")
    class GetAvatarDownloadUrlTests {

        @Test
        @DisplayName("Should return URL when avatar is found in repository")
        void shouldReturnUrlWhenAvatarFound() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(storageClient.createPresignedDownloadUrl(testAvatar.getObjectKey()))
                    .thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.getAvatarDownloadUrl(testUserId))
                    .expectNext(URL)
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository).findByUserId(testUserId);
            Mockito.verify(storageClient).createPresignedDownloadUrl(testAvatar.getObjectKey());
        }

        @Test
        @DisplayName("Should throw NOT_FOUND when user does not exist")
        void shouldThrowNotFoundWhenUserDoesNotExist() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.getAvatarDownloadUrl(testUserId))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).findByUserId(testUserId);
        }

        @Test
        @DisplayName("Should return empty when avatar is not found")
        void shouldReturnEmptyWhenAvatarNotFound() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.getAvatarDownloadUrl(testUserId))
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository).findByUserId(testUserId);
            Mockito.verify(storageClient, Mockito.never()).createPresignedDownloadUrl(Mockito.any());
        }
    }

    @Nested
    @DisplayName("getUserProfile Tests")
    class GetUserProfileTests {

        @Test
        @DisplayName("Should throw NOT_FOUND when user does not exist")
        void shouldThrowNotFoundWhenUserDoesNotExist() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.getUserProfile(testUserId))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).findByUserId(testUserId);
        }

        @Test
        @DisplayName("Should return UserProfileDto with null avatar when user has no avatar")
        void shouldReturnProfileWithNullAvatarWhenNoAvatar() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.getUserProfile(testUserId))
                    .expectNextMatches(profile -> profile.getAvatar() == null)
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository).findByUserId(testUserId);
            Mockito.verify(storageClient, Mockito.never()).createPresignedDownloadUrl(Mockito.any());
        }

        @Test
        @DisplayName("Should return UserProfileDto with AvatarDto when user has avatar")
        void shouldReturnProfileWithAvatarWhenAvatarExists() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(storageClient.createPresignedDownloadUrl(testAvatar.getObjectKey()))
                    .thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.getUserProfile(testUserId))
                    .expectNextMatches(profile -> {
                        AvatarDto avatar = profile.getAvatar();
                        return avatar != null &&
                                avatar.getDownloadUrl().equals(URL) &&
                                avatar.getFileName().equals(FILE_NAME) &&
                                avatar.getUserId().equals(testUserId);
                    })
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository).findByUserId(testUserId);
            Mockito.verify(storageClient).createPresignedDownloadUrl(testAvatar.getObjectKey());
        }
    }

    @Nested
    @DisplayName("createAvatarUploadUrl Tests")
    class CreateAvatarUploadUrlTests {

        @Test
        @DisplayName("Should throw NOT_FOUND when user does not exist")
        void shouldThrowNotFoundWhenUserDoesNotExist() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.createAvatarUploadUrl(testUserId, CONTENT_TYPE, SIZE_BYTES))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(storageClient, Mockito.never()).createPresignedUploadUrl(Mockito.any(), Mockito.anyLong());
        }

        @Test
        @DisplayName("Should delegate to storageClient and return PresignedUploadResult")
        void shouldReturnPresignedUploadResult() {
            // Given
            PresignedUploadResult expectedResult = new PresignedUploadResult("object-key-123", URL);

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.createPresignedUploadUrl(CONTENT_TYPE, SIZE_BYTES))
                    .thenReturn(Mono.just(expectedResult));

            // When & Then
            StepVerifier.create(profileServiceImpl.createAvatarUploadUrl(testUserId, CONTENT_TYPE, SIZE_BYTES))
                    .expectNext(expectedResult)
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(storageClient).createPresignedUploadUrl(CONTENT_TYPE, SIZE_BYTES);
        }
    }

    @Nested
    @DisplayName("confirmAvatarUpload Tests")
    class ConfirmAvatarUploadTests {

        private UserAvatar savedAvatar;

        @SuppressWarnings("unchecked")
        @BeforeEach
        void setUpConfirmAvatar() {
            Mockito.lenient().when(transactionalOperator.transactional(Mockito.any(Mono.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.lenient().when(storageClient.objectExists(NEW_OBJECT_KEY))
                    .thenReturn(Mono.just(true));

            savedAvatar = new UserAvatar();
            savedAvatar.setId(UUID.randomUUID());
            savedAvatar.setUserId(testUserId);
            savedAvatar.setObjectKey(NEW_OBJECT_KEY);
            savedAvatar.setFileName(FILE_NAME);
            savedAvatar.setContentType(CONTENT_TYPE);
            savedAvatar.setSizeBytes(SIZE_BYTES);
        }

        @Test
        @DisplayName("Should throw NOT_FOUND when user does not exist")
        void shouldThrowNotFoundWhenUserDoesNotExist() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(storageClient, Mockito.never()).validateObjectSizeAndDeleteIfTooLargeAndReturnETag(Mockito.any());
        }

        @Test
        @DisplayName("Should save new avatar and not call deleteObject when no old avatar exists")
        void shouldSaveNewAvatarWhenNoOldAvatar() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateObjectSizeAndDeleteIfTooLargeAndReturnETag(NEW_OBJECT_KEY)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                    .expectNextMatches(avatarDto ->
                            avatarDto.getObjectKey().equals(NEW_OBJECT_KEY) &&
                                    avatarDto.getDownloadUrl().equals(URL) &&
                                    avatarDto.getUserId().equals(testUserId))
                    .verifyComplete();

            Mockito.verify(storageClient, Mockito.never()).deleteObject(Mockito.any());
        }

        @Test
        @DisplayName("Should delete old avatar from DB and S3, save new one and return AvatarDto")
        void shouldReplaceOldAvatar() {
            // Given
            String oldObjectKey = testAvatar.getObjectKey();

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateObjectSizeAndDeleteIfTooLargeAndReturnETag(NEW_OBJECT_KEY)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.deleteObject(oldObjectKey)).thenReturn(Mono.empty());
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                    .expectNextMatches(avatarDto ->
                            avatarDto.getObjectKey().equals(NEW_OBJECT_KEY) &&
                                    avatarDto.getDownloadUrl().equals(URL))
                    .verifyComplete();

            Mockito.verify(userAvatarRepository).delete(testAvatar);
            Mockito.verify(storageClient).deleteObject(oldObjectKey);
        }

        @Test
        @DisplayName("Should complete successfully even when deleteObject for old avatar fails")
        void shouldCompleteWhenDeleteOldAvatarFails() {
            // Given
            String oldObjectKey = testAvatar.getObjectKey();

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateObjectSizeAndDeleteIfTooLargeAndReturnETag(NEW_OBJECT_KEY)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.deleteObject(oldObjectKey)).thenReturn(Mono.error(new RuntimeException("S3 delete failed")));
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                    .expectNextMatches(avatarDto ->
                            avatarDto.getObjectKey().equals(NEW_OBJECT_KEY) &&
                                    avatarDto.getDownloadUrl().equals(URL))
                    .verifyComplete();

            Mockito.verify(storageClient).deleteObject(oldObjectKey);
        }

        @Test
        @DisplayName("Should propagate error when validateObjectSizeAndDeleteIfTooLarge fails")
        void shouldPropagateValidationError() {
            // Given
            DomainException validationError = new DomainException(DomainStatus.OUT_OF_RANGE, "File too large");

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateObjectSizeAndDeleteIfTooLargeAndReturnETag(NEW_OBJECT_KEY))
                    .thenReturn(Mono.error(validationError));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE, SIZE_BYTES))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.OUT_OF_RANGE
                    )
                    .verify();

            Mockito.verify(userAvatarRepository, Mockito.never()).findByUserId(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).save(Mockito.any());
        }
    }

    @Nested
    @DisplayName("deleteMyAvatar Tests")
    class DeleteMyAvatarTests {

        @Test
        @DisplayName("Should throw NOT_FOUND when user does not exist")
        void shouldThrowNotFoundWhenUserDoesNotExist() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.deleteMyAvatar(testUserId))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).findByUserId(testUserId);
        }

        @Test
        @DisplayName("Should complete successfully when avatar does not exist (idempotent)")
        void shouldCompleteSuccessfullyWhenNoAvatar() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.deleteMyAvatar(testUserId))
                    .verifyComplete();

            Mockito.verify(userAvatarRepository).findByUserId(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).delete(Mockito.any());
            Mockito.verify(storageClient, Mockito.never()).deleteObject(Mockito.any());
        }

        @Test
        @DisplayName("Should delete avatar from DB and S3 when avatar exists")
        void shouldDeleteAvatarFromDbAndStorage() {
            // Given
            String objectKey = testAvatar.getObjectKey();

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(storageClient.deleteObject(objectKey)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileServiceImpl.deleteMyAvatar(testUserId))
                    .verifyComplete();

            Mockito.verify(userAvatarRepository).delete(testAvatar);
            Mockito.verify(storageClient).deleteObject(objectKey);
        }

        @Test
        @DisplayName("Should complete successfully when S3 deleteObject fails (onErrorComplete)")
        void shouldCompleteWhenS3DeleteFails() {
            // Given
            String objectKey = testAvatar.getObjectKey();

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(storageClient.deleteObject(objectKey)).thenReturn(Mono.error(new RuntimeException("S3 delete failed")));

            // When & Then
            StepVerifier.create(profileServiceImpl.deleteMyAvatar(testUserId))
                    .verifyComplete();

            Mockito.verify(userAvatarRepository).delete(testAvatar);
            Mockito.verify(storageClient).deleteObject(objectKey);
        }
    }
}
