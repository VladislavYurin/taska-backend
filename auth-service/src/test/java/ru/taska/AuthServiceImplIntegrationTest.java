package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.LoginRequest;
import ru.taska.api.auth.v1.LoginRequestBody;
import ru.taska.api.auth.v1.LoginResponse;
import ru.taska.api.auth.v1.PasswordByTokenRequest;
import ru.taska.api.auth.v1.PasswordByTokenRequestBody;
import ru.taska.api.auth.v1.ReactorAuthServiceGrpc;
import ru.taska.api.auth.v1.RefreshRequest;
import ru.taska.api.auth.v1.RefreshRequestBody;
import ru.taska.api.auth.v1.RefreshResponse;
import ru.taska.api.common.v1.Header;
import ru.taska.entity.Credential;
import ru.taska.entity.CredentialType;
import ru.taska.entity.HashingAlgorithm;
import ru.taska.entity.InviteToken;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.InviteTokenRepository;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.PasswordHashServiceImpl;
import ru.taska.service.AuthService;

@Slf4j
public class AuthServiceImplIntegrationTest extends AbstractIT {

    private static final String TOKEN_HASH_ALGORITHM = "SHA-256";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @Autowired
    private PasswordHashServiceImpl passwordHashServiceImpl;

    private static ManagedChannel channel;
    private static ReactorAuthServiceGrpc.ReactorAuthServiceStub authStub;

    private UUID testUserId;
    private String testEmail;
    private String testPassword;
    private String validRefreshToken;

    // Данные для тестов установки пароля
    private UUID invitedUserId;
    private String invitedUserEmail;
    private String inviteTokenRaw;
    private String inviteTokenHash;

    @BeforeAll
    static void setUp() {
        // Ждем пока Liquibase применит миграции и gRPC сервер запустится
//        try {
//            TimeUnit.SECONDS.sleep(5);
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }

