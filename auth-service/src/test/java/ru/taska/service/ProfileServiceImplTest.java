package ru.taska.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.MembershipValidation;
import ru.taska.api.b2b.v1.MembershipValidationBatch;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.entity.User;
import ru.taska.entity.UserAvatar;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProfileMapper;
import ru.taska.repository.UserAvatarRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.security.trust.GrpcTenantServiceClient;
import ru.taska.service.impl.ProfileServiceImpl;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.storage.dto.StoredObjectMetadata;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileServiceImpl Unit Tests")
class ProfileServiceImplTest {
    private static final String URL = "https://storage.example.com/avatars/test-avatar.png?signature=abc";
    private static final String FILE_NAME = "test-avatar.png";
    private static final String CONTENT_TYPE = "image/png";
    private static final long SIZE_BYTES = 102400L;
    private String NEW_OBJECT_KEY;
    public static final String TEST_USER_EMAIL = "test@example.com";

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
    private UUID testUserId2;
    private UUID testUserId3;

    private User testUser;
    private UserAvatar testAvatar;

    private UserDetailsDto testUserDetailsDto;
    private UserDetailsDto testUserDetailsDto2;

    @Mock
    private DirectoryMembershipAccess membership;

    @Mock
    private DirectoryAccess access;

    @Mock
    private ru.taska.security.trust.GrpcTenantServiceClient tenantClient;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("grpcTenantServiceClient", tenantClient);
        profileServiceImpl = new ProfileServiceImpl(
                userRepository,
                userAvatarRepository,
                storageClient,
                profileMapper,
                transactionalOperator,
                membership,
                access,
                beans.getBeanProvider(GrpcTenantServiceClient.class)
        );
        testUserId = UUID.randomUUID();
        NEW_OBJECT_KEY = "avatars/" + testUserId + "/" + UUID.randomUUID();
        testUserId2 = UUID.randomUUID();
        testUserId3 = UUID.randomUUID();

        testUser = User.builder()
                .id(testUserId)
                .email(TEST_USER_EMAIL)
                .status(UserStatus.ACTIVE)
                .build();

        testAvatar = new UserAvatar();
        testAvatar.setId(UUID.randomUUID());
        testAvatar.setUserId(testUserId);
        testAvatar.setObjectKey(UUID.randomUUID().toString());
        testAvatar.setFileName(FILE_NAME);
        testAvatar.setContentType(CONTENT_TYPE);
        testAvatar.setSizeBytes(SIZE_BYTES);

        testUserDetailsDto = UserDetailsDto.builder()
                .userId(testUserId)
                .displayName("John Doe")
                .email(TEST_USER_EMAIL)
                .build();

