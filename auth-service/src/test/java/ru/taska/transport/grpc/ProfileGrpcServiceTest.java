package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadRequest;
import ru.taska.api.auth.profile.v1.ConfirmAvatarUploadRequestBody;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlRequest;
import ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlRequestBody;
import ru.taska.api.auth.profile.v1.DeleteMyAvatarRequest;
import ru.taska.api.auth.profile.v1.DeleteMyAvatarRequestBody;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlRequest;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlRequestBody;
import ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequest;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsRequestBody;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.GetUserProfileRequest;
import ru.taska.api.auth.profile.v1.GetUserProfileRequestBody;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.api.common.v1.Header;
import ru.taska.dto.AvatarDto;
import ru.taska.dto.UserDetailsDto;
import ru.taska.dto.UserProfileDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProfileMapper;
import ru.taska.service.ProfileService;
import ru.taska.storage.dto.PresignedUploadResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Тесты для ProfileGrpcService.
 * Проверяют валидацию запросов и вызов ProfileService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileGrpcService Unit Tests")
class ProfileGrpcServiceTest {

    @Mock
    private ProfileService profileService;

    @Mock
    private ProfileMapper profileMapper;

    @InjectMocks
    private ProfileGrpcService profileGrpcService;

    private static final String REQUEST_ID = "test-request-id";
    private static final String NODE_ID = "test-node-id";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USER_ID_STR = USER_ID.toString();

    private Header validHeader;

    @BeforeEach
    void setUp() {
        validHeader = Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }

    // ==================== CONFIRM AVATAR UPLOAD TESTS ====================

    @Nested
    @DisplayName("ConfirmAvatarUpload Tests")
    class ConfirmAvatarUploadTests {

        private ConfirmAvatarUploadRequest validRequest;
        private AvatarDto avatarDto;

        @BeforeEach
        void setUp() {
            validRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("avatar.png")
                            .setContentType("image/png")
                            .build())
                    .build();

            avatarDto = AvatarDto.builder()
                    .id(UUID.randomUUID())
                    .userId(USER_ID)
                    .objectKey("avatars/test-key.png")
                    .fileName("avatar.png")
                    .contentType("image/png")
                    .sizeBytes(1024L)
                    .createdAt(Instant.now())
                    .downloadUrl("https://example.com/avatar.png")
                    .build();
        }

