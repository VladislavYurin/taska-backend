package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.taska.domain.Credential;
import ru.taska.domain.CredentialType;
import ru.taska.domain.HashingAlgorithm;
import ru.taska.domain.RefreshToken;
import ru.taska.domain.User;
import ru.taska.domain.UserStatus;
import ru.taska.grpc.AuthServiceGrpc;
import ru.taska.grpc.LoginRequest;
import ru.taska.grpc.LoginResponse;
import ru.taska.grpc.RefreshRequest;
import ru.taska.grpc.RefreshResponse;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.PasswordHashService;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")  // Будет использовать application-test.yml
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthServiceIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordHashService passwordHashService;

    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub authStub;

    private UUID testUserId;
    private String testEmail;
    private String testPassword;
    private String validRefreshToken;

    @BeforeAll
    void setUp() {
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        authStub = AuthServiceGrpc.newBlockingStub(channel);

        setupTestData();
    }

    private void setupTestData() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        testEmail = "test_" + uniqueSuffix + "@example.com";
        testPassword = "ValidPassword123!";

        System.out.println(">>> =========================================");
        System.out.println(">>> Setting up test data");
        System.out.println(">>> Unique suffix: " + uniqueSuffix);
        System.out.println(">>> Test email: " + testEmail);

        // Создаем пользователя
        User user = User.builder()
                .login("testuser_" + uniqueSuffix)
                .email(testEmail)
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testUserId = Objects.requireNonNull(userRepository.save(user)
                        .doOnNext(saved -> System.out.println(">>> User saved with ID: " + saved.getId()))
                        .block())
                .getId();

        String hashedPassword = passwordHashService.encode(testPassword, HashingAlgorithm.BCRYPT);

        Credential credential = Credential.builder()
                .userId(testUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash(hashedPassword)
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        credentialRepository.save(credential)
                .doOnNext(saved -> System.out.println(">>> Credential saved with ID: " + saved.getId()))
                .block();

        // Создаем refresh token - используем блокировку с задержкой
        String rawRefreshToken = generateRawRefreshToken();
        String refreshTokenHash = passwordHashService.encode(rawRefreshToken, HashingAlgorithm.BCRYPT);

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(testUserId)
                .tokenHash(refreshTokenHash)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .build();

        RefreshToken savedToken = refreshTokenRepository.save(refreshToken)
                .doOnNext(saved -> System.out.println(">>> Refresh token saved with ID: " + saved.getId()))
                .block(Duration.ofSeconds(5));

        if (savedToken == null) {
            throw new RuntimeException("Failed to save refresh token");
        }

        validRefreshToken = rawRefreshToken;

        System.out.println(">>> Raw refresh token: " + validRefreshToken);

        RefreshToken found = refreshTokenRepository.findById(savedToken.getId()).block(Duration.ofSeconds(2));
        if (found != null) {
            System.out.println(">>> ✅ Verified: token found in DB by ID");
        } else {
            System.err.println(">>> ❌ ERROR: token NOT found in DB after save!");
        }
        System.out.println(">>> =========================================");
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @AfterAll
    void tearDown() {
        // Очищаем данные после всех тестов
        System.out.println(">>> Cleaning up test data for user: " + testEmail);

        if (testUserId != null) {
            try {
                refreshTokenRepository.deleteById(testUserId).block(Duration.ofSeconds(2));
                credentialRepository.deleteById(testUserId).block(Duration.ofSeconds(2));
                userRepository.deleteById(testUserId).block(Duration.ofSeconds(2));
                System.out.println(">>> ✅ Cleanup completed");
            } catch (Exception e) {
                System.err.println(">>> Cleanup error: " + e.getMessage());
            }
        }

        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }

    @Test
    @DisplayName("Should successfully login with valid credentials")
    void testSuccessfulLogin() {
        LoginRequest request = LoginRequest.newBuilder()
                .setEmail(testEmail)
                .setPassword(testPassword)
                .build();

        LoginResponse response = authStub.login(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isNotNull();
        assertThat(response.getAccessToken()).isNotEmpty();
        assertThat(response.getRefreshToken()).isNotNull();
        assertThat(response.getRefreshToken()).isNotEmpty();
        assertThat(response.getExpiresIn()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should successfully refresh token with valid refresh token")
    void testSuccessfulRefresh() { // Проверим, что токен есть в БД
        refreshTokenRepository.findByTokenHash(
                passwordHashService.encode(validRefreshToken, HashingAlgorithm.BCRYPT)
        ).doOnNext(token -> {
            System.out.println("Found token in DB: " + token);
        }).doOnError(error -> {
            System.out.println("Error finding token: " + error);
        }).block();

        RefreshRequest request = RefreshRequest.newBuilder()
                .setRefreshToken(validRefreshToken)
                .build();

        System.out.println("testSuccessfulRefresh().request = " + request);
        RefreshResponse response = authStub.refresh(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isNotNull();
        assertThat(response.getAccessToken()).isNotEmpty();
        assertThat(response.getRefreshToken()).isNotNull();
        assertThat(response.getRefreshToken()).isNotEmpty();
        assertThat(response.getExpiresIn()).isGreaterThan(0);
        assertThat(response.getRefreshToken()).isNotEqualTo(validRefreshToken);
    }

    @Test
    @DisplayName("Should fail login with invalid password")
    void testFailedLoginInvalidPassword() {
        LoginRequest request = LoginRequest.newBuilder()
                .setEmail(testEmail)
                .setPassword("wrongPassword123!")
                .build();

        assertThatThrownBy(() -> authStub.login(request))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Should fail login with non-existent email")
    void testFailedLoginNonExistentEmail() {
        LoginRequest request = LoginRequest.newBuilder()
                .setEmail("nonexistent_" + UUID.randomUUID() + "@example.com")
                .setPassword("somePassword123!")
                .build();

        assertThatThrownBy(() -> authStub.login(request))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("Should fail refresh with invalid refresh token")
    void testFailedRefreshInvalidToken() {
        RefreshRequest request = RefreshRequest.newBuilder()
                .setRefreshToken("invalid.refresh.token.123")
                .build();

        assertThatThrownBy(() -> authStub.refresh(request))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
    }
}