        testUserDetailsDto2 = UserDetailsDto.builder()
                .userId(testUserId2)
                .displayName("Jane Doe")
                .build();
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
            PresignedUploadResult expectedResult = new PresignedUploadResult("object-key-123", URL,300);

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.createPresignedUploadUrl(Mockito.eq(CONTENT_TYPE), Mockito.eq(SIZE_BYTES), Mockito.startsWith("avatars/" + testUserId + "/")))
                    .thenReturn(Mono.just(expectedResult));

            // When & Then
            StepVerifier.create(profileServiceImpl.createAvatarUploadUrl(testUserId, CONTENT_TYPE, SIZE_BYTES))
                    .expectNext(expectedResult)
                    .verifyComplete();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(storageClient).createPresignedUploadUrl(Mockito.eq(CONTENT_TYPE), Mockito.eq(SIZE_BYTES), Mockito.startsWith("avatars/" + testUserId + "/"));
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
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();

            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(storageClient, Mockito.never()).validateAndGetUploadedObjectMetadata(Mockito.any());
        }

        @Test
        @DisplayName("Should save new avatar and not call deleteObject when no old avatar exists")
        void shouldSaveNewAvatarWhenNoOldAvatar() {
            // Given
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(NEW_OBJECT_KEY)).thenReturn(Mono.just(new StoredObjectMetadata("checksum", SIZE_BYTES)));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
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
            Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(NEW_OBJECT_KEY)).thenReturn(Mono.just(new StoredObjectMetadata("checksum", SIZE_BYTES)));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.deleteObject(oldObjectKey)).thenReturn(Mono.empty());
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
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
            Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(NEW_OBJECT_KEY)).thenReturn(Mono.just(new StoredObjectMetadata("checksum", SIZE_BYTES)));
            Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.just(testAvatar));
            Mockito.when(userAvatarRepository.delete(testAvatar)).thenReturn(Mono.empty());
            Mockito.when(userAvatarRepository.save(Mockito.any(UserAvatar.class))).thenReturn(Mono.just(savedAvatar));
            Mockito.when(storageClient.deleteObject(oldObjectKey)).thenReturn(Mono.error(new RuntimeException("S3 delete failed")));
            Mockito.when(storageClient.createPresignedDownloadUrl(NEW_OBJECT_KEY)).thenReturn(Mono.just(URL));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                    .expectNextMatches(avatarDto ->
                            avatarDto.getObjectKey().equals(NEW_OBJECT_KEY) &&
                                    avatarDto.getDownloadUrl().equals(URL))
                    .verifyComplete();

            Mockito.verify(storageClient).deleteObject(oldObjectKey);
        }

        @Test
        @DisplayName("Should propagate error when validateAndGetUploadedObjectMetadata fails")
        void shouldPropagateValidationError() {
            // Given
            DomainException validationError = new DomainException(DomainStatus.OUT_OF_RANGE, "File too large");

            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(storageClient.validateAndGetUploadedObjectMetadata(NEW_OBJECT_KEY))
                    .thenReturn(Mono.error(validationError));

            // When & Then
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, NEW_OBJECT_KEY, FILE_NAME, CONTENT_TYPE))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.OUT_OF_RANGE
                    )
                    .verify();

            Mockito.verify(userAvatarRepository, Mockito.never()).findByUserId(testUserId);
            Mockito.verify(userAvatarRepository, Mockito.never()).save(Mockito.any());
        }
    }

    @Test
    void foreignOrUnknownKey_isRejectedBeforeStorageAndMutation() {
        Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        Mockito.when(userAvatarRepository.findByUserId(testUserId)).thenReturn(Mono.empty());
        for (String key : java.util.List.of("avatars/" + testUserId2 + "/" + UUID.randomUUID(),
                "unknown", "avatars/" + testUserId + "/../other")) {
            StepVerifier.create(profileServiceImpl.confirmAvatarUpload(testUserId, key, FILE_NAME, CONTENT_TYPE))
                    .expectErrorMatches(error -> error instanceof DomainException domain && domain.getStatus() == DomainStatus.NOT_FOUND)
                    .verify();
        }
        Mockito.verifyNoInteractions(storageClient);
        Mockito.verify(userAvatarRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(userAvatarRepository, Mockito.never()).delete(Mockito.any());
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

    @Nested
    @DisplayName("Получение профилей пользователей в разрешённой компании")
    class UserDetailsByIdsTests {

        private final UUID tenantId = UUID.randomUUID();

        /**
         * Настраивает разрешённый доступ актора и всех целей запроса,
         * затем устанавливает тестовый tenant-контекст.
         */
        private Flux<UserDetailsDto> authorizedRequest(List<UUID> userIds) {
            var context = Mockito.mock(VerifiedTenantContext.class);

            Mockito.when(context.tenantId())
                    .thenReturn(tenantId.toString());
            Mockito.when(context.actorId())
                    .thenReturn(testUserId.toString());
            Mockito.when(context.scopes())
                    .thenReturn(Set.of("read"));

            Mockito.when(access.requireActor(testUserId, false))
                    .thenReturn(Mono.just(testUser));

            Mockito.when(membership.check(testUser, tenantId.toString()))
                    .thenReturn(Mono.empty());

            if (!userIds.isEmpty()) {
                List<UUID> distinctIds = userIds.stream().distinct().toList();

                var batch = MembershipValidationBatch.newBuilder()
                        .setTenantId(tenantId.toString());

                for (UUID userId : distinctIds) {
                    batch.addItems(
                            MembershipValidation.newBuilder()
                                    .setUserId(userId.toString())
                                    .setFound(true)
                                    .setMembership(
                                            MembershipSnapshot.newBuilder()
                                                    .setTenantId(tenantId.toString())
                                                    .setUserId(userId.toString())
                                                    .setState(
                                                            Membership.StateValue.STATE_ACTIVE
                                                    )
                                    )
                    );
                }

                Mockito.when(tenantClient.batchValidateMemberships(
                        tenantId.toString(),
                        distinctIds
                )).thenReturn(Mono.just(batch.build()));
            }

            return profileServiceImpl.getUserDetailsByIds(userIds)
                    .contextWrite(ctx ->
                            ctx.put(VerifiedTenantContext.class, context)
                    );
        }

        /**
         * Проверяет, что авторизованный пустой запрос успешно завершается
         * без batch-проверки целей, чтения профилей и обращения к storage.
         */
        @Test
        @DisplayName("Пустой список после проверки доступа возвращает пустой результат")
        void returnsEmptyForEmptyList() {
            StepVerifier.create(authorizedRequest(List.of()))
                    .verifyComplete();

            Mockito.verify(membership)
                    .check(testUser, tenantId.toString());

            Mockito.verifyNoInteractions(
                    tenantClient,
                    userRepository,
                    storageClient
            );
        }

        /**
         * Проверяет устранение повторных UUID и восстановление входного порядка,
         * даже если репозиторий вернул пользователей в обратном порядке.
         */
        @Test
        @DisplayName("Дубли удаляются, профили возвращаются в порядке входных UUID")
        void returnsProfilesInRequestedOrderWithoutDuplicates() {
            List<UUID> requestedIds =
                    List.of(testUserId, testUserId2, testUserId);

            List<UUID> distinctIds =
                    List.of(testUserId, testUserId2);

            Mockito.when(userRepository.findUsersWithAvatars(distinctIds))
                    .thenReturn(Flux.just(
                            testUserDetailsDto2,
                            testUserDetailsDto
                    ));

            StepVerifier.create(authorizedRequest(requestedIds))
                    .expectNext(testUserDetailsDto)
                    .expectNext(testUserDetailsDto2)
                    .verifyComplete();

            Mockito.verify(tenantClient).batchValidateMemberships(
                    tenantId.toString(),
                    distinctIds
            );
            Mockito.verify(userRepository)
                    .findUsersWithAvatars(distinctIds);
        }

        /**
         * Проверяет согласованное допущение: если membership разрешено,
         * но глобальный профиль отсутствует в Auth, эта запись пропускается.
         */
        @Test
        @DisplayName("Отсутствующий профиль разрешённого участника пропускается")
        void skipsMissingProfile() {
            UUID missingProfileId = UUID.randomUUID();
            List<UUID> requestedIds = List.of(
                    testUserId,
                    missingProfileId
            );

            Mockito.when(userRepository.findUsersWithAvatars(requestedIds))
                    .thenReturn(Flux.just(testUserDetailsDto));

            StepVerifier.create(authorizedRequest(requestedIds))
                    .expectNext(testUserDetailsDto)
                    .verifyComplete();

            Mockito.verify(userRepository)
                    .findUsersWithAvatars(requestedIds);
        }

        /**
         * Проверяет, что ошибка получения одного аватара не прерывает batch.
         * У проблемного пользователя удаляется аватар, но сохраняются профиль и порядок.
         */
        @Test
        @DisplayName("Ошибка storage убирает только проблемный аватар")
        void returnsProfilesWhenOneAvatarFails() {
            var first = UserDetailsDto.builder()
                    .userId(testUserId)
                    .displayName("Иван Петров")
                    .email("ivan@example.test")
                    .avatar(AvatarDto.builder()
                            .id(UUID.randomUUID())
                            .objectKey("avatars/ivan.png")
                            .build())
                    .build();

            var second = UserDetailsDto.builder()
                    .userId(testUserId2)
                    .displayName("Мария Иванова")
                    .email("maria@example.test")
                    .avatar(AvatarDto.builder()
                            .id(UUID.randomUUID())
                            .objectKey("avatars/maria.png")
                            .build())
                    .build();

            List<UUID> requestedIds = List.of(testUserId, testUserId2);

            Mockito.when(userRepository.findUsersWithAvatars(requestedIds))
                    .thenReturn(Flux.just(first, second));

            Mockito.when(storageClient.createPresignedDownloadUrl(
                    "avatars/ivan.png"
            )).thenReturn(Mono.just(URL));

            Mockito.when(storageClient.createPresignedDownloadUrl(
                    "avatars/maria.png"
            )).thenReturn(Mono.error(
                    new IllegalStateException("Storage unavailable")
            ));

            StepVerifier.create(authorizedRequest(requestedIds))
                    .assertNext(result -> {
                        Assertions.assertEquals(testUserId, result.userId());
                        Assertions.assertNotNull(result.avatar());
                        Assertions.assertEquals(
                                URL,
                                result.avatar().getDownloadUrl()
                        );
                    })
                    .assertNext(result -> {
                        Assertions.assertEquals(testUserId2, result.userId());
                        Assertions.assertEquals(
                                "Мария Иванова",
                                result.displayName()
                        );
                        Assertions.assertEquals(
                                "maria@example.test",
                                result.email()
                        );
                        Assertions.assertNull(result.avatar());
                    })
                    .verifyComplete();
        }
    }
}
