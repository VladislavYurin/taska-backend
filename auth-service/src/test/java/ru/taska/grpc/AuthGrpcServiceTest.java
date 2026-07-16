package ru.taska.grpc;

import com.google.protobuf.Empty;
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
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.UserContext;
import ru.taska.dto.AuthResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.AuthService;

import java.util.UUID;

/**
 * Тесты для AuthGrpcService.
 * Проверяют бизнес-логику: валидацию запросов и вызов AuthService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthGrpcService Unit Tests")
class AuthGrpcServiceTest {

    @Mock
    private AuthService authService;  // ← Мокаем AuthService

    @InjectMocks
    private AuthGrpcService authGrpcService;  // ← Тестируем AuthGrpcService

    private LoginRequest validLoginRequest;
    private RefreshRequest validRefreshRequest;
    private SetPasswordByTokenRequest validSetPasswordByTokenRequest;
    private ValidateAccessTokenRequest validValidateTokenRequest;
    private UserContext userContext;
    private AuthResponseDto authResponseDto;

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

        authResponseDto = AuthResponseDto.builder()
                .accessToken("access-token-123")
                .refreshToken("refresh-token-456")
                .expiresIn(900L)
                .build();
    }

    // ==================== LOGIN TESTS ====================

    @Test
    @DisplayName("Should successfully process login request")
    void shouldSuccessfullyProcessLoginRequest() {
        // Given
        // Мокаем AuthService (бизнес-логику)
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(authResponseDto));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();

        // Проверяем, что AuthService был вызван с правильными параметрами
        Mockito.verify(authService).login("test@example.com", "password123");
    }

    @Test
    @DisplayName("Should handle login with DomainException")
    void shouldHandleLoginWithDomainException() {
        // Given
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));

        // When & Then
        // AuthGrpcService НЕ преобразует ошибки, просто пробрасывает их
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED
                )
                .verify();

        Mockito.verify(authService).login("test@example.com", "password123");
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT when email is blank")
    void shouldReturnInvalidArgumentWhenEmailIsBlank() {
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
        // Валидация происходит в AuthGrpcService через GrpcRequestValidators
        StepVerifier.create(authGrpcService.login(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        // AuthService НЕ должен вызываться при ошибке валидации
        Mockito.verify(authService, Mockito.never()).login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT when password is blank")
    void shouldReturnInvalidArgumentWhenPasswordIsBlank() {
        // Given
        LoginRequest invalidRequest = LoginRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-id")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("test@example.com")
                        .setPassword("")
                        .build())
                .build();

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never()).login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    // ==================== REFRESH TESTS ====================

    @Test
    @DisplayName("Should successfully process refresh request")
    void shouldSuccessfullyProcessRefreshRequest() {
        // Given
        Mockito.when(authService.refresh(ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(authResponseDto));

        // When & Then
        StepVerifier.create(authGrpcService.refresh(Mono.just(validRefreshRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();

        Mockito.verify(authService).refresh("valid-refresh-token");
    }

    @Test
    @DisplayName("Should handle refresh with DomainException")
    void shouldHandleRefreshWithDomainException() {
        // Given
        Mockito.when(authService.refresh(ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid refresh token")));

        // When & Then
        StepVerifier.create(authGrpcService.refresh(Mono.just(validRefreshRequest)))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED
                )
                .verify();

        Mockito.verify(authService).refresh("valid-refresh-token");
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT when refresh token is blank")
    void shouldReturnInvalidArgumentWhenRefreshTokenIsBlank() {
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
        StepVerifier.create(authGrpcService.refresh(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never()).refresh(ArgumentMatchers.anyString());
    }

    // ==================== SET PASSWORD BY TOKEN TESTS ====================

    @Test
    @DisplayName("Should successfully process setPasswordByToken request")
    void shouldSuccessfullyProcessSetPasswordByTokenRequest() {
        // Given
        Mockito.when(authService.setPasswordByToken(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenReturn(Mono.empty());

        // When & Then
        StepVerifier.create(authGrpcService.setPasswordByToken(Mono.just(validSetPasswordByTokenRequest)))
                .expectNext(Empty.getDefaultInstance())
                .verifyComplete();

        Mockito.verify(authService).setPasswordByToken("test-request-id", "valid-invite-token-123", "NewValidPassword123!");
    }

    @Test
    @DisplayName("Should handle setPasswordByToken with DomainException")
    void shouldHandleSetPasswordByTokenWithDomainException() {
        // Given
        Mockito.when(authService.setPasswordByToken(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired token")));

        // When & Then
        StepVerifier.create(authGrpcService.setPasswordByToken(Mono.just(validSetPasswordByTokenRequest)))
                .expectErrorMatches(error ->
                        error instanceof DomainException &&
                                ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED
                )
                .verify();

        Mockito.verify(authService).setPasswordByToken("test-request-id", "valid-invite-token-123", "NewValidPassword123!");
    }

    @Test
    @DisplayName("Should return INVALID_ARGUMENT when token is blank")
    void shouldReturnInvalidArgumentWhenTokenIsBlank() {
        // Given
        SetPasswordByTokenRequest invalidRequest = SetPasswordByTokenRequest.newBuilder()
                .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                        .setRequestId("test-id")
                        .setNodeId("test-node")
                        .build())
                .setBody(SetPasswordByTokenRequestBody.newBuilder()
                        .setToken("")
                        .setNewPassword("NewValidPassword123!")
                        .build())
                .build();

        // When & Then
        StepVerifier.create(authGrpcService.setPasswordByToken(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never())
                .setPasswordByToken(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    // ==================== VALIDATE ACCESS TOKEN TESTS ====================

    @Nested
    @DisplayName("Validate Access Token Tests")
    class ValidateAccessTokenTests {

        @BeforeEach
        void setUpValidateAccessTokenTests() {
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

        @Test
        @DisplayName("Should successfully validate access token and return user context")
        void shouldSuccessfullyValidateAccessTokenAndReturnUserContext() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(userContext));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectNextMatches(response ->
                            response.getUserContext().getUserId().equals(userContext.getUserId()) &&
                                    response.getUserContext().getLogin().equals(userContext.getLogin()) &&
                                    response.getUserContext().getEmail().equals(userContext.getEmail()) &&
                                    response.getUserContext().getDisplayName().equals(userContext.getDisplayName())
                    )
                    .verifyComplete();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should propagate DomainException from AuthService")
        void shouldPropagateDomainExceptionFromAuthService() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid JWT token")));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when access token is blank")
        void shouldReturnInvalidArgumentWhenAccessTokenIsBlank() {
            // Given
            ValidateAccessTokenRequest invalidRequest = ValidateAccessTokenRequest.newBuilder()
                    .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                            .setRequestId("test-id")
                            .setNodeId("test-node")
                            .build())
                    .setBody(ValidateAccessTokenRequestBody.newBuilder()
                            .setAccessToken("")
                            .build())
                    .build();

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(invalidRequest)))
                    .expectErrorMatches(error ->
                            error instanceof StatusRuntimeException &&
                                    error.getMessage().contains("INVALID_ARGUMENT")
                    )
                    .verify();

            Mockito.verify(authService, Mockito.never()).validateAccessToken(ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should propagate R2dbcBadGrammarException")
        void shouldPropagateR2dbcBadGrammarException() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(new R2dbcBadGrammarException("Table not found")));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof R2dbcBadGrammarException
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should propagate TransactionException")
        void shouldPropagateTransactionException() {
            // Given
            TransactionException transactionException = Mockito.mock(TransactionException.class);
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(transactionException));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof TransactionException
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should propagate RuntimeException")
        void shouldPropagateRuntimeException() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof RuntimeException &&
                                    error.getMessage().equals("Unexpected error")
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }
    }
}