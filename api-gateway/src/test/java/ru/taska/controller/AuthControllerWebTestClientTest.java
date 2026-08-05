package ru.taska.controller;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.LoginResponseDto;
import ru.taska.domain.dto.RefreshResponseDto;
import ru.taska.domain.dto.ValidateAccessTokenResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.AuthMapper;
import ru.taska.mapper.ContextMapper;
import ru.taska.service.UserService;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

@WebFluxTest(controllers = AuthController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class AuthControllerWebTestClientTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GrpcAuthServiceClient grpcAuthServiceClient;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthMapper authMapper;

    @MockitoBean
    private ContextMapper contextMapper;

    @MockitoSpyBean
    private GatewayContextFactory contextFactory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Успешный login")
    void login_Success_Returns200AndTokens() {
        LoginResponseDto responseDto = new LoginResponseDto();
        responseDto.setAccessToken("valid-access-token");
        responseDto.setRefreshToken("valid-refresh-token");
        responseDto.setExpiresIn(900L);

        Mockito.when(grpcAuthServiceClient.login(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.just(responseDto));

        String requestBody = "{\"email\":\"anna@example.com\",\"password\":\"CorrectHorse123!\"}";

        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "custom-request-id-123")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "custom-request-id-123")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("valid-access-token")
                .jsonPath("$.refreshToken").isEqualTo("valid-refresh-token")
                .jsonPath("$.expiresIn").isEqualTo(900);
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - Неверные credentials")
    void login_InvalidCredentials_Returns401() {
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.UNAUTHENTICATED.withDescription("Invalid email or password")
        );
        Mockito.when(grpcAuthServiceClient.login(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.error(grpcError));

        String requestBody = "{\"email\":\"anna@example.com\",\"password\":\"wrong\"}";

        webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-login-fail")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-login-fail")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Успешная ротация токена")
    void refresh_Success_Returns200AndNewTokens() {
        RefreshResponseDto responseDto = new RefreshResponseDto();
        responseDto.setAccessToken("new-access-token");
        responseDto.setRefreshToken("new-refresh-token");
        responseDto.setExpiresIn(3000L);

        Mockito.when(grpcAuthServiceClient.refresh(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.just(responseDto));

        String requestBody = "{\"refreshToken\":\"valid-old-refresh-token\"}";

        webTestClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-refresh-success")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-refresh-success")
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("new-access-token")
                .jsonPath("$.refreshToken").isEqualTo("new-refresh-token")
                .jsonPath("$.expiresIn").isEqualTo(3000L);
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - Невалидный/истекший refresh token")
    void refresh_InvalidOrExpiredToken_Returns401() {
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.UNAUTHENTICATED.withDescription("Refresh token expired")
        );
        Mockito.when(grpcAuthServiceClient.refresh(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.error(grpcError));

        String requestBody = "{\"refreshToken\":\"expired-refresh-token\"}";

        webTestClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-refresh-fail")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-refresh-fail")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Refresh token expired");
    }

    @Test
    @DisplayName("POST /api/v1/auth/invitations/accept - Успешное принятие приглашения")
    void acceptInvitation_Success_Returns204NoContent() {
        Mockito.when(grpcAuthServiceClient.setPasswordByToken(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.empty());

        String requestBody = "{\"token\":\"invite-token\",\"newPassword\":\"CorrectHorse123!\"}";

        webTestClient.post()
                .uri("/api/v1/auth/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-invite-success")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals("X-Request-Id", "req-invite-success")
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("POST /api/v1/auth/invitations/accept - Невалидный/истекший invite token")
    void acceptInvitation_InvalidOrExpiredToken_Returns400() {
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("Invitation token is invalid or has expired")
        );
        Mockito.when(grpcAuthServiceClient.setPasswordByToken(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.error(grpcError));

        String requestBody = "{\"token\":\"expired-invite-token\",\"newPassword\":\"CorrectHorse123!\"}";

        webTestClient.post()
                .uri("/api/v1/auth/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-invite-fail-token")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Request-Id", "req-invite-fail-token")
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_ARGUMENT")
                .jsonPath("$.message").isEqualTo("Invitation token is invalid or has expired");
    }

    @Test
    @DisplayName("POST /api/v1/auth/invitations/accept - Нарушение password policy")
    void acceptInvitation_PasswordPolicyViolation_Returns400() {
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription("Password does not meet complexity requirements")
        );
        Mockito.when(grpcAuthServiceClient.setPasswordByToken(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Mono.error(grpcError));

        String requestBody = "{\"token\":\"valid-invite-token\",\"newPassword\":\"123\"}";

        webTestClient.post()
                .uri("/api/v1/auth/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-invite-fail-policy")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Request-Id", "req-invite-fail-policy")
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_ARGUMENT")
                .jsonPath("$.message").isEqualTo("Password does not meet complexity requirements");
    }

    @Test
    @DisplayName("GET /api/v1/users/me - Запрос без токена")
    void getMyInfo_MissingToken_Returns401() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .header("X-Request-Id", "req-me-no-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-me-no-token")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("Authorization header is missing");
    }

    @Test
    @DisplayName("GET /api/v1/users/me - Запрос с невалидным токеном")
    void getMyInfo_InvalidToken_Returns401() {
        StatusRuntimeException grpcError = new StatusRuntimeException(
                Status.UNAUTHENTICATED.withDescription("Access token is invalid or expired")
        );

        Mockito.when(grpcAuthServiceClient.validateAccessToken(ArgumentMatchers.eq(
                "req-me-invalid-token"), ArgumentMatchers.any(), ArgumentMatchers.eq("invalid-token")))
                .thenReturn(Mono.error(grpcError));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .header("X-Request-Id", "req-me-invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-me-invalid-token")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.message").isEqualTo("Access token is invalid or expired");
    }

    @Test
    @DisplayName("GET /api/v1/users/me - Успешный запрос с валидным токеном")
    void getMyInfo_Success_Returns200AndUserData() {
        String token = "valid-access-token";
        ValidateAccessTokenResponse grpcResponse = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId("6d774efa-57d8-4ae0-a27e-2984d1dfbbf6").build())
                .build();

        GatewayUserContext gatewayUserContext = new GatewayUserContext(
                "6d774efa-57d8-4ae0-a27e-2984d1dfbbf6",
                "anna",
                "anna@example.com",
                "Anna Ivanova",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER
        );

        ru.taska.domain.dto.ValidateAccessTokenResponseDto restResponseDto = new ru.taska.domain.dto.ValidateAccessTokenResponseDto();
        restResponseDto.setId("6d774efa-57d8-4ae0-a27e-2984d1dfbbf6");
        restResponseDto.setLogin("anna");
        restResponseDto.setEmail("anna@example.com");
        restResponseDto.setDisplayName("Anna Ivanova");
        restResponseDto.setStatus("ACTIVE");
        restResponseDto.setGlobalRole(ValidateAccessTokenResponseDto.GlobalRoleEnum.valueOf("USER"));

        Mockito.when(grpcAuthServiceClient.validateAccessToken(ArgumentMatchers.eq(
                "req-me-success"), ArgumentMatchers.any(), ArgumentMatchers.eq(token)))
                .thenReturn(Mono.just(grpcResponse));
        Mockito.when(contextMapper.mapToGatewayUserContext(ArgumentMatchers.any())).thenReturn(gatewayUserContext);
        Mockito.when(authMapper.toValidateAccessTokenRestResponse(gatewayUserContext)).thenReturn(restResponseDto);
        Mockito.when(userService.getMyInfo(ArgumentMatchers.any())).thenReturn(Mono.just(restResponseDto));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Request-Id", "req-me-success")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-me-success")
                .expectBody()
                .jsonPath("$.id").isEqualTo("6d774efa-57d8-4ae0-a27e-2984d1dfbbf6")
                .jsonPath("$.login").isEqualTo("anna")
                .jsonPath("$.email").isEqualTo("anna@example.com")
                .jsonPath("$.displayName").isEqualTo("Anna Ivanova")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.globalRole").isEqualTo("USER");
    }
}

