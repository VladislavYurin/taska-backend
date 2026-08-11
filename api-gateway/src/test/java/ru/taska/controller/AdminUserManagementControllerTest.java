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
import ru.taska.domain.dto.BlockUserRequestDto;
import ru.taska.domain.dto.ResetLockoutRequestDto;
import ru.taska.domain.dto.UnblockUserRequestDto;
import ru.taska.domain.dto.UserStatusDto;
import ru.taska.domain.dto.UserStatusResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAdminServiceClient;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@WebFluxTest(controllers = AdminUserManagementController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
@DisplayName("AdminUserManagementController Unit Tests")
class AdminUserManagementControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TARGET_USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String REASON = "Test reason";
    private static final String LOGIN = "admin";
    private static final String EMAIL = "admin@example.com";
    private static final String DISPLAY_NAME = "Admin Adminov";
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.of(
            2026, 9, 2, 0, 0, 0, 0, ZoneOffset.UTC
    );

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authServiceClient;

    @MockitoBean
    private GrpcAdminServiceClient adminClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ==================== ТЕСТЫ blockUser ====================

    @Test
    @DisplayName("blockUser: должен вернуть UserStatusResponseDto и статус 200")
    void blockUser_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        UUID targetUserId = UUID.fromString(TARGET_USER_ID);
        UserStatusResponseDto response = new UserStatusResponseDto();
        response.setUserId(targetUserId);
        response.setPreviousStatus(UserStatusDto.ACTIVE);
        response.setCurrentStatus(UserStatusDto.BLOCKED);
        response.setChangedAt(FIXED_TIME);

        Mockito.when(adminClient.blockUser(
                        Mockito.eq(targetUserId),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(UserStatusResponseDto.class).isEqualTo(response);

        Mockito.verify(adminClient).blockUser(
                Mockito.eq(targetUserId),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("blockUser: должен вернуть 404 если пользователь не найден")
    void blockUser_shouldReturn404_whenUserNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.blockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("blockUser: должен вернуть 403 при ошибке PERMISSION_DENIED")
    void blockUser_shouldReturn403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.blockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.withDescription("Access Denied").asRuntimeException()));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("blockUser: должен вернуть 400 при пустом reason")
    void blockUser_shouldReturn400_whenReasonIsEmpty() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.blockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reason is required")));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason("");

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ТЕСТЫ unblockUser ====================

    @Test
    @DisplayName("unblockUser: должен вернуть UserStatusResponseDto и статус 200")
    void unblockUser_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        UUID targetUserId = UUID.fromString(TARGET_USER_ID);
        UserStatusResponseDto response = new UserStatusResponseDto();
        response.setUserId(targetUserId);
        response.setPreviousStatus(UserStatusDto.BLOCKED);
        response.setCurrentStatus(UserStatusDto.ACTIVE);
        response.setChangedAt(FIXED_TIME);

        Mockito.when(adminClient.unblockUser(
                        Mockito.eq(targetUserId),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        UnblockUserRequestDto requestDto = new UnblockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/unblock", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(UserStatusResponseDto.class).isEqualTo(response);

        Mockito.verify(adminClient).unblockUser(
                Mockito.eq(targetUserId),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("unblockUser: должен вернуть 404 если пользователь не найден")
    void unblockUser_shouldReturn404_whenUserNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.unblockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        UnblockUserRequestDto requestDto = new UnblockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/unblock", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("unblockUser: должен вернуть 403 при ошибке PERMISSION_DENIED")
    void unblockUser_shouldReturn403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.unblockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.withDescription("Access Denied").asRuntimeException()));

        UnblockUserRequestDto requestDto = new UnblockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/unblock", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ТЕСТЫ resetCredentialLockout ====================

    @Test
    @DisplayName("resetCredentialLockout: должен вернуть UserStatusResponseDto и статус 200")
    void resetCredentialLockout_shouldReturnResponseAndStatus200() {
        mockAuthenticatedUser();

        UUID targetUserId = UUID.fromString(TARGET_USER_ID);
        UserStatusResponseDto response = new UserStatusResponseDto();
        response.setUserId(targetUserId);
        response.setPreviousStatus(UserStatusDto.LOCKED);
        response.setCurrentStatus(UserStatusDto.ACTIVE);
        response.setChangedAt(FIXED_TIME);

        Mockito.when(adminClient.resetCredentialLockout(
                        Mockito.eq(targetUserId),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        ResetLockoutRequestDto requestDto = new ResetLockoutRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/reset-lockout", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(UserStatusResponseDto.class).isEqualTo(response);

        Mockito.verify(adminClient).resetCredentialLockout(
                Mockito.eq(targetUserId),
                Mockito.any(),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("resetCredentialLockout: должен вернуть 404 если пользователь не найден")
    void resetCredentialLockout_shouldReturn404_whenUserNotFound() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.resetCredentialLockout(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")));

        ResetLockoutRequestDto requestDto = new ResetLockoutRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/reset-lockout", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("resetCredentialLockout: должен вернуть 409 если пользователь не в статусе LOCKED")
    void resetCredentialLockout_shouldReturn409_whenUserIsNotLocked() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.resetCredentialLockout(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "User is not in LOCKED status")));

        ResetLockoutRequestDto requestDto = new ResetLockoutRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/reset-lockout", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("resetCredentialLockout: должен вернуть 403 при ошибке PERMISSION_DENIED")
    void resetCredentialLockout_shouldReturn403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.resetCredentialLockout(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.withDescription("Access Denied").asRuntimeException()));

        ResetLockoutRequestDto requestDto = new ResetLockoutRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/reset-lockout", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== ОБЩИЕ ТЕСТЫ (authentication) ====================

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized без Bearer токена")
    void shouldReturn401_whenNoBearerToken() {
        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(adminClient, Mockito.never())
                .blockUser(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 503 Service Unavailable при недоступном downstream")
    void shouldReturn503_whenDownstreamUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.blockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout при превышении deadline")
    void shouldReturn504_whenDeadlineExceeded() {
        mockAuthenticatedUser();

        Mockito.when(adminClient.blockUser(
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout Exceeded").asRuntimeException()));

        BlockUserRequestDto requestDto = new BlockUserRequestDto();
        requestDto.setReason(REASON);

        webTestClient.post()
                .uri("/api/v1/admin/users/{userId}/block", TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id");
    }

    // ==================== HELPER METHODS ====================

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
                .login(LOGIN)
                .email(EMAIL)
                .displayName(DISPLAY_NAME)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.GLOBAL_ADMIN)
                .build();

        Mockito.when(authServiceClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}