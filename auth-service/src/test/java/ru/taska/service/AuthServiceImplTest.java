package ru.taska.service;

import exception.DomainException;
import exception.DomainStatus;
import org.assertj.core.api.Assertions;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.dto.AuthResponseDto;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.Credential;
import ru.taska.entity.CredentialType;
import ru.taska.entity.HashingAlgorithm;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.JwtServiceImpl;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenServiceImpl;
import ru.taska.security.config.SecurityProperties;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            Mockito.when(refreshTokenServiceImpl.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            Mockito.when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("access-token-123") &&
                                    response.getRefreshToken().equals("refresh-token-456") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            Mockito.verify(userRepository).findByEmail(email);
            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(credentialRepository).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
            Mockito.verify(passwordHashService).matches(testCredential, password);
            Mockito.verify(jwtServiceImpl).generateAccessToken(testUser);
            Mockito.verify(refreshTokenServiceImpl).createRefreshToken(testUser);
            Mockito.verify(credentialRepository, Mockito.times(1)).save(ArgumentMatchers.any(Credential.class));
        }

        @Test
        @DisplayName("Should reset failed attempts on successful login")
        void shouldResetFailedAttemptsOnSuccessfulLogin() {
            // Given
            String email = "test@example.com";
            String password = "correctPassword";
            testCredential.setFailedAttempts(3);
            testCredential.setLockedUntil(Instant.now().minus(5, ChronoUnit.MINUTES));

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            Mockito.when(credentialRepository.save(testCredential)).thenReturn(Mono.just(testCredential));
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            Mockito.when(refreshTokenServiceImpl.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            Mockito.when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(response -> response.getAccessToken() != null)
                    .verifyComplete();

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(0);
            Assertions.assertThat(testCredential.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("Should fail when user not found")
        void shouldFailWhenUserNotFound() {
            // Given
            String email = "nonexistent@example.com";
            String password = "password";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            Mockito.verify(userRepository).findByEmail(email);
            Mockito.verify(credentialRepository, Mockito.never()).findByUserIdAndCredentialType(ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when user is blocked")
        void shouldFailWhenUserIsBlocked() {
            // Given
            String email = "blocked@example.com";
            String password = "password";
            testUser.setStatus(UserStatus.BLOCKED);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));


            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            Mockito.verify(credentialRepository, Mockito.times(1)).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
        }

        @Test
        @DisplayName("Should fail when user account not activated")
        void shouldFailWhenUserNotActivated() {
            // Given
            String email = "invited@example.com";
            String password = "password";
            testUser.setStatus(UserStatus.INVITED);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should fail when password not set for user")
        void shouldFailWhenPasswordNotSet() {
            // Given
            String email = "test@example.com";
            String password = "password";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION  &&
                                    error.getMessage().equals("Email and password are required")
                    )
                    .verify();
        }

        @Test
        @DisplayName("Should increment failed attempts on wrong password")
        void shouldIncrementFailedAttemptsOnWrongPassword() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            Mockito.when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(1);
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should lock account after max failed attempts")
        void shouldLockAccountAfterMaxFailedAttempts() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";
            testCredential.setFailedAttempts(4);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            Mockito.when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            Mockito.when(securityProperties.getLockDurationMinutes()).thenReturn(15);
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid credentials")
                    )
                    .verify();

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(5);
            Assertions.assertThat(testCredential.getLockedUntil()).isNotNull();
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

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));

            // Важно: matches() НЕ должен вызываться, так как аккаунт заблокирован
            // Поэтому не мокаем passwordHashService.matches()

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().contains("Invalid credentials")
                    )
                    .verify();

            // Verify that matches was never called because account is locked
            Mockito.verify(passwordHashService, Mockito.never()).matches(ArgumentMatchers.any(), ArgumentMatchers.anyString());
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when email is blank")
        void shouldFailWhenEmailIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("", "password"))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION &&
                                    error.getMessage().equals("Email and password are required")
                    )
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findByEmail(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when password is blank")
        void shouldFailWhenPasswordIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("test@example.com", ""))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION &&
                                    error.getMessage().equals("Email and password are required")
                    )
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findByEmail(ArgumentMatchers.any());
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

            Mockito.when(refreshTokenServiceImpl.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(testRefreshTokenResponse));
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("new-access-token-123"));
            Mockito.when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(refreshToken))
                    .expectNextMatches(response ->
                            response.getAccessToken().equals("new-access-token-123") &&
                                    response.getRefreshToken().equals("new-refresh-token-789") &&
                                    response.getExpiresIn().equals(900L)
                    )
                    .verifyComplete();

            Mockito.verify(refreshTokenServiceImpl).validateAndRotate(refreshToken);
            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(jwtServiceImpl).generateAccessToken(testUser);
        }

        @Test
        @DisplayName("Should fail when refresh token is invalid")
        void shouldFailWhenRefreshTokenIsInvalid() {
            // Given
            String invalidRefreshToken = "invalid-refresh-token";

            Mockito.when(refreshTokenServiceImpl.validateAndRotate(invalidRefreshToken))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired refresh token")));

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(invalidRefreshToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired refresh token")
                    )
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findById((UUID) ArgumentMatchers.any());
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
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

            Mockito.when(refreshTokenServiceImpl.validateAndRotate(refreshToken))
                    .thenReturn(Mono.just(responseDto));
            Mockito.when(userRepository.findById(nonExistentUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.refresh(refreshToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.NOT_FOUND &&
                                    error.getMessage().equals("User not found")
                    )
                    .verify();

            Mockito.verify(userRepository).findById(nonExistentUserId);
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
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

            Mockito.verify(refreshTokenServiceImpl, Mockito.never()).validateAndRotate(ArgumentMatchers.any());
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

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            Mockito.when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectError(DomainException.class)
                    .verify();

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should handle password hash service error")
        void shouldHandlePasswordHashServiceError() {
            // Given
            String email = "test@example.com";
            String password = "password";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.matches(testCredential, password))
                    .thenReturn(Mono.error(new RuntimeException("Hash service error")));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectError(RuntimeException.class)
                    .verify();

            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should handle null email and password")
        void shouldHandleNullEmailAndPassword() {
            // When & Then
            StepVerifier.create(authServiceImpl.login(null, "password"))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION
                    )
                    .verify();

            StepVerifier.create(authServiceImpl.login("test@example.com", null))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.FAILED_PRECONDITION
                    )
                    .verify();
        }
    }
}