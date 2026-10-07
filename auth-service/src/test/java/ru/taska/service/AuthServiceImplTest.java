package ru.taska.service;

import io.jsonwebtoken.Claims;
import org.springframework.transaction.reactive.TransactionalOperator;
import ru.taska.entity.OutboxEvent;
import ru.taska.event.OutboxEventStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
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
import ru.taska.service.impl.AuthServiceImpl;
import ru.taska.util.JwtValidator;
import ru.taska.util.PasswordValidator;

import java.time.Duration;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl Unit Tests")
class AuthServiceImplTest {

    private static final String REQUEST_ID = "test-request-id";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordValidator passwordValidator;

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

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private TransactionalOperator requiresNewTransactionalOperator;

    @Mock
    private AccountLockService accountLockService;

    private UUID testUserId;
    private User testUser;
    private Credential testCredential;
    private AuthResponseDto testAuthResponse;
    private RefreshTokenResponseDto testRefreshTokenResponse;
    private InviteToken testInviteToken;
    private String testRawToken;
    private String testTokenHash;
    private AuthServiceImpl authServiceImpl;

    private final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testRawToken = "valid-invite-token-123";
        testTokenHash = "hashed-invite-token";

        testUser = User.builder()
                .id(testUserId)
                .email("test@example.com")
                .login("testuser")
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .lockedUntil(null)
                .build();

        testCredential = Credential.builder()
                .userId(testUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash("hashedPassword123")
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
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

        Mockito.lenient().when(requiresNewTransactionalOperator.transactional(Mockito.any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Mockito.lenient().when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
        Mockito.lenient().when(securityProperties.getLockDuration()).thenReturn(Duration.ofMinutes(15));

        authServiceImpl = new AuthServiceImpl(
                userRepository,
                credentialRepository,
                passwordHashService,
                jwtServiceImpl,
                refreshTokenServiceImpl,
                securityProperties,
                inviteTokenRepository,
                outboxEventRepository,
                userMapper,
                passwordValidator,
                jwtValidator,
                accountLockService,
                requiresNewTransactionalOperator
        );
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
            Mockito.when(accountLockService.resolveLockState(testUser)).thenReturn(Mono.just(testUser));
            Mockito.when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class)))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
            Mockito.when(jwtServiceImpl.generateAccessToken(testUser)).thenReturn(Mono.just("access-token-123"));
            Mockito.when(refreshTokenServiceImpl.createRefreshToken(testUser)).thenReturn(Mono.just("refresh-token-456"));
            Mockito.when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(r ->
                            "access-token-123".equals(r.getAccessToken())
                                    && "refresh-token-456".equals(r.getRefreshToken())
                                    && r.getExpiresIn().equals(900L))
                    .verifyComplete();

