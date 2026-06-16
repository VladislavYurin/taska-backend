package ru.taska.service;

import ru.taska.entity.OutboxEvent;
import ru.taska.entity.OutboxEventStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
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
import ru.taska.entity.InviteToken;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.mapper.UserMapper;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.JwtServiceImpl;
import ru.taska.security.PasswordHashService;
import ru.taska.security.RefreshTokenServiceImpl;
import ru.taska.security.config.SecurityProperties;
import ru.taska.util.PasswordPolicyValidator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl Unit Tests")
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordPolicyValidator passwordPolicyValidator;

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

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private AuthServiceImpl authServiceImpl;

    private UUID testUserId;
    private User testUser;
    private Credential testCredential;
    private AuthResponseDto testAuthResponse;
    private RefreshTokenResponseDto testRefreshTokenResponse;
    private InviteToken testInviteToken;
    private String testRawToken;
    private String testTokenHash;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testRawToken = "valid-invite-token-123";
        testTokenHash = "hashed-invite-token";

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
        testInviteToken = InviteToken.builder()
                .id(UUID.randomUUID())
                .userId(testUserId)
                .tokenHash(testTokenHash)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .usedAt(null)
                .build();
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

    @Nested
    @DisplayName("Set Password By Token Tests")
    class SetPasswordByTokenTests {
        private final String newPassword = "NewValidPassword123!";
        private User invitedUser;
        private InviteToken validInviteToken;
        private String validTokenHash;

        @BeforeEach
        void setUpSetPasswordTests() {
            // Вычисляем реальный хэш от testRawToken
            validTokenHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(testRawToken);
            // Создаём отдельного пользователя со статусом INVITED для тестов установки пароля
            invitedUser = User.builder()
                    .id(testUserId)
                    .email("invited@example.com")
                    .login("inviteduser")
                    .status(UserStatus.INVITED)
                    .build();

            validInviteToken = InviteToken.builder()
                    .id(UUID.randomUUID())
                    .userId(testUserId)
                    .tokenHash(validTokenHash )
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                    .usedAt(null)
                    .build();
        }

        @Test
        @DisplayName("Should successfully set password for invited user")
        void shouldSuccessfullySetPasswordForInvitedUser() {
            // Given
            OutboxEvent mockOutboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("user")
                    .aggregateId(testUserId)
                    .eventType("UserActivated")
                    .status(OutboxEventStatus.NEW)
                    .attempts(0)
                    .build();

            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));

            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(validInviteToken));
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.just(invitedUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.empty());
            Mockito.when(passwordHashService.encode(newPassword, HashingAlgorithm.BCRYPT))
                    .thenReturn("newHashedPassword");
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class)))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenReturn(Mono.just(invitedUser));

            Mockito.when(userMapper.buildUserActivatedOutboxEvent(ArgumentMatchers.any(User.class)))
                    .thenReturn(mockOutboxEvent);
            Mockito.when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class)))
                    .thenReturn(Mono.just(mockOutboxEvent));

            Mockito.doNothing().when(passwordPolicyValidator).validate(newPassword);

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .verifyComplete();

            Mockito.verify(passwordPolicyValidator).validate(newPassword);
            Mockito.verify(inviteTokenRepository).findByTokenHash(ArgumentMatchers.anyString());
            Mockito.verify(userRepository).findById(testUserId);
            Mockito.verify(credentialRepository).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
            Mockito.verify(passwordHashService).encode(newPassword, HashingAlgorithm.BCRYPT);
            Mockito.verify(credentialRepository).save(ArgumentMatchers.any(Credential.class));
            Mockito.verify(userRepository).save(ArgumentMatchers.any(User.class));

            Assertions.assertThat(invitedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should successfully set password when credential already exists")
        void shouldSuccessfullySetPasswordWhenCredentialExists() {
            // Given
            OutboxEvent mockOutboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("user")
                    .aggregateId(testUserId)
                    .eventType("UserActivated")
                    .status(OutboxEventStatus.NEW)
                    .attempts(0)
                    .build();

            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));
            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(validInviteToken));
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.just(invitedUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(passwordHashService.encode(newPassword, HashingAlgorithm.BCRYPT))
                    .thenReturn("newHashedPassword");
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class)))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenReturn(Mono.just(invitedUser));

            Mockito.when(userMapper.buildUserActivatedOutboxEvent(ArgumentMatchers.any(User.class)))
                    .thenReturn(mockOutboxEvent);
            Mockito.when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class)))
                    .thenReturn(Mono.just(mockOutboxEvent));

            Mockito.doNothing().when(passwordPolicyValidator).validate(newPassword);

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .verifyComplete();

            Mockito.verify(passwordPolicyValidator).validate(newPassword);
            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getSecretHash()).isEqualTo("newHashedPassword");
        }

        @Test
        @DisplayName("Should fail when token is already used")
        void shouldFailWhenTokenAlreadyUsed() {
            // Given
            testInviteToken.setUsedAt(Instant.now().minus(1, ChronoUnit.DAYS));

            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                            .thenReturn(Mono.just(0));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findById(ArgumentMatchers.any(UUID.class));
            Mockito.verify(credentialRepository, Mockito.never()).save(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when token is expired")
        void shouldFailWhenTokenIsExpired() {
            // Given
            // markTokenAsUsedIfValid возвращает 0 (токен истёк, условие expires_at > NOW() не выполняется)
            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(0));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            // Проверяем, что findByTokenHash НЕ вызывался
            Mockito.verify(inviteTokenRepository, Mockito.never())
                    .findByTokenHash(ArgumentMatchers.anyString());
            Mockito.verify(userRepository, Mockito.never())
                    .findById(ArgumentMatchers.any(UUID.class));
            Mockito.verify(credentialRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Credential.class));
        }

        @Test
        @DisplayName("Should fail when user is not in INVITED status (already ACTIVE)")
        void shouldFailWhenUserIsAlreadyActive() {
            // Given
            User activeUser = User.builder()
                    .id(testUserId)
                    .email("active@example.com")
                    .login("activeuser")
                    .status(UserStatus.ACTIVE)  // ACTIVE, не INVITED
                    .build();

            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));
            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(validInviteToken));  // Используем validInviteToken
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.just(activeUser));       // Используем activeUser

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            Mockito.verify(credentialRepository, Mockito.never()).findByUserIdAndCredentialType(ArgumentMatchers.any(), ArgumentMatchers.any());
            Mockito.verify(credentialRepository, Mockito.never()).save(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when user is BLOCKED")
        void shouldFailWhenUserIsBlocked() {
            // Given
            testUser.setStatus(UserStatus.BLOCKED);

            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));
            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(testInviteToken));
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.just(testUser));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            Mockito.verify(credentialRepository, Mockito.never()).findByUserIdAndCredentialType(ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when invite token not found")
        void shouldFailWhenInviteTokenNotFound() {
            // Given
            String nonExistentToken = "non-existent-token";
            // markTokenAsUsedIfValid возвращает 0 (токен не найден)
            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(0));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(nonExistentToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findById(ArgumentMatchers.any(UUID.class));
        }

        @Test
        @DisplayName("Should fail when user not found")
        void shouldFailWhenUserNotFound() {
            // Given
            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));
            // Используем validInviteToken, который связан с invitedUser
            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(validInviteToken));  // Изменено на validInviteToken
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            Mockito.verify(credentialRepository, Mockito.never()).save(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when token is null")
        void shouldFailWhenTokenIsNull() {
            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(null, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                    error.getMessage().equals("Token required")
                    )
                    .verify();

            Mockito.verify(inviteTokenRepository, Mockito.never()).findByTokenHash(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when token is blank")
        void shouldFailWhenTokenIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken("", newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.INVALID_ARGUMENT &&
                                    error.getMessage().equals("Token required")
                    )
                    .verify();

            Mockito.verify(inviteTokenRepository, Mockito.never()).findByTokenHash(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should handle password hash service error during set password")
        void shouldHandlePasswordHashServiceErrorDuringSetPassword() {
            // Given
            // markTokenAsUsedIfValid должна вернуть 1 (токен успешно заблокирован)
            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(1));

            Mockito.when(inviteTokenRepository.findByTokenHash(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(validInviteToken));
            Mockito.when(userRepository.findById(testUserId))
                    .thenReturn(Mono.just(invitedUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.empty());
            Mockito.when(passwordHashService.encode(newPassword, HashingAlgorithm.BCRYPT))
                    .thenThrow(new RuntimeException("Hash service error"));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(testRawToken, newPassword))
                    .expectError(RuntimeException.class)
                    .verify();

            // Проверяем, что encode был вызван
            Mockito.verify(passwordHashService).encode(newPassword, HashingAlgorithm.BCRYPT);

            // Проверяем, что сохранения НЕ вызывались
            Mockito.verify(credentialRepository, Mockito.never())
                    .save(ArgumentMatchers.any(Credential.class));
            Mockito.verify(userRepository, Mockito.never())
                    .save(ArgumentMatchers.any(User.class));
            Mockito.verify(outboxEventRepository, Mockito.never())
                    .save(ArgumentMatchers.any(OutboxEvent.class));
        }

        @Test
        @DisplayName("Should fail when token hash is invalid")
        void shouldFailWhenTokenHashIsInvalid() {
            // Given - токен не существует в БД
            String differentToken = "different-token";

            // markTokenAsUsedIfValid возвращает 0 (токен не найден или уже использован)
            Mockito.when(inviteTokenRepository.markTokenAsUsedIfValid(ArgumentMatchers.anyString()))
                    .thenReturn(Mono.just(0));

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(differentToken, newPassword))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid or expired token")
                    )
                    .verify();

            // Проверяем, что findByTokenHash НЕ вызывался (так как mark вернул 0)
            Mockito.verify(inviteTokenRepository, Mockito.never())
                    .findByTokenHash(ArgumentMatchers.anyString());
            Mockito.verify(userRepository, Mockito.never())
                    .findById(ArgumentMatchers.any(UUID.class));
        }
    }
}