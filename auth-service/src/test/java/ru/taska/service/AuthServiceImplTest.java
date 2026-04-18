package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
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
import ru.taska.dto.AuthResponseDto;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.*;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.JwtServiceImpl;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenServiceImpl;
import ru.taska.security.config.SecurityProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl Unit Tests")
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private PasswordHashService passwordHashService;

    @Mock
    private JwtServiceImpl jwtServiceImpl;

    @Mock
    private RefreshTokenServiceImpl refreshTokenServiceImpl;

    @Mock
    private SecurityProperties securityProperties;

    @InjectMocks
    private AuthServiceImpl authServiceImpl;

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
            when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            when(refreshTokenServiceImpl.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("access-token-123") &&
                                    response.getRefreshToken().equals("refresh-token-456") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            verify(userRepository).findByEmail(email);
            verify(userRepository).findById(testUserId);
            verify(credentialRepository).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
            verify(passwordHashService).matches(testCredential, password);
            verify(jwtServiceImpl).generateAccessToken(testUser);
            verify(refreshTokenServiceImpl).createRefreshToken(testUser);
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
            when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            when(refreshTokenServiceImpl.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(response -> response.getAccessToken() != null)
                    .verifyComplete();

            verify(credentialRepository).save(testCredential);
            assertThat(testCredential.getFailedAttempts()).isEqualTo(0);
            assertThat(testCredential.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("Should fail when user not found")
        void shouldFailWhenUserNotFound() {
            // Given
            String email = "nonexistent@example.com";
            String password = "password";

            when(userRepository.findByEmail(email)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND &&
                                    error.getMessage().equals("User not found")
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
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.PERMISSION_DENIED &&
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
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION &&
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
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION &&
                                    error.getMessage().equals("Password not set for user")
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
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            verify(credentialRepository).save(testCredential);
            assertThat(testCredential.getFailedAttempts()).isEqualTo(1);
            verify(jwtServiceImpl, never()).generateAccessToken(any());
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
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            verify(credentialRepository).save(testCredential);
            assertThat(testCredential.getFailedAttempts()).isEqualTo(5);
            assertThat(testCredential.getLockedUntil()).isNotNull();
        }

        @Test
        @DisplayName("Should fail when account is locked")
        void shouldFailWhenAccountIsLocked() {
            // Given
            String email = "test@example.com";
            String password = "password";

            // Устанавливаем lockedUntil в будущее
            testCredential.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
            testCredential.setFailedAttempts(5);

            when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));

            // Важно: matches() НЕ должен вызываться, так как аккаунт заблокирован
            // Поэтому не мокаем passwordHashService.matches()

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.PERMISSION_DENIED &&
                                    error.getMessage().contains("Account is locked until")
                    )
                    .verify();

            // Verify that matches was never called because account is locked
            verify(passwordHashService, never()).matches(any(), anyString());
            verify(jwtServiceImpl, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when email is blank")
        void shouldFailWhenEmailIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("", "password"))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                    error.getMessage().equals("Email cannot be blank")
                    )
                    .verify();

            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when password is blank")
        void shouldFailWhenPasswordIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("test@example.com", ""))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                    error.getMessage().equals("Password cannot be blank")
                    )
                    .verify();

            verify(userRepository, never()).findByEmail(any());
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

            when(refreshTokenServiceImpl.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(testRefreshTokenResponse));
            when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("new-access-token-123"));
            when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(refreshToken))
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("new-access-token-123") &&
                                    response.getRefreshToken().equals("new-refresh-token-789") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            verify(refreshTokenServiceImpl).validateAndRotate(refreshToken);
            verify(userRepository).findById(testUserId);
            verify(jwtServiceImpl).generateAccessToken(testUser);
        }

        @Test
        @DisplayName("Should fail when refresh token is invalid")
        void shouldFailWhenRefreshTokenIsInvalid() {
            // Given
            String invalidRefreshToken = "invalid-refresh-token";

            when(refreshTokenServiceImpl.validateAndRotate(invalidRefreshToken))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired refresh token")));

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(invalidRefreshToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired refresh token")
                    )
                    .verify();

            verify(userRepository, never()).findById((UUID) any());
            verify(jwtServiceImpl, never()).generateAccessToken(any());
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

            when(refreshTokenServiceImpl.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(responseDto));
            when(userRepository.findById(nonExistentUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(refreshToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND &&
                                    error.getMessage().equals("User not found")
                    )
                    .verify();

            verify(userRepository).findById(nonExistentUserId);
            verify(jwtServiceImpl, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when refresh token is blank")
        void shouldFailWhenRefreshTokenIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.refresh(""))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                    error.getMessage().equals("Refresh token cannot be blank")
                    )
                    .verify();

            verify(refreshTokenServiceImpl, never()).validateAndRotate(any());
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
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectError(DomainException.class)
                    .verify();

            verify(credentialRepository).save(testCredential);
            assertThat(testCredential.getFailedAttempts()).isEqualTo(1);
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
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectError(RuntimeException.class)
                    .verify();

            verify(jwtServiceImpl, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("Should handle null email and password")
        void shouldHandleNullEmailAndPassword() {
            // When & Then
            StepVerifier.create(authServiceImpl.login(null, "password"))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT
                    )
                    .verify();

            StepVerifier.create(authServiceImpl.login("test@example.com", null))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT
                    )
                    .verify();
        }
    }
}