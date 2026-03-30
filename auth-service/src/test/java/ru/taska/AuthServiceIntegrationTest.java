package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import ru.taska.domain.*;
import ru.taska.grpc.*;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.PasswordHashService;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestcontainersConfiguration.Initializer.class)
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
        // Create test user
        testEmail = "test@example.com";
        testPassword = "ValidPassword123!";

        User user = User.builder()
                .id(UUID.randomUUID())
                .login("testuser")
                .email(testEmail)
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testUserId = user.getId();
        userRepository.save(user).block();

        // Create password credential
        String hashedPassword = passwordHashService.encode(testPassword, HashingAlgorithm.BCRYPT);

        Credential credential = Credential.builder()
                .id(UUID.randomUUID())
                .userId(testUserId)
                .credentialType(CredentialType.PASSWORD)
                .secretHash(hashedPassword)
                .algo(HashingAlgorithm.BCRYPT)
                .failedAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        credentialRepository.save(credential).block();

        // Create valid refresh token for testing
        String rawRefreshToken = generateRawRefreshToken();
        String refreshTokenHash = passwordHashService.encode(rawRefreshToken, HashingAlgorithm.BCRYPT);

        RefreshToken refreshToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(testUserId)
                .tokenHash(refreshTokenHash)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .build();

        refreshTokenRepository.save(refreshToken).block();
        validRefreshToken = rawRefreshToken;
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @AfterAll
    void tearDown() {
        // Clean up test data
        refreshTokenRepository.deleteAll().block();
        credentialRepository.deleteAll().block();
        userRepository.deleteAll().block();

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
    void testSuccessfulRefresh() {
        RefreshRequest request = RefreshRequest.newBuilder()
                .setRefreshToken(validRefreshToken)
                .build();

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
                .setEmail("nonexistent@example.com")
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

    // Добавьте остальные тесты по аналогии...
}