            Mockito.verify(userRepository).findByEmail(email);
            Mockito.verify(credentialRepository).findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD);
            Mockito.verify(accountLockService).resolveLockState(testUser);
            Mockito.verify(passwordHashService).matches(testCredential, password);
            Mockito.verify(jwtServiceImpl).generateAccessToken(testUser);
            Mockito.verify(refreshTokenServiceImpl).createRefreshToken(testUser);
        }

        @Test
        @DisplayName("Should reset failed attempts on successful login")
        void shouldResetFailedAttemptsOnSuccessfulLogin() {
            // Given
            String email = "test@example.com";
            String password = "correctPassword";
            testCredential.setFailedAttempts(3);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));

            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(accountLockService.resolveLockState(testUser)).thenReturn(Mono.just(testUser));

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
            Assertions.assertThat(testUser.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("Should fail when user not found")
        void shouldFailWhenUserNotFound() {
            // Given
            String email = "nonexistent@example.com";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, "password"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
                    .verify();

            Mockito.verify(userRepository).findByEmail(email);
            Mockito.verify(credentialRepository, Mockito.never())
                    .findByUserIdAndCredentialType(ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail when user is blocked")
        void shouldFailWhenUserIsBlocked() {
            // Given
            String email = "blocked@example.com";
            testUser.setStatus(UserStatus.BLOCKED);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, "password"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
                    .verify();

            Mockito.verify(accountLockService, Mockito.never()).resolveLockState(ArgumentMatchers.any());
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
            StepVerifier.create(authServiceImpl.login(email, "password"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
                    .verify();

            Mockito.verify(accountLockService, Mockito.never()).resolveLockState(ArgumentMatchers.any());
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
            StepVerifier.create(authServiceImpl.login(email, "password"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
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

            Mockito.when(accountLockService.resolveLockState(testUser)).thenReturn(Mono.just(testUser));

            Mockito.when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            Mockito.when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            // When & Then
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
                    .verify();

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(1);
            Mockito.verify(userRepository, Mockito.never()).save(ArgumentMatchers.any(User.class));
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should lock account after max failed attempts — lockedUntil on User")
        void shouldLockAccountAfterMaxFailedAttempts() {
            // Given
            String email = "test@example.com";
            String wrongPassword = "wrongPassword";
            testCredential.setFailedAttempts(4);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));

            Mockito.when(accountLockService.resolveLockState(testUser)).thenReturn(Mono.just(testUser));

            Mockito.when(passwordHashService.matches(testCredential, wrongPassword)).thenReturn(Mono.just(false));
            Mockito.when(securityProperties.getMaxFailedAttempts()).thenReturn(5);
            Mockito.when(securityProperties.getLockDuration()).thenReturn(Duration.ofMinutes(15));
            Mockito.when(credentialRepository.save(ArgumentMatchers.any(Credential.class))).thenReturn(Mono.just(testCredential));

            Mockito.when(userRepository.save(ArgumentMatchers.any(User.class)))
                    .thenReturn(Mono.just(testUser));

            Instant before = Instant.now();
            // When & Then
            StepVerifier.create(authServiceImpl.login(email, wrongPassword))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid credentials"))
                    .verify();
            Instant after = Instant.now();

            Duration lock = Duration.ofMinutes(15);

            Mockito.verify(credentialRepository).save(testCredential);
            Assertions.assertThat(testCredential.getFailedAttempts()).isEqualTo(5);
            Assertions.assertThat(testUser.getLockedUntil()).isNotNull();
            Assertions.assertThat(testUser.getStatus()).isEqualTo(UserStatus.LOCKED);
            Assertions.assertThat(testUser.getLockedUntil())
                    .isAfterOrEqualTo(before.plus(lock))
                    .isBeforeOrEqualTo(after.plus(lock));
            Mockito.verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("Should fail with PERMISSION_DENIED when account is locked with active window")
        void shouldFailWhenAccountIsLockedWithActiveWindow() {
            // Given
            String email = "test@example.com";
            String password = "password";

            User lockedUser = testUser.toBuilder()
                    .status(UserStatus.LOCKED)
                    .lockedUntil(NOW.plus(10, ChronoUnit.MINUTES))
                    .build();

            testCredential.setFailedAttempts(5);

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(lockedUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));

            // resolveLockState вернул LOCKED — окно активно
            Mockito.when(accountLockService.resolveLockState(lockedUser)).thenReturn(Mono.just(lockedUser));

            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.PERMISSION_DENIED
                                    && e.getMessage().contains("Account is locked until"))
                    .verify();

            Mockito.verify(passwordHashService, Mockito.never()).matches(ArgumentMatchers.any(), ArgumentMatchers.anyString());
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when email is blank")
        void shouldFailWhenEmailIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("", "password"))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.FAILED_PRECONDITION
                                    && e.getMessage().equals("Email and password are required"))
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findByEmail(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when password is blank")
        void shouldFailWhenPasswordIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.login("test@example.com", ""))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.FAILED_PRECONDITION
                                    && e.getMessage().equals("Email and password are required"))
                    .verify();

            Mockito.verify(userRepository, Mockito.never()).findByEmail(ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Locked Account Tests")
    class LockedAccountTests {

        @Test
        @DisplayName("login: LOCKED с истёкшим окном → resolveLockState вернул ACTIVE → логин успешен")
        void login_lockedExpiredWindow_loginSucceeds() {
            String email = "test@example.com";
            String password = "correctPassword";

            User lockedStale = testUser.toBuilder()
                    .status(UserStatus.LOCKED)
                    .lockedUntil(NOW.minusSeconds(60))
                    .build();
            User refreshed = testUser.toBuilder()
                    .status(UserStatus.ACTIVE)
                    .lockedUntil(null)
                    .build();

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(lockedStale));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(accountLockService.resolveLockState(lockedStale)).thenReturn(Mono.just(refreshed));
            Mockito.when(passwordHashService.matches(testCredential, password)).thenReturn(Mono.just(true));
            Mockito.when(credentialRepository.save(testCredential)).thenReturn(Mono.just(testCredential));
            //  Логика: resolveLockState уже вернул ACTIVE с lockedUntil=null,
            //  значит needUserSave=false в resetFailedAttempts, save не нужен.
            //  Раньше висел UnnecessaryStubbingException в strict-Mockito.
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(refreshed));
            Mockito.when(jwtServiceImpl.generateAccessToken(refreshed)).thenReturn(Mono.just("access"));
            Mockito.when(refreshTokenServiceImpl.createRefreshToken(refreshed)).thenReturn(Mono.just("refresh"));
            Mockito.when(jwtServiceImpl.getExpiresIn()).thenReturn(Mono.just(900L));

            StepVerifier.create(authServiceImpl.login(email, password))
                    .expectNextMatches(r -> "access".equals(r.getAccessToken()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("validateAccessToken: LOCKED с активным окном → UserContext (access-токен не отзывается)")
        void validateAccessToken_lockedActiveWindow_returnsContext() {
            Claims claims = Mockito.mock(Claims.class);
            User locked = testUser.toBuilder()
                    .status(UserStatus.LOCKED)
                    .lockedUntil(NOW.plusSeconds(600))
                    .build();

            Mockito.when(jwtValidator.validate("token")).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(testUserId.toString());
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(locked));

            StepVerifier.create(authServiceImpl.validateAccessToken("token"))
                    .expectNextMatches(ctx -> ctx.getUserId().equals(testUserId.toString()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("validateAccessToken: LOCKED с истёкшим окном → access-токен работает, UserContext")
        void validateAccessToken_lockedExpiredWindow_returnsContext() {
            Claims claims = Mockito.mock(Claims.class);
            User lockedStale = testUser.toBuilder()
                    .status(UserStatus.LOCKED)
                    .lockedUntil(NOW.minusSeconds(60))
                    .build();

            Mockito.when(jwtValidator.validate("token")).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(testUserId.toString());
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(lockedStale));

            StepVerifier.create(authServiceImpl.validateAccessToken("token"))
                    .expectNextMatches(ctx -> ctx.getUserId().equals(testUserId.toString()))
                    .verifyComplete();
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
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("Invalid or expired refresh token"))
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
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.NOT_FOUND
                                    && e.getMessage().equals("User not found"))
                    .verify();

            Mockito.verify(userRepository).findById(nonExistentUserId);
            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should propagate UNAUTHENTICATED from validateAndRotate for LOCKED user")
        void shouldPropagateUnauthenticatedFromRotateForLockedUser() {
            String refreshToken = "valid-refresh-token";

            // Внутри validateAndRotate лок/статус проверяются — здесь мокаем итоговый отказ
            Mockito.when(refreshTokenServiceImpl.validateAndRotate(refreshToken))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid credentials")));

            StepVerifier.create(authServiceImpl.refresh(refreshToken))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED)
                    .verify();

            Mockito.verify(jwtServiceImpl, Mockito.never()).generateAccessToken(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should fail with invalid argument when refresh token is blank")
        void shouldFailWhenRefreshTokenIsBlank() {
            // When & Then
            StepVerifier.create(authServiceImpl.refresh(""))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.INVALID_ARGUMENT
                                    && e.getMessage().equals("Refresh token cannot be blank"))
                    .verify();

            Mockito.verify(refreshTokenServiceImpl, Mockito.never()).validateAndRotate(ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle password hash service error")
        void shouldHandlePasswordHashServiceError() {
            // Given
            String email = "test@example.com";
            String password = "password";

            Mockito.when(userRepository.findByEmail(email)).thenReturn(Mono.just(testUser));
            Mockito.when(credentialRepository.findByUserIdAndCredentialType(testUserId, CredentialType.PASSWORD))
                    .thenReturn(Mono.just(testCredential));
            Mockito.when(accountLockService.resolveLockState(testUser)).thenReturn(Mono.just(testUser));
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
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.FAILED_PRECONDITION)
                    .verify();

            StepVerifier.create(authServiceImpl.login("test@example.com", null))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.FAILED_PRECONDITION)
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

            Mockito.when(userMapper.buildUserActivatedOutboxEvent(ArgumentMatchers.any(User.class), ArgumentMatchers.nullable(String.class)))
                    .thenReturn(mockOutboxEvent);
            Mockito.when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class)))
                    .thenReturn(Mono.just(mockOutboxEvent));

            Mockito.doNothing().when(passwordValidator).validate(newPassword);

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
                    .verifyComplete();

            Mockito.verify(passwordValidator).validate(newPassword);
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

            Mockito.when(userMapper.buildUserActivatedOutboxEvent(ArgumentMatchers.any(User.class), ArgumentMatchers.nullable(String.class)))
                    .thenReturn(mockOutboxEvent);
            Mockito.when(outboxEventRepository.save(ArgumentMatchers.any(OutboxEvent.class)))
                    .thenReturn(Mono.just(mockOutboxEvent));

            Mockito.doNothing().when(passwordValidator).validate(newPassword);

            // When & Then
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
                    .verifyComplete();

            Mockito.verify(passwordValidator).validate(newPassword);
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, nonExistentToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, null, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, "", newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, testRawToken, newPassword))
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
            StepVerifier.create(authServiceImpl.setPasswordByToken(REQUEST_ID, differentToken, newPassword))
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

    @Nested
    @DisplayName("Validate Access Token Tests")
    class ValidateAccessTokenTests {

        @Test
        @DisplayName("Should NOT query database when JWT validation fails")
        void shouldNotQueryDatabaseWhenJwtValidationFails() {
            // Given
            String invalidToken = "invalid.jwt.token";

            Mockito.when(jwtValidator.validate(invalidToken))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid JWT token")));

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(invalidToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("Invalid JWT token")
                    )
                    .verify();

            Mockito.verify(jwtValidator).validate(invalidToken);
            Mockito.verify(userRepository, Mockito.never()).findById((UUID) ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should not query database when JWT token is expired")
        void shouldNotQueryDatabaseWhenJwtTokenIsExpired() {
            // Given
            String expiredToken = "expired.jwt.token";

            Mockito.when(jwtValidator.validate(expiredToken))
                    .thenReturn(Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "JWT token expired")));

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(expiredToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("JWT token expired")
                    )
                    .verify();

            Mockito.verify(jwtValidator).validate(expiredToken);
            Mockito.verify(userRepository, Mockito.never()).findById((UUID) ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Should return UNAUTHENTICATED when user not found")
        void shouldReturnUnauthenticatedWhenUserNotFound() {
            // Given
            String accessToken = "valid.jwt.token";
            Claims claims = Mockito.mock(Claims.class);
            UUID unknownUserId = UUID.randomUUID();

            Mockito.when(jwtValidator.validate(accessToken)).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(unknownUserId.toString());
            Mockito.when(userRepository.findById(unknownUserId)).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(accessToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException &&
                                    ((DomainException) error).getStatus() == DomainStatus.UNAUTHENTICATED &&
                                    error.getMessage().equals("User not found")
                    )
                    .verify();

            Mockito.verify(jwtValidator).validate(accessToken);
            Mockito.verify(userRepository).findById(unknownUserId);
        }

        @Test
        @DisplayName("Should return UNAUTHENTICATED when user is blocked")
        void shouldReturnUnauthenticatedWhenUserIsBlocked() {
            // Given
            String accessToken = "valid.jwt.token";
            Claims claims = Mockito.mock(Claims.class);
            User blockedUser = User.builder()
                    .id(testUserId)
                    .email("blocked@example.com")
                    .login("blockeduser")
                    .status(UserStatus.BLOCKED)
                    .build();

            Mockito.when(jwtValidator.validate(accessToken)).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(testUserId.toString());
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(blockedUser));

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(accessToken))
                    .expectErrorMatches(error ->
                            error instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && error.getMessage().equals("User not found or inactive"))
                    .verify();

            Mockito.verify(jwtValidator).validate(accessToken);
            Mockito.verify(userRepository).findById(testUserId);
        }

        @Test
        @DisplayName("Should return UNAUTHENTICATED when user is invited")
        void shouldReturnUnauthenticatedWhenUserIsInvited() {
            // Given
            String accessToken = "valid.jwt.token";
            Claims claims = Mockito.mock(Claims.class);
            User invitedUser = User.builder()
                    .id(testUserId)
                    .email("invited@example.com")
                    .login("inviteduser")
                    .status(UserStatus.INVITED)
                    .build();

            Mockito.when(jwtValidator.validate(accessToken)).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(testUserId.toString());
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(invitedUser));

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(accessToken))
                    .expectErrorMatches(e ->
                            e instanceof DomainException de
                                    && de.getStatus() == DomainStatus.UNAUTHENTICATED
                                    && e.getMessage().equals("User not found or inactive")
                    )
                    .verify();

            Mockito.verify(jwtValidator).validate(accessToken);
            Mockito.verify(userRepository).findById(testUserId);
        }

        @Test
        @DisplayName("Should return correct UserContext")
        void shouldReturnCorrectUserContext() {
            // Given
            String accessToken = "valid.jwt.token";
            Claims claims = Mockito.mock(Claims.class);

            User fullUser = User.builder()
                    .id(testUserId)
                    .email("user@example.com")
                    .login("user")
                    .displayName("User Name")
                    .status(UserStatus.ACTIVE)
                    .build();

            Mockito.when(jwtValidator.validate(accessToken)).thenReturn(Mono.just(claims));
            Mockito.when(claims.getSubject()).thenReturn(testUserId.toString());
            Mockito.when(userRepository.findById(testUserId)).thenReturn(Mono.just(fullUser));

            // When & Then
            StepVerifier.create(authServiceImpl.validateAccessToken(accessToken))
                    .expectNextMatches(userContext ->
                            userContext.getUserId().equals(testUserId.toString()) &&
                                    userContext.getLogin().equals("user") &&
                                    userContext.getEmail().equals("user@example.com") &&
                                    userContext.getDisplayName().equals("User Name")
                    )
                    .verifyComplete();

            Mockito.verify(jwtValidator).validate(accessToken);
            Mockito.verify(userRepository).findById(testUserId);
        }
    }
}