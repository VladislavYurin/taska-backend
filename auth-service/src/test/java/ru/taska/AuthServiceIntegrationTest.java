package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taska.domain.*;
import ru.taska.grpc.RefreshRequest;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.service.PasswordHashService;
import ru.taska.grpc.AuthServiceGrpc;
import ru.taska.grpc.LoginRequest;
import ru.taska.grpc.LoginResponse;
import ru.taska.grpc.RefreshResponse;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@Slf4j
public class AuthServiceIntegrationTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("auth_db_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withReuse(false);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!postgres.isRunning()) {
            postgres.start();
        }

        registry.add("spring.r2dbc.url", () ->
                String.format("r2dbc:postgresql://%s:%d/%s",
                        postgres.getHost(),
                        postgres.getMappedPort(5432),
                        postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

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
    static void runLiquibaseMigrations() {

        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName());

        log.debug(">>> Running Liquibase migrations on: " + jdbcUrl);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, "test_user", "test_pass")) {
            liquibase.Liquibase liquibase = new liquibase.Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(),
                    new JdbcConnection(connection)
            );

            liquibase.update("test");
            log.debug(">>> Liquibase migrations completed successfully!");

            try (Statement stmt = connection.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'taska' AND table_name = 'users'"
                );
                rs.next();
                log.debug(">>> Users table exists: " + (rs.getInt(1) > 0));
            }

        } catch (Exception e) {
            System.err.println(">>> Liquibase migration failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to run Liquibase migrations", e);
        }
    }

    @BeforeAll
    void setUp() {
        if (!postgres.isRunning()) {
            postgres.start();
        }

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

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

        log.debug(">>> =========================================");
        log.debug(">>> Setting up test data");
        log.debug(">>> Unique suffix: " + uniqueSuffix);
        log.debug(">>> Test email: " + testEmail);
        log.debug(">>> DB Host: " + postgres.getHost() + ":" + postgres.getMappedPort(5432));

        User user = User.builder()
                .login("testuser_" + uniqueSuffix)
                .email(testEmail)
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testUserId = Objects.requireNonNull(userRepository.save(user)
                        .doOnNext(saved -> log.debug(">>> User saved with ID: " + saved.getId()))
                        .doOnError(error -> System.err.println(">>> Error saving user: " + error.getMessage()))
                        .block(Duration.ofSeconds(10)))
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
                .doOnNext(saved -> log.debug(">>> Credential saved with ID: " + saved.getId()))
                .doOnError(error -> System.err.println(">>> Error saving credential: " + error.getMessage()))
                .block(Duration.ofSeconds(10));

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
                .doOnNext(saved -> log.debug(">>> Refresh token saved with ID: " + saved.getId()))
                .doOnError(error -> System.err.println(">>> Error saving refresh token: " + error.getMessage()))
                .block(Duration.ofSeconds(10));

        if (savedToken == null) {
            throw new RuntimeException("Failed to save refresh token");
        }

        validRefreshToken = rawRefreshToken;

        log.debug(">>> Raw refresh token: " + validRefreshToken);
        log.debug(">>> =========================================");
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @AfterAll
    void tearDown() {
        log.debug(">>> Cleaning up test data for user: " + testEmail);

        if (testUserId != null) {
            try {
                refreshTokenRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                credentialRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                userRepository.deleteById(testUserId).block(Duration.ofSeconds(5));
                log.debug(">>> ✅ Cleanup completed");
            } catch (Exception e) {
                System.err.println(">>> Cleanup error: " + e.getMessage());
            }
        }

        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }

        if (postgres.isRunning()) {
            postgres.stop();
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

        log.debug("testSuccessfulRefresh().request = " + request);
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