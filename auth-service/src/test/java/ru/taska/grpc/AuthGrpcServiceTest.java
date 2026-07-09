package ru.taska.grpc;

import com.google.protobuf.Empty;
import org.junit.jupiter.api.Nested;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshRequestBody;
import ru.taska.api.auth.v1.SetPasswordByTokenRequest;
import ru.taska.api.auth.v1.SetPasswordByTokenRequestBody;
import ru.taska.api.auth.v1.ValidateAccessTokenRequest;
import ru.taska.api.auth.v1.ValidateAccessTokenRequestBody;
import ru.taska.api.common.v1.UserContext;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import io.r2dbc.spi.R2dbcBadGrammarException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import ru.taska.dto.PasswordByTokenResponseDto;
import ru.taska.service.AuthService;
import ru.taska.util.PasswordValidator;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthGrpcService Unit Tests")
class AuthGrpcServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private PasswordValidator passwordValidator;

    @InjectMocks
    private AuthGrpcService authGrpcService;

    private LoginRequest validLoginRequest;
    private RefreshRequest validRefreshRequest;
    private ru.taska.dto.AuthResponseDto authResponseDto;
    private PasswordByTokenResponseDto passwordByTokenResponseDto;
    private SetPasswordByTokenRequest validSetPasswordByTokenRequest;

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

        authResponseDto = ru.taska.dto.AuthResponseDto.builder()
                .accessToken("access-token-123")
                .refreshToken("refresh-token-456")
                .expiresIn(900L)
                .build();
        passwordByTokenResponseDto = new PasswordByTokenResponseDto("testuser@example.com");

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
    }

    @Test
    @DisplayName("Should successfully process login request")
    void shouldSuccessfullyProcessLoginRequest() {
        // Given
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

        Mockito.verify(authService).login("test@example.com", "password123");
    }

    @Test
    @DisplayName("Should handle login with DomainException")
    void shouldHandleLoginWithDomainException() {
        // Given
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();

        Mockito.verify(authService).login("test@example.com", "password123");
    }

    @Test
    @DisplayName("Should handle login with invalid email")
    void shouldHandleLoginWithInvalidEmail() {
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
        StepVerifier.create(authGrpcService.login(Mono.just(invalidRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never()).login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

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
    @DisplayName("Should handle refresh with invalid token")
    void shouldHandleRefreshWithInvalidToken() {
        // Given
        Mockito.when(authService.refresh(ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid refresh token")));

        // When & Then
        StepVerifier.create(authGrpcService.refresh(Mono.just(validRefreshRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();

        Mockito.verify(authService).refresh("valid-refresh-token");
    }

    @Test
    @DisplayName("Should handle R2DBC exception as database unavailable")
    void shouldHandleR2dbcExceptionAsDatabaseUnavailable() {
        // Given
        // Используем конкретную реализацию R2dbcException
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new R2dbcBadGrammarException("Table not found")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAVAILABLE")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle TransactionException as database unavailable")
    void shouldHandleTransactionExceptionAsDatabaseUnavailable() {
        // Given
        // Используем мок TransactionException
        TransactionException transactionException = Mockito.mock(TransactionException.class);
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(transactionException));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAVAILABLE")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle generic R2dbcNonTransientResourceException")
    void shouldHandleGenericR2dbcException() {
        // Given
        // Используем другую конкретную реализацию
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new R2dbcNonTransientResourceException("Connection pool exhausted")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAVAILABLE")
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle unknown error as internal error")
    void shouldHandleUnknownErrorAsInternalError() {
        // Given
        Mockito.when(authService.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                                            error instanceof io.grpc.StatusRuntimeException statusException &&
                                                    statusException.getStatus().getCode() == io.grpc.Status.Code.INTERNAL
                )
                .verify();
    }

    @Test
    @DisplayName("Should handle refresh with invalid request body")
    void shouldHandleRefreshWithInvalidRequestBody() {
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
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never()).refresh(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Should handle missing header in login request")
    void shouldHandleMissingHeaderInLoginRequest() {
        // Given
        LoginRequest noHeaderRequest = LoginRequest.newBuilder()
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("test@example.com")
                        .setPassword("password123")
                        .build())
                .build();

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(noHeaderRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("INVALID_ARGUMENT")
                )
                .verify();

        Mockito.verify(authService, Mockito.never()).login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

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
    @DisplayName("Should handle setPasswordByToken with invalid token")
    void shouldHandleSetPasswordByTokenWithInvalidToken() {
        // Given
        Mockito.when(authService.setPasswordByToken(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired token")));

        // When & Then
        StepVerifier.create(authGrpcService.setPasswordByToken(Mono.just(validSetPasswordByTokenRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();

        Mockito.verify(authService).setPasswordByToken("test-request-id", "valid-invite-token-123", "NewValidPassword123!");
    }

    @Nested
    @DisplayName("Validate Access Token Tests")
    class ValidateAccessTokenTests {

        private ValidateAccessTokenRequest validValidateTokenRequest;
        private UserContext userContext;

        @BeforeEach
        void setUpValidateAccessTokenTests() {
            userContext = UserContext.newBuilder()
                    .setUserId(UUID.randomUUID().toString())
                    .setLogin("testuser")
                    .setEmail("test@example.com")
                    .setDisplayName("Test User")
                    .build();

            validValidateTokenRequest = ru.taska.api.auth.v1.ValidateAccessTokenRequest.newBuilder()
                    .setHeader(ru.taska.api.common.v1.Header.newBuilder()
                            .setRequestId("test-request-id")
                            .setNodeId("test-node-id")
                            .build())
                    .setBody(ru.taska.api.auth.v1.ValidateAccessTokenRequestBody.newBuilder()
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
                            error instanceof io.grpc.StatusRuntimeException &&
                                    error.getMessage().contains("UNAUTHENTICATED")
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should return UNAVAILABLE when database error occurs")
        void shouldReturnUnavailableWhenDatabaseErrorOccurs() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(new R2dbcBadGrammarException("Table not found")));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof io.grpc.StatusRuntimeException &&
                                    error.getMessage().contains("UNAVAILABLE")
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should return UNAVAILABLE when TransactionException occurs")
        void shouldReturnUnavailableWhenTransactionExceptionOccurs() {
            // Given
            TransactionException transactionException = Mockito.mock(TransactionException.class);
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(transactionException));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                            error instanceof io.grpc.StatusRuntimeException &&
                                    error.getMessage().contains("UNAVAILABLE")
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }

        @Test
        @DisplayName("Should return INTERNAL when unexpected error occurs")
        void shouldReturnInternalWhenUnexpectedErrorOccurs() {
            // Given
            Mockito.when(authService.validateAccessToken(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

            // When & Then
            StepVerifier.create(authGrpcService.validateAccessToken(Mono.just(validValidateTokenRequest)))
                    .expectErrorMatches(error ->
                                                error instanceof io.grpc.StatusRuntimeException statusException &&
                                                        statusException.getStatus().getCode() == io.grpc.Status.Code.INTERNAL
                    )
                    .verify();

            Mockito.verify(authService).validateAccessToken("valid.jwt.token");
        }
    }
}