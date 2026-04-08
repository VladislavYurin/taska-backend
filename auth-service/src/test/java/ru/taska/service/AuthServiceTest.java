package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.SecurityProperties;
import ru.taska.domain.*;
import ru.taska.dto.AuthResponseDto;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.exception.AuthException;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private PasswordHashService passwordHashService;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private SecurityProperties securityProperties;

    @InjectMocks
    private AuthService authService;

    private UUID testUserId;
    private User testUser;
    private Credential testCredential;
    private AuthResponseDto testAuthResponse;
    private RefreshTokenResponseDto testRefreshTokenResponse;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();

        testUser = User.builder()
                .id(testUserId)
                .email("test@example.com")
                .login("testuser")
                .status(UserStatus.ACTIVE)
                .build();

        testCredential = Credential.builder()
                .userId(testUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash("hashedPassword123")
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .lockedUntil(null)
                .build();

        testAuthResponse = AuthResponseDto.builder()
                .accessToken("access-token-123")
                .refreshToken("refresh-token-456")
                .expiresIn(900L)
                .build();

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(testUserId)
                .tokenHash("hashed-refresh-token")
                .build();

        testRefreshTokenResponse = new RefreshTokenResponseDto(refreshToken, "new-refresh-token-789");
    }

    @Nested
    @DisplayName("Login Tests")
    class LoginTests {

        @Test
        @DisplayName("Should successfully login with valid credentials")
        void shouldSuccessfullyLoginWithValidCredentials() {
            // Given
            String email = "test@example.com";
            String password = "correctPassword";

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));

            when(jwtService.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            when(refreshTokenService.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            when(jwtService.getExpiresIn()).thenReturn(Mono.just(900L));

            StepVerifier.create(authService.login(email, password))
                    // Then
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("access-token-123") &&
                                    response.getRefreshToken().equals("refresh-token-456") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            verify(userRepository).findByEmail(email);
            verify(userRepository).findById(testUserId); // Добавить проверку
            verify(credentialRepository).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
            verify(passwordHashService).matches(testCredential, password);
            verify(jwtService).generateAccessToken(testUser);
            verify(refreshTokenService).createRefreshToken(testUser);
            verify(credentialRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reset failed attempts on successful login")
        void shouldResetFailedAttemptsOnSuccessfulLogin() {
            // Given
            String email = "test@example.com";
            String password = "correctPassword";
            testCredential.setFailedAttempts(3);
            testCredential.setLockedUntil(Instant.now().minus(5, ChronoUnit.MINUTES));

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            when(credentialRepository.save(testCredential)).thenReturn(Mono.just(testCredential));

            when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));

            when(jwtService.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            when(refreshTokenService.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            when(jwtService.getExpiresIn()).thenReturn(Mono.just(900L));

            StepVerifier.create(authService.login(email, password))
                    .expectNextMatches(response -> response.getAccessToken() != null)
                    .verifyComplete();

            verify(credentialRepository).save(testCredential);
            assert testCredential.getFailedAttempts() == 0;
            assert testCredential.getLockedUntil() == null;
        }

        @Test
        @DisplayName("Should fail when user not found")
        void shouldFailWhenUserNotFound() {
            // Given
            String email = "nonexistent@example.com";
            String password = "password";

            when(userRepository.findByEmail(email)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authService.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            verify(userRepository).findByEmail(email);
            verify(credentialRepository, never()).findByUserIdAndCredentialType(any(), any());
        }

        @Test
        @DisplayName("Should fail when user is blocked")
        void shouldFailWhenUserIsBlocked() {
            // Given
            String email = "blocked@example.com";
            String password = "password";
            testUser.setStatus(UserStatus.BLOCKED);

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));

            // When & Then
            StepVerifier.create(authService.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Account is blocked")
                    )
                    .verify();

            verify(credentialRepository, never()).findByUserIdAndCredentialType(any(), any());
        }

        @Test
        @DisplayName("Should fail when user account not activated")
        void shouldFailWhenUserNotActivated() {
            // Given
            String email = "invited@example.com";
            String password = "password";
            testUser.setStatus(UserStatus.INVITED);

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));

            // When & Then
            StepVerifier.create(authService.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Account not activated")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should fail when password not set for user")
        void shouldFailWhenPasswordNotSet() {
            // Given
            String email = "test@example.com";
            String password = "password";

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authService.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Password not set")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should increment failed attempts on wrong password")
        void shouldIncrementFailedAttemptsOnWrongPassword() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            when(credentialRepository.save(any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authService.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            verify(credentialRepository).save(testCredential);
            assert testCredential.getFailedAttempts() == 1;
            verify(jwtService, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("Should lock account after max failed attempts")
        void shouldLockAccountAfterMaxFailedAttempts() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";
            testCredential.setFailedAttempts(4);

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            when(securityProperties.getLockDurationMinutes()).thenReturn(15);
            when(credentialRepository.save(any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authService.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            verify(credentialRepository).save(testCredential);
            assert testCredential.getFailedAttempts() == 5;
            assert testCredential.getLockedUntil() != null;
        }
    }

    @Nested
    @DisplayName("Refresh Token Tests")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should successfully refresh token")
        void shouldSuccessfullyRefreshToken() {
            // Given
            String refreshToken = "valid-refresh-token";

            when(refreshTokenService.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(testRefreshTokenResponse));
            when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            when(jwtService.generateAccessToken(testUser)).thenReturn(Mono.just("new-access-token-123"));
            when(jwtService.getExpiresIn()).thenReturn(Mono.just(900L));

            // When
            StepVerifier.create(authService.refresh(refreshToken))
                    // Then
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("new-access-token-123") &&
                                    response.getRefreshToken().equals("new-refresh-token-789") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            verify(refreshTokenService).validateAndRotate(refreshToken);
            verify(userRepository).findById(testUserId);
            verify(jwtService).generateAccessToken(testUser);
        }

        @Test
        @DisplayName("Should fail when refresh token is invalid")
        void shouldFailWhenRefreshTokenIsInvalid() {
            // Given
            String invalidRefreshToken = "invalid-refresh-token";

            when(refreshTokenService.validateAndRotate(invalidRefreshToken))
                    .thenReturn(Mono.error(new RuntimeException("Invalid or expired refresh token")));

            // When & Then
            StepVerifier.create(authService.refresh(invalidRefreshToken))
                    .expectErrorMatches(error ->
                            error instanceof RuntimeException &&
                                    error.getMessage().equals("Invalid or expired refresh token")
                    )
                    .verify();

            verify(userRepository, never()).findById((UUID) any());
            verify(jwtService, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("Should fail when user not found during refresh")
        void shouldFailWhenUserNotFoundDuringRefresh() {
            // Given
            String refreshToken = "valid-refresh-token";
            UUID nonExistentUserId = UUID.randomUUID();

            RefreshToken refreshTokenEntity = RefreshToken.builder()
                    .userId(nonExistentUserId)
                    .tokenHash("hash")
                    .build();

            RefreshTokenResponseDto responseDto = new RefreshTokenResponseDto(refreshTokenEntity, "new-token");

            when(refreshTokenService.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(responseDto));
            when(userRepository.findById(nonExistentUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authService.refresh(refreshToken))
                    .expectErrorMatches(error ->
                            error instanceof AuthException &&
                                    error.getMessage().equals("User not found")
                    )
                    .verify();

            verify(userRepository).findById(nonExistentUserId);
            verify(jwtService, never()).generateAccessToken(any());
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle null failed attempts")
        void shouldHandleNullFailedAttempts() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";
            testCredential.setFailedAttempts(null);

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            when(credentialRepository.save(any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authService.login(email, wrongPassword))
                    .expectError(AuthException.class)
                    .verify();

            verify(credentialRepository).save(testCredential);
            assert testCredential.getFailedAttempts() == 1;
        }

        @Test
        @DisplayName("Should handle password hash service error")
        void shouldHandlePasswordHashServiceError() {
            // Given
            String email = "test@example.com";
            String password = "password";

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            when(passwordHashService.matches(testCredential, password))
                    .thenReturn(Mono.error(new RuntimeException("Hash service error")));

            // When & Then
            StepVerifier.create(authService.login(email, password))
                    .expectError(RuntimeException.class)
                    .verify();

            verify(jwtService, never()).generateAccessToken(any());
        }
    }
}