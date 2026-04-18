package ru.taska.grpc;

import exception.DomainException;
import exception.DomainStatus;
import io.r2dbc.spi.R2dbcBadGrammarException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshRequestBody;
import ru.taska.service.AuthService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthGrpcService Unit Tests")
class AuthGrpcServiceTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthGrpcService authGrpcService;

    private LoginRequest validLoginRequest;
    private RefreshRequest validRefreshRequest;
    private ru.taska.dto.AuthResponseDto authResponseDto;

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
    }

    @Test
    @DisplayName("Should successfully process login request")
    void shouldSuccessfullyProcessLoginRequest() {
        // Given
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.just(authResponseDto));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();

        verify(authService).login("test@example.com", "password123");
    }

    @Test
    @DisplayName("Should handle login with DomainException")
    void shouldHandleLoginWithDomainException() {
        // Given
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();

        verify(authService).login("test@example.com", "password123");
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

        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    @DisplayName("Should successfully process refresh request")
    void shouldSuccessfullyProcessRefreshRequest() {
        // Given
        when(authService.refresh(anyString()))
                .thenReturn(Mono.just(authResponseDto));

        // When & Then
        StepVerifier.create(authGrpcService.refresh(Mono.just(validRefreshRequest)))
                .expectNextMatches(response ->
                        response.getAccessToken().equals("access-token-123") &&
                                response.getRefreshToken().equals("refresh-token-456") &&
                                response.getExpiresIn() == 900L
                )
                .verifyComplete();

        verify(authService).refresh("valid-refresh-token");
    }

    @Test
    @DisplayName("Should handle refresh with invalid token")
    void shouldHandleRefreshWithInvalidToken() {
        // Given
        when(authService.refresh(anyString()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid refresh token")));

        // When & Then
        StepVerifier.create(authGrpcService.refresh(Mono.just(validRefreshRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("UNAUTHENTICATED")
                )
                .verify();

        verify(authService).refresh("valid-refresh-token");
    }

    @Test
    @DisplayName("Should handle R2DBC exception as database unavailable")
    void shouldHandleR2dbcExceptionAsDatabaseUnavailable() {
        // Given
        // Используем конкретную реализацию R2dbcException
        when(authService.login(anyString(), anyString()))
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
        TransactionException transactionException = mock(TransactionException.class);
        when(authService.login(anyString(), anyString()))
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
        when(authService.login(anyString(), anyString()))
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
        when(authService.login(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Unexpected error")));

        // When & Then
        StepVerifier.create(authGrpcService.login(Mono.just(validLoginRequest)))
                .expectErrorMatches(error ->
                        error instanceof io.grpc.StatusRuntimeException &&
                                error.getMessage().contains("internal error")
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

        verify(authService, never()).refresh(anyString());
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

        verify(authService, never()).login(anyString(), anyString());
    }
}