        // Создаем канал к gRPC серверу
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        authStub = ReactorAuthServiceGrpc.newReactorStub(channel);
    }

    @BeforeEach
    void setupInvitedUserTestData() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        invitedUserEmail = "invited_" + uniqueSuffix + "@example.com";
        inviteTokenRaw = generateRawToken();
        inviteTokenHash = org.apache.commons.codec.digest.DigestUtils.sha256Hex(inviteTokenRaw);

        log.debug(">>> Setting up invited user test data");
        log.debug(">>> Invited user email: {}", invitedUserEmail);
        log.debug(">>> Invite token raw: {}", inviteTokenRaw);
        log.debug(">>> Invite token hash: {}", inviteTokenHash);

        User invitedUser = User.builder()
                .login("inviteduser_" + uniqueSuffix)
                .email(invitedUserEmail)
                .displayName("Invited User")
                .status(UserStatus.INVITED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        invitedUserId = userRepository.save(invitedUser)
                .block(Duration.ofSeconds(10))
                .getId();

        InviteToken inviteToken = InviteToken.builder()
                .userId(invitedUserId)
                .tokenHash(inviteTokenHash)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS))
                .usedAt(null)
                .build();

        inviteTokenRepository.save(inviteToken).block(Duration.ofSeconds(10));

        log.debug(">>> Invited user created with id: {}", invitedUserId);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @BeforeEach
    void setupTestData() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        testEmail = "test_" + uniqueSuffix + "@example.com";
        testPassword = "ValidPassword123!";

        log.debug(">>> Setting up test data");
        log.debug(">>> Test email: {}", testEmail);

        User user = User.builder()
                .login("testuser_" + uniqueSuffix)
                .email(testEmail)
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testUserId = userRepository.save(user)
                .block(Duration.ofSeconds(10))
                .getId();

        String hashedPassword = passwordHashServiceImpl.encode(testPassword, HashingAlgorithm.BCRYPT);

        Credential credential = Credential.builder()
                .userId(testUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash(hashedPassword)
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        credentialRepository.save(credential).block(Duration.ofSeconds(10));

        // Generate valid refresh token for testing
        validRefreshToken = generateAndSaveRefreshToken(testUserId);

        log.debug(">>> Valid refresh token: {}", validRefreshToken);
    }

    private String generateAndSaveRefreshToken(UUID userId) {
        String rawRefreshToken = generateRawRefreshToken();
        String refreshTokenHash = hashTokenWithSHA256(rawRefreshToken); // Используем SHA-256, а не BCrypt!

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(userId)
                .tokenHash(refreshTokenHash)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .build();

        refreshTokenRepository.save(refreshToken).block(Duration.ofSeconds(10));
        return rawRefreshToken;
    }

    private String hashTokenWithSHA256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(TOKEN_HASH_ALGORITHM);
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hashing algorithm not available", e);
        }
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @AfterEach
    void cleanTestData() {
        log.debug(">>> Cleaning up test data for user: {}", testEmail);

        if (testUserId != null) {
            try {
                refreshTokenRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                credentialRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                userRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                log.debug(">>> Cleanup completed");
            } catch (Exception e) {
                log.error("Cleanup error: {}", e.getMessage());
            }
        }

        // Clean up invited user data
        if (invitedUserId != null) {
            try {
                // Сначала находим и удаляем invite token по userId
                inviteTokenRepository.findByUserId(invitedUserId)
                                     .flatMap(token -> inviteTokenRepository.deleteById(token.getId()))
                                     .block(Duration.ofSeconds(5));
                // Удаляем credential если он был создан
                credentialRepository.findByUserIdAndCredentialType(invitedUserId, CredentialType.PASSWORD)
                                    .flatMap(cred -> credentialRepository.deleteById(cred.getId()))
                                    .block(Duration.ofSeconds(5));
                // Удаляем пользователя
                userRepository.deleteById(invitedUserId).block(Duration.ofSeconds(5));
                log.debug(">>> Invited user cleanup completed");
            } catch (Exception e) {
                log.error("Invited user cleanup error: {}", e.getMessage());
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("Verify database schema is properly initialized")
    void testDatabaseSchemaInitialized() {
        // Проверяем, что таблица users существует и можно выполнить запрос
        Long userCount = userRepository.count().block(Duration.ofSeconds(8));
        Assertions.assertThat(userCount).isNotNull();
        log.info("Database schema is properly initialized, users count: {}", userCount);

        // Проверяем, что таблица credentials существует
        Long credentialsCount = credentialRepository.count().block(Duration.ofSeconds(5));
        Assertions.assertThat(credentialsCount).isNotNull();
        log.info("Credentials table exists, count: {}", credentialsCount);

        // Проверяем, что таблица refresh_tokens существует
        Long refreshTokensCount = refreshTokenRepository.count().block(Duration.ofSeconds(5));
        Assertions.assertThat(refreshTokensCount).isNotNull();
        log.info("Refresh tokens table exists, count: {}", refreshTokensCount);
    }

    @Test
    @DisplayName("Should successfully login with valid credentials")
    void testSuccessfulLogin() {
        LoginRequest request = LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-1")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(testEmail)
                        .setPassword(testPassword)
                        .build())
                .build();

        LoginResponse response = authStub.login(Mono.just(request)).block(Duration.ofSeconds(10));

        Assertions.assertThat(response).isNotNull();
        Assertions.assertThat(response.getAccessToken()).isNotNull();
        Assertions.assertThat(response.getAccessToken()).isNotEmpty();
        Assertions.assertThat(response.getRefreshToken()).isNotNull();
        Assertions.assertThat(response.getRefreshToken()).isNotEmpty();
        Assertions.assertThat(response.getExpiresIn()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should successfully refresh token with valid refresh token")
    void testSuccessfulRefresh() {
        RefreshRequest request = RefreshRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-2")
                        .setNodeId("test-node")
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken(validRefreshToken)
                        .build())
                .build();

        RefreshResponse response = authStub.refresh(Mono.just(request)).block(Duration.ofSeconds(10));

        Assertions.assertThat(response).isNotNull();
        Assertions.assertThat(response.getAccessToken()).isNotNull();
        Assertions.assertThat(response.getAccessToken()).isNotEmpty();
        Assertions.assertThat(response.getRefreshToken()).isNotNull();
        Assertions.assertThat(response.getRefreshToken()).isNotEmpty();
        Assertions.assertThat(response.getExpiresIn()).isGreaterThan(0);
        Assertions.assertThat(response.getRefreshToken()).isNotEqualTo(validRefreshToken);
    }

    @Test
    @DisplayName("Should fail login with invalid password")
    void testFailedLoginInvalidPassword() {
        LoginRequest request = LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-3")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(testEmail)
                        .setPassword("wrongPassword123!")
                        .build())
                .build();

        Assertions.assertThatThrownBy(() -> authStub.login(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    Assertions.assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("UNAUTHENTICATED");
                });
    }

    @Test
    @DisplayName("Should fail login with non-existent email")
    void testFailedLoginNonExistentEmail() {
        LoginRequest request = LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-4")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail("nonexistent_" + UUID.randomUUID() + "@example.com")
                        .setPassword("somePassword123!")
                        .build())
                .build();

        Assertions.assertThatThrownBy(() -> authStub.login(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    Assertions.assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("UNAUTHENTICATED");
                });
    }

    @Test
    @DisplayName("Should fail refresh with invalid refresh token")
    void testFailedRefreshInvalidToken() {
        RefreshRequest request = RefreshRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-5")
                        .setNodeId("test-node")
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken("invalid.refresh.token.123")
                        .build())
                .build();

        Assertions.assertThatThrownBy(() -> authStub.refresh(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    Assertions.assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("UNAUTHENTICATED");
                });
    }

    @Test
    @DisplayName("Should NOT issue refresh token when user is blocked during login")
    void testRefreshTokenNotIssuedForBlockedUserDuringLogin() {
        // Given: Создаем заблокированного пользователя
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String blockedUserEmail = "blocked_user_" + uniqueSuffix + "@example.com";
        String blockedUserPassword = "ValidPassword123!";

        User blockedUser = User.builder()
                .login("blockeduser_" + uniqueSuffix)
                .email(blockedUserEmail)
                .displayName("Blocked Test User")
                .status(UserStatus.BLOCKED)  // Пользователь заблокирован
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        UUID blockedUserId = userRepository.save(blockedUser)
                .block(Duration.ofSeconds(10))
                .getId();

        // Создаем credentials для заблокированного пользователя
        String hashedPassword = passwordHashServiceImpl.encode(blockedUserPassword, HashingAlgorithm.BCRYPT);

        Credential credential = Credential.builder()
                .userId(blockedUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash(hashedPassword)
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        credentialRepository.save(credential).block(Duration.ofSeconds(10));

        log.debug("Created blocked user: {} with id: {}", blockedUserEmail, blockedUserId);

        // When: Пытаемся выполнить логин для заблокированного пользователя
        LoginRequest loginRequest = LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-blocked-login")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(blockedUserEmail)
                        .setPassword(blockedUserPassword)
                        .build())
                .build();

        // Then: Логин должен провалиться и не выдать refresh token
        Assertions.assertThatThrownBy(() -> authStub.login(Mono.just(loginRequest)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    Assertions.assertThat(statusEx.getStatus().getCode().toString())
                            .isEqualTo("UNAUTHENTICATED");
                    Assertions.assertThat(statusEx.getMessage())
                            .contains("Invalid credentials");
                    log.info("Login for blocked user correctly failed with: {}", statusEx.getMessage());
                });

        // Проверяем, что refresh token не был создан в БД для заблокированного пользователя
        Long refreshTokenCount = refreshTokenRepository.countByUserId(blockedUserId).block(Duration.ofSeconds(5));
        Assertions.assertThat(refreshTokenCount).isEqualTo(0);
        log.info("Verified that no refresh token was created for blocked user");

    }

    @Test
    @DisplayName("Should generate consistent SHA-256 hash for same token")
    void testSha256HashConsistency() {
        String token = "test-token-123";
        String hash1 = hashTokenWithSHA256(token);
        String hash2 = hashTokenWithSHA256(token);

        Assertions.assertThat(hash1).isEqualTo(hash2);

        String hash3 = hashTokenWithSHA256("different-token");
        Assertions.assertThat(hash1).isNotEqualTo(hash3);
    }

    @Test
    @DisplayName("Should successfully set password for invited user with valid token")
    void testSuccessfulSetPasswordByToken() {
        String newPassword = "NewSecurePassword456!";

        PasswordByTokenRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-set-pwd")
                        .setNodeId("test-node")
                        .build())
                .setBody(PasswordByTokenRequestBody.newBuilder()
                        .setToken(inviteTokenRaw)
                        .setNewPassword(newPassword)
                        .build())
                .build();

        authService.setPasswordByToken("test-req-set-pwd", inviteTokenRaw, newPassword).block();

        // Verify user status changed to ACTIVE
        User updatedUser = userRepository.findById(invitedUserId).block(Duration.ofSeconds(5));
        Assertions.assertThat(updatedUser).isNotNull();
        Assertions.assertThat(updatedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);

        // Verify credential was created/updated
        Credential credential = credentialRepository
                .findByUserIdAndCredentialType(invitedUserId, CredentialType.PASSWORD)
                .block(Duration.ofSeconds(5));
        Assertions.assertThat(credential).isNotNull();
        Assertions.assertThat(credential.getSecretHash()).isNotNull();

        // Verify password works for login
        LoginRequest loginRequest = LoginRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-login-after-set")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(invitedUserEmail)
                        .setPassword(newPassword)
                        .build())
                .build();

        LoginResponse loginResponse = authStub.login(Mono.just(loginRequest)).block(Duration.ofSeconds(10));
        Assertions.assertThat(loginResponse).isNotNull();
        Assertions.assertThat(loginResponse.getAccessToken()).isNotNull();

        // Verify token was marked as used
        InviteToken usedToken = inviteTokenRepository.findByTokenHash(inviteTokenHash).block(Duration.ofSeconds(5));
        Assertions.assertThat(usedToken).isNotNull();
        Assertions.assertThat(usedToken.getUsedAt()).isNotNull();
    }
}