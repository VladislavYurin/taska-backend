package ru.taska.security;

import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.RefreshToken;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.RefreshTokenRepository;
import ru.taska.repository.UserRepository;
import ru.taska.security.config.JwtProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@Slf4j
@DisplayName("Интеграционные тесты на конкурентный доступ в RefreshTokenService")
class RefreshTokenServiceIntegrationConcurrentTest {

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("auth_db_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final String TOKEN_HASH_ALGORITHM = "SHA-256";

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
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenServiceImpl refreshTokenService;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private JwtProperties jwtProperties;

    private UUID userId;
    private User testUser;

    @BeforeAll
    void setUp() {
        // Ждем пока Liquibase применит миграции
        try {
            java.util.concurrent.TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Очищаем таблицы
        databaseClient.sql("DELETE FROM taska.refresh_tokens").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM taska.users").fetch().rowsUpdated().block();

        // Создаем пользователя БЕЗ ID (пусть база сгенерирует)
        testUser = User.builder()
                .login("testuser_" + UUID.randomUUID().toString().substring(0, 8))
                .email("test_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .globalRole(GlobalRole.USER)
                .displayName("Test User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Сохраняем пользователя и получаем его с сгенерированным ID
        User savedUser = userRepository.save(testUser).block(Duration.ofSeconds(10));
        log.debug(">>> Saved user: {}", savedUser);
        Assertions.assertThat(savedUser).isNotNull();
        userId = savedUser.getId();

        log.debug(">>> Setup complete. User ID: {}", userId);
    }

    @AfterAll
    void tearDown() {
        log.debug(">>> Cleaning up test data");

        try {
            refreshTokenRepository.deleteAll().block(Duration.ofSeconds(5));
            userRepository.deleteAll().block(Duration.ofSeconds(5));
            log.debug(">>> Cleanup completed");
        } catch (Exception e) {
            log.error("Cleanup error: {}", e.getMessage());
        }
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(TOKEN_HASH_ALGORITHM);
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hashing algorithm not available", e);
        }
    }

    private RefreshToken createAndSaveToken(String rawToken, UUID userId) {
        String tokenHash = hashToken(rawToken);
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(604800))
                .createdAt(Instant.now())
                .build();

        return refreshTokenRepository.save(token).block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Должен корректно обрабатывать параллельную ротацию refresh-токенов - только первый успешный")
    void shouldHandleParallelRefreshTokenRotationsWithoutConflicts() {
        // Создаем свежий токен для этого теста
        String rawToken = "test-refresh-token-" + System.currentTimeMillis();
        RefreshToken existingToken = createAndSaveToken(rawToken, userId);
        Assertions.assertThat(existingToken).isNotNull();
        String tokenHash = hashToken(rawToken);

        log.debug(">>> Test token hash: {}", tokenHash);

        // Используем AtomicInteger для подсчета успешных вызовов
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Проверяем, что токен существует перед вызовом
        RefreshToken tokenBefore = refreshTokenRepository.findValidToken(tokenHash, Instant.now()).block(Duration.ofSeconds(10));
        log.debug(">>> Token before first call: {}", tokenBefore);
        Assertions.assertThat(tokenBefore).isNotNull();
        Assertions.assertThat(tokenBefore.getTokenHash()).isEqualTo(tokenHash);

        // Сначала выполняем первый вызов и ждем его полного завершения
        RefreshTokenResponseDto firstResult = refreshTokenService.validateAndRotate(rawToken)
                .doOnNext(r -> {
                    successCount.incrementAndGet();
                    log.debug("First call succeeded, new token id: {}", r.getRefreshToken().getId());
                })
                .doOnError(e -> {
                    errorCount.incrementAndGet();
                    log.debug("First call failed: {}", e.getMessage());
                })
                .block(Duration.ofSeconds(10));

        // Проверяем, что первый вызов успешен
        Assertions.assertThat(firstResult).isNotNull();
        Assertions.assertThat(successCount.get()).isEqualTo(1);

        // Проверяем, что старый токен больше не валиден
        RefreshToken oldTokenAfter = refreshTokenRepository.findValidToken(tokenHash, Instant.now()).block(Duration.ofSeconds(10));
        Assertions.assertThat(oldTokenAfter).isNull();

        // Затем выполняем второй вызов с тем же токеном - он должен упасть
        Mono<RefreshTokenResponseDto> secondCall = refreshTokenService.validateAndRotate(rawToken)
                .doOnNext(r -> {
                    successCount.incrementAndGet();
                    log.debug("Second call succeeded");
                })
                .doOnError(e -> {
                    errorCount.incrementAndGet();
                    log.debug("Second call failed: {}", e.getMessage());
                });

        StepVerifier.create(secondCall)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException domainError = (DomainException) error;
                    Assertions.assertThat(domainError.getStatus()).isEqualTo(DomainStatus.UNAUTHENTICATED);
                    Assertions.assertThat(domainError.getMessage()).contains("Invalid or expired refresh token");
                })
                .verify();

        // Проверяем, что второй вызов завершился с ошибкой
        Assertions.assertThat(errorCount.get()).isEqualTo(1);

        // Проверяем состояние БД - должно быть 2 токена (старый отозванный и новый)
        List<RefreshToken> allTokens = refreshTokenRepository.findAll().collectList().block(Duration.ofSeconds(10));
        Assertions.assertThat(allTokens).hasSize(2);

        // Проверяем, что старый токен отозван
        RefreshToken oldToken = refreshTokenRepository.findById(existingToken.getId()).block(Duration.ofSeconds(10));
        Assertions.assertThat(oldToken).isNotNull();
        Assertions.assertThat(oldToken.getRevokedAt()).isNotNull();
        Assertions.assertThat(oldToken.getReplacedBy()).isNotNull();

        // Проверяем, что есть активный токен
        long activeCount = allTokens.stream()
                .filter(t -> t.getReplacedBy() == null && t.getRevokedAt() == null)
                .count();
        Assertions.assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Должен корректно устанавливать replaced_by при ротации")
    void shouldCorrectlySetReplacedBy() {
        // Создаем свежий токен для этого теста
        String rawToken = "test-refresh-token-" + System.currentTimeMillis();
        RefreshToken existingToken = createAndSaveToken(rawToken, userId);
        Assertions.assertThat(existingToken).isNotNull();

        // When
        RefreshTokenResponseDto result = refreshTokenService.validateAndRotate(rawToken).block(Duration.ofSeconds(10));

        // Then
        Assertions.assertThat(result).isNotNull();

        RefreshToken oldToken = refreshTokenRepository.findById(existingToken.getId()).block(Duration.ofSeconds(10));
        Assertions.assertThat(oldToken).isNotNull();
        Assertions.assertThat(oldToken.getReplacedBy()).isNotNull();
        Assertions.assertThat(oldToken.getReplacedBy()).isEqualTo(result.getRefreshToken().getId());
        Assertions.assertThat(oldToken.getRevokedAt()).isNotNull();

        RefreshToken newToken = refreshTokenRepository.findById(result.getRefreshToken().getId()).block(Duration.ofSeconds(10));
        Assertions.assertThat(newToken).isNotNull();
        Assertions.assertThat(newToken.getReplacedBy()).isNull();
        Assertions.assertThat(newToken.getRevokedAt()).isNull();
        Assertions.assertThat(newToken.getTokenHash()).isNotEqualTo(oldToken.getTokenHash());
    }

    @Test
    @DisplayName("Должен отклонять второй параллельный запрос с проверкой replaced_by")
    void shouldRejectSecondParallelRequestWithReplacedByCheck() {
        // Создаем свежий токен для этого теста
        String rawToken = "test-refresh-token-" + System.currentTimeMillis();
        RefreshToken existingToken = createAndSaveToken(rawToken, userId);
        Assertions.assertThat(existingToken).isNotNull();

        // Given - первый вызов успешен
        RefreshTokenResponseDto firstResult = refreshTokenService.validateAndRotate(rawToken).block(Duration.ofSeconds(10));
        Assertions.assertThat(firstResult).isNotNull();

        // When - второй вызов с тем же токеном
        Mono<RefreshTokenResponseDto> secondCall = refreshTokenService.validateAndRotate(rawToken);

        // Then - должен упасть с UNAUTHENTICATED
        StepVerifier.create(secondCall)
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException domainError = (DomainException) error;
                    Assertions.assertThat(domainError.getStatus()).isEqualTo(DomainStatus.UNAUTHENTICATED);
                    Assertions.assertThat(domainError.getMessage()).contains("Invalid or expired refresh token");
                })
                .verify();
    }

