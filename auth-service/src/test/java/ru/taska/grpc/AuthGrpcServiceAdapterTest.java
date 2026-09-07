package ru.taska.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcBadGrammarException;
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
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.RefreshRequestBody;
import ru.taska.api.auth.v1.SetPasswordByTokenRequestBody;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.auth.v1.ValidateAccessTokenRequestBody;
import ru.taska.api.common.v1.UserContext;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.UUID;

/**
 * Тесты для AuthGrpcServiceAdapter.
 * Проверяют, что адаптер корректно делегирует вызовы и преобразует ошибки в gRPC статусы.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthGrpcServiceAdapter Unit Tests")
class AuthGrpcServiceAdapterTest {

    // ============ Мокаем authGrpcService, потому что адаптер вызывает его ============
    @Mock
    private AuthGrpcService authGrpcService;

    @InjectMocks
    private AuthGrpcServiceAdapter adapter;

    private LoginRequest validLoginRequest;
    private RefreshRequest validRefreshRequest;
    private SetPasswordByTokenRequest validSetPasswordByTokenRequest;
    private ValidateAccessTokenRequest validValidateTokenRequest;
    private UserContext userContext;

    @BeforeEach
    void setUp() {
        validLoginRequest = LoginRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-request-id")
                        .setNodeId("test-node-id")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("test@example.com")
                        .setPassword("password123")
                        .build())
                .build();

        validRefreshRequest = RefreshRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-request-id")
                        .setNodeId("test-node-id")
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken("valid-refresh-token")
                        .build())
                .build();

        validSetPasswordByTokenRequest = SetPasswordByTokenRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-request-id")
                        .setNodeId("test-node-id")
                        .build())
                .setBody(SetPasswordByTokenRequestBody.newBuilder()
                        .setToken("valid-invite-token-123")
                        .setNewPassword("NewValidPassword123!")
                        .build())
                .build();

        userContext = UserContext.newBuilder()
                .setUserId(UUID.randomUUID().toString())
                .setLogin("testuser")
                .setEmail("test@example.com")
                .setDisplayName("Test User")
                .build();

        validValidateTokenRequest = ValidateAccessTokenRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-request-id")
                        .setNodeId("test-node-id")
                        .build())
                .setBody(ValidateAccessTokenRequestBody.newBuilder()
                        .setAccessToken("valid.jwt.token")
                        .build())
                .build();
    }

    // ==================== LOGIN TESTS ====================

    @Test
    @DisplayName("Should successfully process login request")
    void shouldSuccessfullyProcessLoginRequest() {
        // Given
        // Адаптер вызывает authGrpcService, мокаем его
        Mockito.when(authGrpcService.login(ArgumentMatchers.any()))
                .thenReturn(Mono.just(LoginResponse.newBuilder()
                        .setAccessToken("access-token-123")
                        .setRefreshToken("refresh-token-456")
                        .setExpiresIn(900L)
                        .build()));

        // When & Then
        StepVerifier.create(adapter.login(Mono.just(validLoginRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle login with DomainException")
    void shouldHandleLoginWithDomainException() {
        // Given
        Mockito.when(authGrpcService.login(ArgumentMatchers.any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));

        // When & Then
        // Адаптер через GrpcExceptionHandler преобразует DomainException → StatusRuntimeException
        StepVerifier.create(adapter.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle login with invalid email")
    void shouldHandleLoginWithInvalidEmail() {
        // Мокаем authGrpcService, чтобы он вернул ошибку валидации
        Mockito.when(authGrpcService.login(ArgumentMatchers.any()))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INVALID_ARGUMENT)));
        // Given
        LoginRequest invalidRequest = LoginRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-id")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("")
                        .setPassword("password123")
                        .build())
                .build();

        // When & Then
        // Валидация происходит в AuthGrpcService, адаптер просто пробрасывает ошибку
        StepVerifier.create(adapter.login(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle missing header in login request")
    void shouldHandleMissingHeaderInLoginRequest() {

        Mockito.when(authGrpcService.login(Mockito.any()))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INVALID_ARGUMENT)));

        // Given
        LoginRequest noHeaderRequest = LoginRequest.newBuilder()
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("test@example.com")
                        .setPassword("password123")
                        .build())
                .build();

        // When & Then
        StepVerifier.create(adapter.login(Mono.just(noHeaderRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();
    }

    // ==================== REFRESH TESTS ====================

    @Test
    @DisplayName("Should successfully process refresh request")
    void shouldSuccessfullyProcessRefreshRequest() {
        // Given
        Mockito.when(authGrpcService.refresh(ArgumentMatchers.any()))
                .thenReturn(Mono.just(RefreshResponse.newBuilder()
                        .setAccessToken("access-token-123")
                        .setRefreshToken("refresh-token-456")
                        .setExpiresIn(900L)
                        .build()));

        // When & Then
        StepVerifier.create(adapter.refresh(Mono.just(validRefreshRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle refresh with DomainException")
    void shouldHandleRefreshWithDomainException() {
        // Given
        Mockito.when(authGrpcService.refresh(ArgumentMatchers.any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid refresh token")));

        // When & Then
        StepVerifier.create(adapter.refresh(Mono.just(validRefreshRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle refresh with invalid request body")
    void shouldHandleRefreshWithInvalidRequestBody() {

        Mockito.when(authGrpcService.refresh(Mockito.any()))
                .thenReturn(Mono.error(new StatusRuntimeException(Status.INVALID_ARGUMENT)));

        // Given
        RefreshRequest invalidRequest = RefreshRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-id")
                        .setNodeId("test-node")
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken("")
                        .build())
                .build();

        // When & Then
        StepVerifier.create(adapter.refresh(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();
    }

    // ==================== SET PASSWORD BY TOKEN TESTS ====================

    @Test
    @DisplayName("Should successfully process setPasswordByToken request")
    void shouldSuccessfullyProcessSetPasswordByTokenRequest() {
        // Given
        Mockito.when(authGrpcService.setPasswordByToken(ArgumentMatchers.any()))
                .thenReturn(Mono.just(Empty.getDefaultInstance()));

        // When & Then
        StepVerifier.create(adapter.setPasswordByToken(Mono.just(validSetPasswordByTokenRequest)))
                .expectNext(Empty.getDefaultInstance())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle setPasswordByToken with DomainException")
    void shouldHandleSetPasswordByTokenWithDomainException() {
        // Given
        Mockito.when(authGrpcService.setPasswordByToken(ArgumentMatchers.any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired token")));

        // When & Then
        StepVerifier.create(adapter.setPasswordByToken(Mono.just(validSetPasswordByTokenRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();
    }

    // ==================== VALIDATE ACCESS TOKEN TESTS ====================

    @Nested
    @DisplayName("Validate Access Token Tests")
    class ValidateAccessTokenTests {

        @BeforeEach
        void setUpValidateAccessTokenTests() {
            validValidateTokenRequest = ValidateAccessTokenRequest.newBuilder()
                    .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                            .setRequestId("test-request-id")
                            .setNodeId("test-node-id")
                            .build())
                    .setBody(ValidateAccessTokenRequestBody.newBuilder()
                            .setAccessToken("valid.jwt.token")
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Should successfully validate access token and return user context")
        void shouldSuccessfullyValidateAccessTokenAndReturnUserContext() {
            // Given
            Mockito.when(authGrpcService.validateAccessToken(ArgumentMatchers.any()))
                    .thenReturn(Mono.just(ValidateAccessTokenResponse.newBuilder()
                            .setUserContext(userContext)
                            .build()));

            // When & Then
            StepVerifier.create(adapter.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectNextMatches(response ->
                            response.getUserContext().getUserId().equals(userContext.getUserId()) &&
                                    response.getUserContext().getLogin().equals(userContext.getLogin()) &&
                                    response.getUserContext().getEmail().equals(userContext.getEmail()) &&
                                    response.getUserContext().getDisplayName().equals(userContext.getDisplayName())
                    )
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle DomainException from AuthGrpcService")
        void shouldHandleDomainExceptionFromAuthGrpcService() {
            // Given
            Mockito.when(authGrpcService.validateAccessToken(ArgumentMatchers.any()))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid JWT token")));

            // When & Then
            StepVerifier.create(adapter.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("UNAUTHENTICATED")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should convert R2dbcBadGrammarException to UNAVAILABLE")
        void shouldConvertR2dbcBadGrammarExceptionToUnavailable() {
            // Given
            Mockito.when(authGrpcService.validateAccessToken(ArgumentMatchers.any()))
                    .thenReturn(Mono.error(new R2dbcBadGrammarException("Table not found")));

            // When & Then
            StepVerifier.create(adapter.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("UNAVAILABLE")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should convert TransactionException to UNAVAILABLE")
        void shouldConvertTransactionExceptionToUnavailable() {
            // Given
            TransactionException transactionException = Mockito.mock(TransactionException.class);
            Mockito.when(authGrpcService.validateAccessToken(ArgumentMatchers.any()))
                    .thenReturn(Mono.error(transactionException));

            // When & Then
            StepVerifier.create(adapter.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("UNAVAILABLE")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should convert RuntimeException to INTERNAL")
        void shouldConvertRuntimeExceptionToInternal() {
            // Given
            Mockito.when(authGrpcService.validateAccessToken(ArgumentMatchers.any()))
                    .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

            // When & Then
            StepVerifier.create(adapter.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INTERNAL")
                    )
                    .verify();
        }
    }
}