package ru.taska.integration;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.repository.OutboxRetryRepository;
import ru.taska.repository.OutboxRetryRepositoryImpl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration-тесты {@link OutboxRetryRepository}.
 * <p>
 * Проверяют реальное выполнение SQL ручного retry
 * на PostgreSQL Testcontainers без изменения существующей
 * infrastructure других integration tests.
 */
class OutboxRetryRepositoryIT {

    private static final String SERVICE = "issue";

    private static PostgreSQLContainer<?> postgres;

    private static OutboxRetryRepository repository;

    private static ObjectMapper objectMapper;

    /**
     * Запускает отдельный PostgreSQL container и создаёт
     * минимальную схему outbox для TAS-106.
     *
     * @throws Exception если тестовую БД подготовить не удалось
     */
    @BeforeAll
    static void setUpDatabase() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        objectMapper = new ObjectMapper();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE SCHEMA IF NOT EXISTS taska");

            statement.execute("""
                    CREATE TABLE taska.outbox_events (
                        id uuid PRIMARY KEY,
                        aggregate_type varchar(128) NOT NULL,
                        aggregate_id uuid NOT NULL,
                        event_type varchar(128) NOT NULL,
                        status varchar(32) NOT NULL,
                        payload jsonb NOT NULL,
                        attempts integer NOT NULL,
                        last_error_message text,
                        created_at timestamptz NOT NULL,
                        published_at timestamptz,
                        processing_started_at timestamptz,
                        request_id text
                    )
                    """);
        }

        ConnectionFactoryOptions options =
                ConnectionFactoryOptions.builder()
                        .option(DRIVER, "postgresql")
                        .option(HOST, "127.0.0.1")
                        .option(PORT, postgres.getMappedPort(5432))
                        .option(USER, postgres.getUsername())
                        .option(PASSWORD, postgres.getPassword())
                        .option(DATABASE, postgres.getDatabaseName())
                        .build();

        ConnectionFactory connectionFactory =
                ConnectionFactories.get(options);

        DatabaseClient databaseClient =
                DatabaseClient.create(connectionFactory);

        repository = new OutboxRetryRepositoryImpl(
                Map.of(SERVICE, databaseClient),
                objectMapper
        );
    }

    /**
     * Очищает outbox перед каждым тестом.
     *
     * @throws Exception если очистка БД не удалась
     */
    @BeforeEach
    void cleanDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             Statement statement = connection.createStatement()) {

            statement.execute("DELETE FROM taska.outbox_events");
        }
    }

    /**
     * Останавливает PostgreSQL container после завершения тестов.
     */
    @AfterAll
    static void tearDownDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * FAILED должен быть переведён в NEW.
     * Attempts и payload должны сохраниться,
     * информация об ошибке должна быть очищена.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("FAILED переводится в NEW без изменения attempts и payload")
    void retryFailed_shouldPreserveAttemptsAndPayload() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "issueId": "22222222-2222-2222-2222-222222222222",
                  "event": "ISSUE_UPDATED"
                }
                """);

        insertEvent(
                eventId,
                OutboxStatus.FAILED,
                4,
                "Kafka unavailable",
                null,
                payload
        );

        StepVerifier.create(repository.retry(
                        SERVICE,
                        eventId,
                        Instant.now().minus(Duration.ofMinutes(10))
                ))
                .expectNext(1L)
                .verifyComplete();

        OutboxEventSnapshot snapshot =
                repository.findById(SERVICE, eventId).block();

        assertEquals(OutboxStatus.NEW, snapshot.status());
        assertEquals(4, snapshot.attempts());
        assertEquals(payload, snapshot.payload());

        assertNull(snapshot.lastErrorMessage());
        assertNull(snapshot.processingStartedAt());
    }

    /**
     * Зависший PROCESSING должен быть доступен для retry.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("Зависший PROCESSING переводится в NEW")
    void retryStuckProcessing_shouldSucceed() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "event": "ISSUE_UPDATED"
                }
                """);

        insertEvent(
                eventId,
                OutboxStatus.PROCESSING,
                2,
                null,
                Instant.now().minus(Duration.ofMinutes(20)),
                payload
        );

        StepVerifier.create(repository.retry(
                        SERVICE,
                        eventId,
                        Instant.now().minus(Duration.ofMinutes(10))
                ))
                .expectNext(1L)
                .verifyComplete();

        OutboxEventSnapshot snapshot =
                repository.findById(SERVICE, eventId).block();

        assertEquals(OutboxStatus.NEW, snapshot.status());
        assertEquals(2, snapshot.attempts());
        assertEquals(payload, snapshot.payload());
        assertNull(snapshot.processingStartedAt());
    }

    /**
     * Свежий PROCESSING не должен удовлетворять guarded UPDATE.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("Свежий PROCESSING нельзя retry")
    void retryFreshProcessing_shouldNotUpdate() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "event": "ISSUE_UPDATED"
                }
                """);

        insertEvent(
                eventId,
                OutboxStatus.PROCESSING,
                1,
                null,
                Instant.now().minus(Duration.ofMinutes(1)),
                payload
        );

        StepVerifier.create(repository.retry(
                        SERVICE,
                        eventId,
                        Instant.now().minus(Duration.ofMinutes(10))
                ))
                .expectNext(0L)
                .verifyComplete();

        OutboxEventSnapshot snapshot =
                repository.findById(SERVICE, eventId).block();

        assertEquals(
                OutboxStatus.PROCESSING,
                snapshot.status()
        );

        assertEquals(1, snapshot.attempts());
        assertEquals(payload, snapshot.payload());
    }

    /**
     * NEW не должен изменяться ручным retry.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("NEW нельзя retry")
    void retryNew_shouldNotUpdate() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "event": "ISSUE_UPDATED"
                }
                """);

        insertEvent(
                eventId,
                OutboxStatus.NEW,
                0,
                null,
                null,
                payload
        );

        StepVerifier.create(repository.retry(
                        SERVICE,
                        eventId,
                        Instant.now().minus(Duration.ofMinutes(10))
                ))
                .expectNext(0L)
                .verifyComplete();

        OutboxEventSnapshot snapshot =
                repository.findById(SERVICE, eventId).block();

        assertEquals(OutboxStatus.NEW, snapshot.status());
        assertEquals(payload, snapshot.payload());
    }

    /**
     * PUBLISHED не должен изменяться ручным retry.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("PUBLISHED нельзя retry")
    void retryPublished_shouldNotUpdate() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "event": "ISSUE_UPDATED"
                }
                """);

        insertEvent(
                eventId,
                OutboxStatus.PUBLISHED,
                3,
                null,
                null,
                payload
        );

        StepVerifier.create(repository.retry(
                        SERVICE,
                        eventId,
                        Instant.now().minus(Duration.ofMinutes(10))
                ))
                .expectNext(0L)
                .verifyComplete();

        OutboxEventSnapshot snapshot =
                repository.findById(SERVICE, eventId).block();

        assertEquals(
                OutboxStatus.PUBLISHED,
                snapshot.status()
        );

        assertEquals(3, snapshot.attempts());
        assertEquals(payload, snapshot.payload());
    }

    /**
     * Добавляет outbox event непосредственно в PostgreSQL fixture.
     *
     * @param eventId             идентификатор события
     * @param status              статус
     * @param attempts            количество попыток
     * @param lastErrorMessage    последняя ошибка
     * @param processingStartedAt начало обработки
     * @param payload             payload события
     * @throws Exception если INSERT не выполнился
     */
    private void insertEvent(
            UUID eventId,
            OutboxStatus status,
            int attempts,
            String lastErrorMessage,
            Instant processingStartedAt,
            JsonNode payload
    ) throws Exception {
        String sql = """
                INSERT INTO taska.outbox_events (
                    id,
                    aggregate_type,
                    aggregate_id,
                    event_type,
                    status,
                    payload,
                    attempts,
                    last_error_message,
                    created_at,
                    published_at,
                    processing_started_at,
                    request_id
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setObject(1, eventId);
            statement.setString(2, "ISSUE");
            statement.setObject(3, UUID.randomUUID());
            statement.setString(4, "ISSUE_UPDATED");
            statement.setString(5, status.name());
            statement.setString(
                    6,
                    objectMapper.writeValueAsString(payload)
            );
            statement.setInt(7, attempts);
            statement.setString(8, lastErrorMessage);
            statement.setObject(
                    9,
                    java.time.OffsetDateTime.now()
            );

            if (status == OutboxStatus.PUBLISHED) {
                statement.setObject(
                        10,
                        java.time.OffsetDateTime.now()
                                .minusMinutes(1)
                );
            } else {
                statement.setObject(10, null);
            }

            if (processingStartedAt != null) {
                statement.setObject(
                        11,
                        processingStartedAt.atOffset(
                                java.time.ZoneOffset.UTC
                        )
                );
            } else {
                statement.setObject(11, null);
            }

            statement.setString(
                    12,
                    "request-" + eventId
            );

            statement.executeUpdate();
        }
    }
}