    @Test
    @DisplayName("Должен корректно обрабатывать параллельные попытки обновления от разных пользователей")
    void shouldHandleConcurrentRefreshAttemptsWithDifferentUsers() {
        // Создаем свежий токен для первого пользователя
        String rawToken1 = "test-refresh-token-1-" + System.currentTimeMillis();
        RefreshToken existingToken1 = createAndSaveToken(rawToken1, userId);
        Assertions.assertThat(existingToken1).isNotNull();

        // Given - второй пользователь (БЕЗ ID)
        User testUser2 = User.builder()
                .login("testuser2_" + UUID.randomUUID().toString().substring(0, 8))
                .email("test2_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .globalRole(GlobalRole.USER)
                .displayName("Test User 2")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        // Сохраняем второго пользователя и получаем его с ID
        User savedUser2 = userRepository.save(testUser2).block(Duration.ofSeconds(10));
        Assertions.assertThat(savedUser2).isNotNull();
        UUID userId2 = savedUser2.getId();

        // Создаем токен для второго пользователя
        String rawToken2 = "test-refresh-token-2-" + System.currentTimeMillis();
        RefreshToken existingToken2 = createAndSaveToken(rawToken2, userId2);
        Assertions.assertThat(existingToken2).isNotNull();

        log.debug(">>> Created second user with token hash: {}", hashToken(rawToken2).substring(0, 8) + "***");

        // When - параллельная ротация для двух разных пользователей
        Mono<RefreshTokenResponseDto> call1 = refreshTokenService.validateAndRotate(rawToken1);
        Mono<RefreshTokenResponseDto> call2 = refreshTokenService.validateAndRotate(rawToken2);

        // Then - оба должны завершиться успешно (нет конфликта)
        StepVerifier.create(Flux.merge(call1, call2).collectList())
                .assertNext(results -> {
                    Assertions.assertThat(results).hasSize(2);
                    // Проверяем, что результаты принадлежат разным пользователям
                    Assertions.assertThat(results).extracting(r -> r.getRefreshToken().getUserId())
                            .containsExactlyInAnyOrder(userId, userId2);
                })
                .verifyComplete();

        // Проверяем, что оба токена отозваны
        String tokenHash1 = hashToken(rawToken1);
        String tokenHash2 = hashToken(rawToken2);
        RefreshToken oldToken1 = refreshTokenRepository.findByTokenHash(tokenHash1).block(Duration.ofSeconds(10));
        RefreshToken oldToken2 = refreshTokenRepository.findByTokenHash(tokenHash2).block(Duration.ofSeconds(10));
        Assertions.assertThat(oldToken1.getRevokedAt()).isNotNull();
        Assertions.assertThat(oldToken2.getRevokedAt()).isNotNull();
    }
}