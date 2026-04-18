package ru.taska;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.entity.*;
import ru.taska.repository.CredentialRepository;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.PasswordHashServiceImpl;

import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@Slf4j
public class AuthServiceImplIntegrationTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("auth_db_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    // Статический метод для запуска миграций (как было до рефакторинга)
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
                var rs = stmt.executeQuery(
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
    private PasswordHashServiceImpl passwordHashServiceImpl;

    private ManagedChannel channel;
    private ReactorAuthServiceGrpc.ReactorAuthServiceStub authStub;

    private UUID testUserId;
    private String testEmail;
    private String testPassword;
    private String validRefreshToken;

    @BeforeAll
    void setUp() {
        // Ждем пока Liquibase применит миграции и gRPC сервер запустится
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Создаем канал к gRPC серверу
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        authStub = ReactorAuthServiceGrpc.newReactorStub(channel);

        setupTestData();
    }

    private void setupTestData() {
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
        String refreshTokenHash = passwordHashServiceImpl.encode(rawRefreshToken, HashingAlgorithm.BCRYPT);

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

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @AfterAll
    void tearDown() {
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
        assertThat(userCount).isNotNull();
        log.info("Database schema is properly initialized, users count: {}", userCount);

        // Проверяем, что таблица credentials существует
        Long credentialsCount = credentialRepository.count().block(Duration.ofSeconds(5));
        assertThat(credentialsCount).isNotNull();
        log.info("Credentials table exists, count: {}", credentialsCount);

        // Проверяем, что таблица refresh_tokens существует
        Long refreshTokensCount = refreshTokenRepository.count().block(Duration.ofSeconds(5));
        assertThat(refreshTokensCount).isNotNull();
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
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-2")
                        .setNodeId("test-node")
                        .build())
                .setBody(RefreshRequestBody.newBuilder()
                        .setRefreshToken(validRefreshToken)
                        .build())
                .build();

        RefreshResponse response = authStub.refresh(Mono.just(request)).block(Duration.ofSeconds(10));

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
                .setHeader(Header.newBuilder()
                        .setRequestId("test-req-3")
                        .setNodeId("test-node")
                        .build())
                .setBody(LoginRequestBody.newBuilder()
                        .setEmail(testEmail)
                        .setPassword("wrongPassword123!")
                        .build())
                .build();

        assertThatThrownBy(() -> authStub.login(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("UNAUTHENTICATED");
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

        assertThatThrownBy(() -> authStub.login(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("NOT_FOUND");
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

        assertThatThrownBy(() -> authStub.refresh(Mono.just(request)).block(Duration.ofSeconds(10)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(ex -> {
                    StatusRuntimeException statusEx = (StatusRuntimeException) ex;
                    assertThat(statusEx.getStatus().getCode().toString()).isEqualTo("UNAUTHENTICATED");
                });
    }
}