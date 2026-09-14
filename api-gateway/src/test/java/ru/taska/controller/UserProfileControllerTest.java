package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.AvatarResponseDto;
import ru.taska.domain.dto.ConfirmAvatarUploadRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAvatarUploadUrlResponseDto;
import ru.taska.domain.dto.GetAvatarDownloadUrlResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@WebFluxTest(controllers = UserProfileController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
@DisplayName("UserProfileController Unit Tests")
class UserProfileControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OBJECT_KEY = "avatars/123e4567-e89b-12d3-a456-426614174000/avatar.png";
    private static final String FILE_NAME = "avatar.png";
    private static final String CONTENT_TYPE = "image/png";
    private static final long SIZE_BYTES = 120000;
    private static final long EXPIRES_IN = 300;
    private static final String UPLOAD_URL = "https://s3.example.com/upload?X-Amz-Algorithm=...";
    private static final String DOWNLOAD_URL = "https://s3.example.com/download?X-Amz-Algorithm=...";
    private static final String AVATAR_ID = "22222222-2222-2222-2222-222222222222";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authServiceClient;

    @MockitoBean
    private GrpcUserProfileServiceClient grpcClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ==================== ТЕСТЫ createAvatarUploadUrl ====================

    @Test
    @DisplayName("createAvatarUploadUrl: должен вернуть 201 с uploadUrl, objectKey, expiresIn")
    void createAvatarUploadUrl_shouldReturn201() {
        mockAuthenticatedUser();

        CreateAvatarUploadUrlResponseDto response = new CreateAvatarUploadUrlResponseDto();
        response.setUploadUrl(UPLOAD_URL);
        response.setObjectKey(OBJECT_KEY);
        response.setExpiresIn(EXPIRES_IN);

        Mockito.when(grpcClient.createAvatarUploadUrl(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        CreateAvatarUploadUrlRequestDto request = new CreateAvatarUploadUrlRequestDto();
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);
        request.setSizeBytes(SIZE_BYTES);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/upload-url")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(CreateAvatarUploadUrlResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(grpcClient).createAvatarUploadUrl(
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("createAvatarUploadUrl: должен вернуть 401 без токена")
    void createAvatarUploadUrl_shouldReturn401_whenNoToken() {
        CreateAvatarUploadUrlRequestDto request = new CreateAvatarUploadUrlRequestDto();
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);
        request.setSizeBytes(SIZE_BYTES);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(grpcClient, Mockito.never())
                .createAvatarUploadUrl(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("createAvatarUploadUrl: должен вернуть 400 при невалидном contentType")
    void createAvatarUploadUrl_shouldReturn400_whenInvalidContentType() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.createAvatarUploadUrl(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Content type not allowed: application/pdf")));

        CreateAvatarUploadUrlRequestDto request = new CreateAvatarUploadUrlRequestDto();
        request.setFileName(FILE_NAME);
        request.setContentType("application/pdf");
        request.setSizeBytes(SIZE_BYTES);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/upload-url")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("createAvatarUploadUrl: должен вернуть 400 при sizeBytes больше максимума")
    void createAvatarUploadUrl_shouldReturn400_whenSizeTooLarge() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.createAvatarUploadUrl(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "File size exceeds maximum")));

        CreateAvatarUploadUrlRequestDto request = new CreateAvatarUploadUrlRequestDto();
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);
        request.setSizeBytes(10_000_000L);  // 10 MB

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/upload-url")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("createAvatarUploadUrl: должен вернуть 503 при недоступном storage")
    void createAvatarUploadUrl_shouldReturn503_whenStorageUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.createAvatarUploadUrl(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Storage unavailable").asRuntimeException()));

        CreateAvatarUploadUrlRequestDto request = new CreateAvatarUploadUrlRequestDto();
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);
        request.setSizeBytes(SIZE_BYTES);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/upload-url")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ТЕСТЫ confirmAvatarUpload ====================

    @Test
    @DisplayName("confirmAvatarUpload: должен вернуть 200 с AvatarResponseDto")
    void confirmAvatarUpload_shouldReturn200() {
        mockAuthenticatedUser();

        AvatarResponseDto response = createAvatarResponseDto();

        Mockito.when(grpcClient.confirmAvatarUpload(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey(OBJECT_KEY);
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(AvatarResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(grpcClient).confirmAvatarUpload(
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("confirmAvatarUpload: должен вернуть 404 при objectKey не найден")
    void confirmAvatarUpload_shouldReturn404_whenObjectNotFound() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.confirmAvatarUpload(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Object not found")));

        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey("non-existent-key");
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("confirmAvatarUpload: должен вернуть 400 при пустом fileName")
    void confirmAvatarUpload_shouldReturn400_whenFileNameEmpty() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.confirmAvatarUpload(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "fileName is required")));

        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey(OBJECT_KEY);
        request.setFileName("");
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("confirmAvatarUpload: должен вернуть 401 без токена")
    void confirmAvatarUpload_shouldReturn401_whenNoToken() {
        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey(OBJECT_KEY);
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("confirmAvatarUpload: должен вернуть 503 при недоступном storage")
    void confirmAvatarUpload_shouldReturn503_whenStorageUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.confirmAvatarUpload(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Storage unavailable").asRuntimeException()));

        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey(OBJECT_KEY);
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ТЕСТЫ deleteMyAvatar ====================

    @Test
    @DisplayName("deleteMyAvatar: должен вернуть 204 при успешном удалении")
    void deleteMyAvatar_shouldReturn204() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.deleteMyAvatar(
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/users/me/avatar")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(grpcClient).deleteMyAvatar(
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("deleteMyAvatar: должен вернуть 204 если аватар отсутствует (идемпотентность)")
    void deleteMyAvatar_shouldReturn204_whenNoAvatar() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.deleteMyAvatar(
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/users/me/avatar")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(grpcClient).deleteMyAvatar(
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("deleteMyAvatar: должен вернуть 401 без токена")
    void deleteMyAvatar_shouldReturn401_whenNoToken() {
        webTestClient.delete()
                .uri("/api/v1/users/me/avatar")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(grpcClient, Mockito.never())
                .deleteMyAvatar(Mockito.any());
    }

    @Test
    @DisplayName("deleteMyAvatar: должен вернуть 503 при недоступном storage")
    void deleteMyAvatar_shouldReturn503_whenStorageUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.deleteMyAvatar(
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Storage unavailable").asRuntimeException()));

        webTestClient.delete()
                .uri("/api/v1/users/me/avatar")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ТЕСТЫ getAvatarDownloadUrl ====================

    @Test
    @DisplayName("getAvatarDownloadUrl: должен вернуть 200 с url")
    void getAvatarDownloadUrl_shouldReturn200() {
        mockAuthenticatedUser();

        GetAvatarDownloadUrlResponseDto response = new GetAvatarDownloadUrlResponseDto();
        response.setUrl(DOWNLOAD_URL);

        Mockito.when(grpcClient.getAvatarDownloadUrl(
                        Mockito.any(UUID.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/users/{userId}/avatar", OTHER_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(GetAvatarDownloadUrlResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(grpcClient).getAvatarDownloadUrl(
                Mockito.eq(UUID.fromString(OTHER_USER_ID)),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("getAvatarDownloadUrl: должен вернуть 200 с url:null если аватар отсутствует")
    void getAvatarDownloadUrl_shouldReturn200_whenNoAvatar() {
        mockAuthenticatedUser();

        GetAvatarDownloadUrlResponseDto response = new GetAvatarDownloadUrlResponseDto();
        response.setUrl(null);

        Mockito.when(grpcClient.getAvatarDownloadUrl(
                        Mockito.any(UUID.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/users/{userId}/avatar", OTHER_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(GetAvatarDownloadUrlResponseDto.class)
                .isEqualTo(response);
    }

    @Test
    @DisplayName("getAvatarDownloadUrl: должен вернуть 404 если пользователь не найден")
    void getAvatarDownloadUrl_shouldReturn404_whenUserNotFound() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.getAvatarDownloadUrl(
                        Mockito.any(UUID.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found")));

        webTestClient.get()
                .uri("/api/v1/users/{userId}/avatar", OTHER_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("getAvatarDownloadUrl: должен вернуть 401 без токена")
    void getAvatarDownloadUrl_shouldReturn401_whenNoToken() {
        webTestClient.get()
                .uri("/api/v1/users/{userId}/avatar", OTHER_USER_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verify(grpcClient, Mockito.never())
                .getAvatarDownloadUrl(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("getAvatarDownloadUrl: должен вернуть 503 при недоступном storage")
    void getAvatarDownloadUrl_shouldReturn503_whenStorageUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.getAvatarDownloadUrl(
                        Mockito.any(UUID.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Storage unavailable").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/users/{userId}/avatar", OTHER_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ОБЩИЕ ТЕСТЫ ====================

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout при превышении deadline")
    void shouldReturn504_whenDeadlineExceeded() {
        mockAuthenticatedUser();

        Mockito.when(grpcClient.confirmAvatarUpload(
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout").asRuntimeException()));

        ConfirmAvatarUploadRequestDto request = new ConfirmAvatarUploadRequestDto();
        request.setObjectKey(OBJECT_KEY);
        request.setFileName(FILE_NAME);
        request.setContentType(CONTENT_TYPE);

        webTestClient.post()
                .uri("/api/v1/users/me/avatar/confirm")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== HELPER METHODS ====================

    private AvatarResponseDto createAvatarResponseDto() {
        AvatarResponseDto dto = new AvatarResponseDto();
        dto.setId(UUID.fromString(AVATAR_ID));
        dto.setUserId(UUID.fromString(USER_ID));
        dto.setObjectKey(OBJECT_KEY);
        dto.setFileName(FILE_NAME);
        dto.setContentType(CONTENT_TYPE);
        dto.setSizeBytes(SIZE_BYTES);
        dto.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        dto.setDownloadUrl(DOWNLOAD_URL);
        return dto;
    }

    private void mockAuthenticatedUser() {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(
                        UserContext.newBuilder()
                                .setUserId(USER_ID)
                                .build()
                )
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .login("testuser")
                .email("test@example.com")
                .displayName("Test User")
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        Mockito.when(authServiceClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}