        @Test
        @DisplayName("Should successfully confirm avatar upload")
        void shouldSuccessfullyConfirmAvatarUpload() {
            // Given
            Mockito.when(profileService.confirmAvatarUpload(
                    ArgumentMatchers.eq(USER_ID),
                    ArgumentMatchers.eq("avatars/test-key.png"),
                    ArgumentMatchers.eq("avatar.png"),
                    ArgumentMatchers.eq("image/png")
            )).thenReturn(Mono.just(avatarDto));

            var expectedResponse = ru.taska.api.auth.profile.v1.ConfirmAvatarUploadResponse.getDefaultInstance();
            Mockito.when(profileMapper.toConfirmAvatarUploadResponse(avatarDto))
                    .thenReturn(expectedResponse);

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(validRequest)))
                    .expectNext(expectedResponse)
                    .verifyComplete();

            Mockito.verify(profileService).confirmAvatarUpload(
                    USER_ID, "avatars/test-key.png", "avatar.png", "image/png");
        }

        @Test
        @DisplayName("Should handle DomainException from ProfileService")
        void shouldHandleDomainException() {
            // Given
            Mockito.when(profileService.confirmAvatarUpload(
                    ArgumentMatchers.any(UUID.class),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString()
            )).thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Avatar not found")));

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when actor_user_id is blank")
        void shouldReturnInvalidArgumentWhenActorUserIdIsBlank() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId("")
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("avatar.png")
                            .setContentType("image/png")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when actor_user_id is invalid UUID")
        void shouldReturnInvalidArgumentWhenActorUserIdIsInvalidUuid() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId("not-a-uuid")
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("avatar.png")
                            .setContentType("image/png")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when object_key is blank")
        void shouldReturnInvalidArgumentWhenObjectKeyIsBlank() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setObjectKey("")
                            .setFileName("avatar.png")
                            .setContentType("image/png")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when file_name is blank")
        void shouldReturnInvalidArgumentWhenFileNameIsBlank() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("")
                            .setContentType("image/png")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when content_type is blank")
        void shouldReturnInvalidArgumentWhenContentTypeIsBlank() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("avatar.png")
                            .setContentType("")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when requestId is blank")
        void shouldReturnInvalidArgumentWhenRequestIdIsBlank() {
            // Given
            ConfirmAvatarUploadRequest invalidRequest = ConfirmAvatarUploadRequest.newBuilder()
                    .setHeader(Header.newBuilder()
                            .setRequestId("")
                            .setNodeId(NODE_ID)
                            .build())
                    .setBody(ConfirmAvatarUploadRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setObjectKey("avatars/test-key.png")
                            .setFileName("avatar.png")
                            .setContentType("image/png")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.confirmAvatarUpload(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).confirmAvatarUpload(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        }
    }

    // ==================== CREATE AVATAR UPLOAD URL TESTS ====================

    @Nested
    @DisplayName("CreateAvatarUploadUrl Tests")
    class CreateAvatarUploadUrlTests {

        private CreateAvatarUploadUrlRequest validRequest;
        private PresignedUploadResult uploadResult;

        @BeforeEach
        void setUp() {
            validRequest = CreateAvatarUploadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(CreateAvatarUploadUrlRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setContentType("image/png")
                            .setSizeBytes(2048L)
                            .build())
                    .build();

            uploadResult = new PresignedUploadResult("avatars/generated-key.png", "https://s3.example.com/upload-url");
        }

        @Test
        @DisplayName("Should successfully create avatar upload URL")
        void shouldSuccessfullyCreateAvatarUploadUrl() {
            // Given
            Mockito.when(profileService.createAvatarUploadUrl(
                    ArgumentMatchers.eq(USER_ID),
                    ArgumentMatchers.eq("image/png"),
                    ArgumentMatchers.eq(2048L)
            )).thenReturn(Mono.just(uploadResult));

            var expectedResponse = ru.taska.api.auth.profile.v1.CreateAvatarUploadUrlResponse.getDefaultInstance();
            Mockito.when(profileMapper.toCreateAvatarUploadUrlResponse(uploadResult))
                    .thenReturn(expectedResponse);

            // When & Then
            StepVerifier.create(profileGrpcService.createAvatarUploadUrl(Mono.just(validRequest)))
                    .expectNext(expectedResponse)
                    .verifyComplete();

            Mockito.verify(profileService).createAvatarUploadUrl(USER_ID, "image/png", 2048L);
        }

        @Test
        @DisplayName("Should handle DomainException from ProfileService")
        void shouldHandleDomainException() {
            // Given
            Mockito.when(profileService.createAvatarUploadUrl(
                    ArgumentMatchers.any(UUID.class),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyLong()
            )).thenReturn(Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid content type")));

            // When & Then
            StepVerifier.create(profileGrpcService.createAvatarUploadUrl(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when actor_user_id is blank")
        void shouldReturnInvalidArgumentWhenActorUserIdIsBlank() {
            // Given
            CreateAvatarUploadUrlRequest invalidRequest = CreateAvatarUploadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(CreateAvatarUploadUrlRequestBody.newBuilder()
                            .setActorUserId("")
                            .setContentType("image/png")
                            .setSizeBytes(2048L)
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.createAvatarUploadUrl(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).createAvatarUploadUrl(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when content_type is blank")
        void shouldReturnInvalidArgumentWhenContentTypeIsBlank() {
            // Given
            CreateAvatarUploadUrlRequest invalidRequest = CreateAvatarUploadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(CreateAvatarUploadUrlRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setContentType("")
                            .setSizeBytes(2048L)
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.createAvatarUploadUrl(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).createAvatarUploadUrl(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when size_bytes is zero")
        void shouldReturnInvalidArgumentWhenSizeBytesIsZero() {
            // Given
            CreateAvatarUploadUrlRequest invalidRequest = CreateAvatarUploadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(CreateAvatarUploadUrlRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .setContentType("image/png")
                            .setSizeBytes(0L)
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.createAvatarUploadUrl(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).createAvatarUploadUrl(
                    ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyLong());
        }
    }

    // ==================== GET AVATAR DOWNLOAD URL TESTS ====================

    @Nested
    @DisplayName("GetAvatarDownloadUrl Tests")
    class GetAvatarDownloadUrlTests {

        private GetAvatarDownloadUrlRequest validRequest;

        @BeforeEach
        void setUp() {
            validRequest = GetAvatarDownloadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetAvatarDownloadUrlRequestBody.newBuilder()
                            .setUserId(USER_ID_STR)
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Should successfully get avatar download URL")
        void shouldSuccessfullyGetAvatarDownloadUrl() {
            // Given
            String downloadUrl = "https://s3.example.com/download-url";
            Mockito.when(profileService.getAvatarDownloadUrl(ArgumentMatchers.eq(USER_ID)))
                    .thenReturn(Mono.just(downloadUrl));

            var expectedResponse = ru.taska.api.auth.profile.v1.GetAvatarDownloadUrlResponse.newBuilder()
                    .setUrl(downloadUrl)
                    .build();
            Mockito.when(profileMapper.toGetAvatarDownloadUrlResponse(downloadUrl))
                    .thenReturn(expectedResponse);

            // When & Then
            StepVerifier.create(profileGrpcService.getAvatarDownloadUrl(Mono.just(validRequest)))
                    .expectNextMatches(response ->
                            response.getUrl().equals(downloadUrl)
                    )
                    .verifyComplete();

            Mockito.verify(profileService).getAvatarDownloadUrl(USER_ID);
        }

        @Test
        @DisplayName("Should return default instance when avatar not found (empty Mono)")
        void shouldReturnDefaultInstanceWhenAvatarNotFound() {
            // Given
            Mockito.when(profileService.getAvatarDownloadUrl(ArgumentMatchers.eq(USER_ID)))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileGrpcService.getAvatarDownloadUrl(Mono.just(validRequest)))
                    .expectNext(GetAvatarDownloadUrlResponse.getDefaultInstance())
                    .verifyComplete();

            Mockito.verify(profileService).getAvatarDownloadUrl(USER_ID);
        }

        @Test
        @DisplayName("Should handle DomainException from ProfileService")
        void shouldHandleDomainException() {
            // Given
            Mockito.when(profileService.getAvatarDownloadUrl(ArgumentMatchers.any(UUID.class)))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")));

            // When & Then
            StepVerifier.create(profileGrpcService.getAvatarDownloadUrl(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when user_id is blank")
        void shouldReturnInvalidArgumentWhenUserIdIsBlank() {
            // Given
            GetAvatarDownloadUrlRequest invalidRequest = GetAvatarDownloadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetAvatarDownloadUrlRequestBody.newBuilder()
                            .setUserId("")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.getAvatarDownloadUrl(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).getAvatarDownloadUrl(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when user_id is invalid UUID")
        void shouldReturnInvalidArgumentWhenUserIdIsInvalidUuid() {
            // Given
            GetAvatarDownloadUrlRequest invalidRequest = GetAvatarDownloadUrlRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetAvatarDownloadUrlRequestBody.newBuilder()
                            .setUserId("not-a-uuid")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.getAvatarDownloadUrl(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).getAvatarDownloadUrl(ArgumentMatchers.any());
        }
    }

    // ==================== DELETE MY AVATAR TESTS ====================

    @Nested
    @DisplayName("DeleteMyAvatar Tests")
    class DeleteMyAvatarTests {

        private DeleteMyAvatarRequest validRequest;

        @BeforeEach
        void setUp() {
            validRequest = DeleteMyAvatarRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(DeleteMyAvatarRequestBody.newBuilder()
                            .setActorUserId(USER_ID_STR)
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Should successfully delete avatar")
        void shouldSuccessfullyDeleteAvatar() {
            // Given
            Mockito.when(profileService.deleteMyAvatar(ArgumentMatchers.eq(USER_ID)))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(profileGrpcService.deleteMyAvatar(Mono.just(validRequest)))
                    .expectNext(Empty.getDefaultInstance())
                    .verifyComplete();

            Mockito.verify(profileService).deleteMyAvatar(USER_ID);
        }

        @Test
        @DisplayName("Should handle DomainException from ProfileService")
        void shouldHandleDomainException() {
            // Given
            Mockito.when(profileService.deleteMyAvatar(ArgumentMatchers.any(UUID.class)))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Avatar not found")));

            // When & Then
            StepVerifier.create(profileGrpcService.deleteMyAvatar(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when actor_user_id is blank")
        void shouldReturnInvalidArgumentWhenActorUserIdIsBlank() {
            // Given
            DeleteMyAvatarRequest invalidRequest = DeleteMyAvatarRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(DeleteMyAvatarRequestBody.newBuilder()
                            .setActorUserId("")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.deleteMyAvatar(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).deleteMyAvatar(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when actor_user_id is invalid UUID")
        void shouldReturnInvalidArgumentWhenActorUserIdIsInvalidUuid() {
            // Given
            DeleteMyAvatarRequest invalidRequest = DeleteMyAvatarRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(DeleteMyAvatarRequestBody.newBuilder()
                            .setActorUserId("invalid-uuid")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.deleteMyAvatar(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).deleteMyAvatar(ArgumentMatchers.any());
        }
    }

    // ==================== GET USER PROFILE TESTS ====================

    @Nested
    @DisplayName("GetUserProfile Tests")
    class GetUserProfileTests {

        private GetUserProfileRequest validRequest;
        private UserProfileDto userProfileDto;

        @BeforeEach
        void setUp() {
            validRequest = GetUserProfileRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetUserProfileRequestBody.newBuilder()
                            .setUserId(USER_ID_STR)
                            .build())
                    .build();

            userProfileDto = UserProfileDto.builder()
                    .avatar(AvatarDto.builder()
                            .id(UUID.randomUUID())
                            .userId(USER_ID)
                            .objectKey("avatars/key.png")
                            .fileName("avatar.png")
                            .contentType("image/png")
                            .sizeBytes(512L)
                            .createdAt(Instant.now())
                            .downloadUrl("https://example.com/avatar.png")
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Should successfully get user profile")
        void shouldSuccessfullyGetUserProfile() {
            // Given
            Mockito.when(profileService.getUserProfile(ArgumentMatchers.eq(USER_ID)))
                    .thenReturn(Mono.just(userProfileDto));

            var expectedResponse = ru.taska.api.auth.profile.v1.GetUserProfileResponse.getDefaultInstance();
            Mockito.when(profileMapper.toProto(userProfileDto))
                    .thenReturn(expectedResponse);

            // When & Then
            StepVerifier.create(profileGrpcService.getUserProfile(Mono.just(validRequest)))
                    .expectNext(expectedResponse)
                    .verifyComplete();

            Mockito.verify(profileService).getUserProfile(USER_ID);
        }

        @Test
        @DisplayName("Should handle DomainException from ProfileService")
        void shouldHandleDomainException() {
            // Given
            Mockito.when(profileService.getUserProfile(ArgumentMatchers.any(UUID.class)))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.NOT_FOUND, "User not found")));

            // When & Then
            StepVerifier.create(profileGrpcService.getUserProfile(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should propagate RuntimeException")
        void shouldPropagateRuntimeException() {
            // Given
            Mockito.when(profileService.getUserProfile(ArgumentMatchers.any(UUID.class)))
                    .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

            // When & Then
            StepVerifier.create(profileGrpcService.getUserProfile(Mono.just(validRequest)))
                    .expectErrorMatches(error ->
                            error instanceof RuntimeException &&
                                    error.getMessage().equals("Unexpected error")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when user_id is blank")
        void shouldReturnInvalidArgumentWhenUserIdIsBlank() {
            // Given
            GetUserProfileRequest invalidRequest = GetUserProfileRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetUserProfileRequestBody.newBuilder()
                            .setUserId("")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.getUserProfile(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).getUserProfile(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when user_id is invalid UUID")
        void shouldReturnInvalidArgumentWhenUserIdIsInvalidUuid() {
            // Given
            GetUserProfileRequest invalidRequest = GetUserProfileRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(GetUserProfileRequestBody.newBuilder()
                            .setUserId("bad-uuid")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(profileGrpcService.getUserProfile(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(profileService, Mockito.never()).getUserProfile(ArgumentMatchers.any());
        }
    }


    @Nested
    @DisplayName("GetUserDetailsByIds Tests")
    class GetUserDetailsByIdsTests {
        public final String email1 = "john@example.com";
        public final String email2 = "jane@example.com";
        public final String displayName1 = "John Doe";
        public final String displayName2 = "Jane Doe";
        private final UUID userId1 = UUID.randomUUID();
        private final UUID userId2 = UUID.randomUUID();

        private UserDetailsDto userDetailsDto1;
        private UserDetailsDto userDetailsDto2;
        private UserDetails userDetailsProto1;
        private UserDetails userDetailsProto2;
        private AvatarDto avatarDto1;
        private AvatarDto avatarDto2;
        private AvatarResponse avatarResponse1;
        private AvatarResponse avatarResponse2;

        @BeforeEach
        void setUp() {
            UUID avatarId1 = UUID.randomUUID();
            UUID avatarId2 = UUID.randomUUID();

            avatarDto1 = createAvatarDto(avatarId1, userId1, "user1");
            avatarDto2 = createAvatarDto(avatarId2, userId2, "user2");

            userDetailsDto1 = UserDetailsDto.builder()
                    .userId(userId1)
                    .displayName(displayName1)
                    .email(email1)
                    .avatar(avatarDto1)
                    .build();
            userDetailsDto2 = UserDetailsDto.builder()
                    .userId(userId2)
                    .displayName(displayName2)
                    .email(email2)
                    .avatar(avatarDto2)
                    .build();

            avatarResponse1 = createAvatarResponse(avatarDto1);
            avatarResponse2 = createAvatarResponse(avatarDto2);

            userDetailsProto1 = UserDetails.newBuilder()
                    .setUserId(userId1.toString())
                    .setDisplayName(displayName1)
                    .setEmail(email1)
                    .setAvatar(avatarResponse1)
                    .build();

            userDetailsProto2 = UserDetails.newBuilder()
                    .setUserId(userId2.toString())
                    .setDisplayName(displayName2)
                    .setEmail(email2)
                    .setAvatar(avatarResponse2)
                    .build();
        }

        @Test
        @DisplayName("Should successfully return GetUserDetailsByIdsResponse when request is valid")
        void getUserDetailsByIds_whenRequestIsValid_thenReturnsSuccessfulResponse() {
            List<UUID> expectedUserIds = List.of(userId1, userId2);
            GetUserDetailsByIdsRequest validRequest = buildRequest(List.of(userId1.toString(), userId2.toString()));

            Mockito.when(profileService.getUserDetailsByIds(expectedUserIds))
                    .thenReturn(Flux.just(userDetailsDto1, userDetailsDto2));

            Mockito.when(profileMapper.toProto(userDetailsDto1))
                    .thenReturn(userDetailsProto1);
            Mockito.when(profileMapper.toProto(userDetailsDto2))
                    .thenReturn(userDetailsProto2);

            Mono<GetUserDetailsByIdsResponse> responseMono =
                    profileGrpcService.getUserDetailsByIds(Mono.just(validRequest));

            StepVerifier.create(responseMono)
                    .assertNext(response -> {
                        Assertions.assertEquals(2, response.getUserDetailsCount());
                        Assertions.assertEquals(userId1.toString(), response.getUserDetails(0).getUserId());
                        Assertions.assertEquals(userId2.toString(), response.getUserDetails(1).getUserId());
                    })
                    .verifyComplete();

            Mockito.verify(profileService, Mockito.times(1))
                    .getUserDetailsByIds(expectedUserIds);
            Mockito.verify(profileMapper, Mockito.times(1))
                    .toProto(userDetailsDto1);
            Mockito.verify(profileMapper, Mockito.times(1))
                    .toProto(userDetailsDto2);
        }

        @Test
        @DisplayName("Should propagate error when profileService throws an exception")
        void getUserDetailsByIds_whenServiceFails_thenPropagatesError() {
            List<UUID> expectedUserIds = List.of(userId1);
            GetUserDetailsByIdsRequest validRequest = buildRequest(List.of(userId1.toString()));

            DomainException domainException = new DomainException(DomainStatus.NOT_FOUND, "Users not found");

            Mockito.when(profileService.getUserDetailsByIds(expectedUserIds))
                    .thenReturn(Flux.error(domainException));

            Mono<GetUserDetailsByIdsResponse> responseMono =
                    profileGrpcService.getUserDetailsByIds(Mono.just(validRequest));

            StepVerifier.create(responseMono)
                    .expectErrorSatisfies(throwable -> Assertions.assertEquals(domainException, throwable))
                    .verify();

            Mockito.verify(profileService, Mockito.times(1))
                    .getUserDetailsByIds(expectedUserIds);
            Mockito.verifyNoInteractions(profileMapper);
        }

        @Test
        @DisplayName("Should return validation error when userIds contains invalid UUID format")
        void getUserDetailsByIds_whenUserIdIsInvalidUuid_thenReturnsValidationError() {
            GetUserDetailsByIdsRequest invalidRequest = buildRequest(List.of("invalid-uuid-format"));

            Mono<GetUserDetailsByIdsResponse> responseMono =
                    profileGrpcService.getUserDetailsByIds(Mono.just(invalidRequest));

            StepVerifier.create(responseMono)
                    .expectError()
                    .verify();

            Mockito.verifyNoInteractions(profileService);
            Mockito.verifyNoInteractions(profileMapper);
        }

        private GetUserDetailsByIdsRequest buildRequest(List<String> userIds) {
            GetUserDetailsByIdsRequestBody body = GetUserDetailsByIdsRequestBody.newBuilder()
                    .addAllUserIds(userIds)
                    .build();
            return GetUserDetailsByIdsRequest.newBuilder()
                    .setHeader(validHeader)
                    .setBody(body)
                    .build();
        }

        private AvatarDto createAvatarDto(UUID avatarId, UUID userId, String name) {
            return AvatarDto.builder()
                    .id(avatarId)
                    .userId(userId)
                    .objectKey("avatars/" + name + ".jpg")
                    .fileName(name + ".jpg")
                    .contentType("image/jpeg")
                    .sizeBytes(2048L)
                    .createdAt(Instant.now())
                    .downloadUrl("https://storage.example.com/avatars/" + name + ".jpg")
                    .build();
        }

        private AvatarResponse createAvatarResponse(AvatarDto dto) {
            return AvatarResponse.newBuilder()
                    .setId(dto.getId().toString())
                    .setUserId(dto.getUserId().toString())
                    .setObjectKey(dto.getObjectKey())
                    .setFileName(dto.getFileName())
                    .setContentType(dto.getContentType())
                    .setSizeBytes(dto.getSizeBytes())
                    .setDownloadUrl(dto.getDownloadUrl())
                    .build();
        }
    }
}
