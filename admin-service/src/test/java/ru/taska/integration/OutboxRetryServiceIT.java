package ru.taska.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;
import ru.taska.domain.OutboxEventSnapshot;
import ru.taska.domain.OutboxStatus;
import ru.taska.service.OutboxRetryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration-тест полного сценария ручного retry outbox-события.
 * <p>
 * Использует отдельный PostgreSQL Testcontainer и не зависит от
 * существующего {@code AbstractIT} или fixtures других разработчиков.
 * Проверяет реальный {@link OutboxRetryService}, реальный outbox repository
 * и сохранение административного аудита в {@code admin_audit_log}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxRetryServiceIT {

    private static final String SERVICE = "issue";
    private static final String REQUEST_ID = "tas-106-it-request";
    private static final String REASON = "Manual retry after investigation";
    private static final String ACTOR_LOGIN = "global-admin";
    private static final String NODE_ID = "admin-it-node";

    private static final UUID ACTOR_USER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS taska");
        } catch (SQLException ex) {
            postgres.stop();
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Autowired
    private OutboxRetryService outboxRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Направляет все подключения Spring-контекста данного теста
     * в отдельный PostgreSQL Testcontainer.
     *
     * @param registry registry динамических Spring properties
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml"
        );
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);

        registry.add("spring.r2dbc.url", OutboxRetryServiceIT::r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.grpc.server.port", () -> "0");
        registry.add("admin.outbox-retry.stuck-threshold", () -> "10m");

        registerReadonly(registry, "auth");
        registerReadonly(registry, "project");
        registerReadonly(registry, "issue");
        registerReadonly(registry, "workflow");
        registerReadonly(registry, "notification");
        registerReadonly(registry, "admin");

        registerOutboxWrite(registry, "auth");
        registerOutboxWrite(registry, "project");
        registerOutboxWrite(registry, "issue");

        /*
         * Эти два ключа безопасно оставить даже если текущая версия
         * OutboxWriteDatasourcesProperties уже ограничена только
         * auth/project/issue. Если поля существуют, они также будут
         * направлены в Testcontainer; если нет — будут проигнорированы.
         */
        registerOutboxWrite(registry, "workflow");
        registerOutboxWrite(registry, "notification");
    }

    /**
     * Создаёт минимальную таблицу outbox_events для service DB.
     * Таблица audit создаётся настоящими Liquibase-миграциями admin-service.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @BeforeAll
    static void createOutboxTable() throws Exception {
        executeSql("""
                CREATE TABLE IF NOT EXISTS taska.outbox_events (
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

    /**
     * Очищает только данные TAS-106 перед каждым тестом.
     *
     * @throws Exception если очистить данные не удалось
     */
    @BeforeEach
    void cleanDatabase() throws Exception {
        executeSql("DELETE FROM taska.outbox_events");
        executeSql("DELETE FROM taska.admin_audit_log");
    }

    /**
     * Останавливает отдельный Testcontainer после выполнения класса.
     */
    @AfterAll
    static void stopContainer() {
        postgres.stop();
    }

    /**
     * Проверяет полный успешный сценарий:
     * FAILED -> NEW и реальную запись audit old/new, actor, reason, requestId.
     *
     * @throws Exception если подготовка или чтение fixture не удались
     */
    @Test
    @DisplayName("FAILED retry сохраняет полный audit в admin_audit_log")
    void retryFailed_shouldPersistAudit() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "issueId": "22222222-2222-2222-2222-222222222222",
                  "event": "ISSUE_UPDATED"
                }
                """);

        JsonNode actorRoles = objectMapper.valueToTree(
                List.of("GLOBAL_ADMIN")
        );

        insertEvent(
                eventId,
                OutboxStatus.FAILED,
                4,
                "Kafka unavailable",
                payload
        );

        StepVerifier.create(outboxRetryService.retryOutboxEvent(
                        REQUEST_ID,
                        NODE_ID,
                        SERVICE,
                        eventId,
                        REASON,
                        ACTOR_USER_ID,
                        ACTOR_LOGIN,
                        actorRoles
                ))
                .assertNext(snapshot -> assertSuccessfulSnapshot(
                        snapshot,
                        eventId,
                        payload,
                        4
                ))
                .verifyComplete();

        AuditRow audit = readAudit(REQUEST_ID);

        assertNotNull(audit);

        assertEquals(REQUEST_ID, audit.requestId());
        assertEquals(ACTOR_USER_ID, audit.actorUserId());
        assertEquals(ACTOR_LOGIN, audit.actorLogin());
        assertEquals(actorRoles, audit.actorRoles());

        assertEquals("RETRY_OUTBOX_EVENT", audit.action());
        assertEquals(SERVICE, audit.targetService());
        assertEquals("outbox_events", audit.targetTable());
        assertEquals(eventId.toString(), audit.targetId());
        assertEquals(REASON, audit.reason());

        assertEquals(
                OutboxStatus.FAILED.name(),
                audit.oldValue().get("status").asText()
        );
        assertEquals(
                OutboxStatus.NEW.name(),
                audit.newValue().get("status").asText()
        );

        assertEquals(4, audit.oldValue().get("attempts").asInt());
        assertEquals(4, audit.newValue().get("attempts").asInt());

        assertEquals(payload, audit.oldValue().get("payload"));
        assertEquals(payload, audit.newValue().get("payload"));
    }

    /**
     * Проверяет согласованную с TL семантику ошибки audit:
     * клиент получает ошибку, но уже выполненный retry outbox не компенсируется.
     *
     * @throws Exception если подготовка fixture не удалась
     */
    @Test
    @DisplayName("При ошибке audit операция возвращает error, но outbox остаётся NEW")
    void retryAuditFailure_shouldReturnErrorWithoutCompensation() throws Exception {
        UUID eventId = UUID.randomUUID();

        JsonNode payload = objectMapper.readTree("""
                {
                  "issueId": "22222222-2222-2222-2222-222222222222",
                  "event": "ISSUE_UPDATED"
                }
                """);

        JsonNode actorRoles = objectMapper.valueToTree(
                List.of("GLOBAL_ADMIN")
        );

        insertEvent(
                eventId,
                OutboxStatus.FAILED,
                5,
                "Kafka unavailable",
                payload
        );

        executeSql("""
                ALTER TABLE taska.admin_audit_log
                RENAME TO admin_audit_log_unavailable
                """);

        try {
            StepVerifier.create(outboxRetryService.retryOutboxEvent(
                            REQUEST_ID,
                            NODE_ID,
                            SERVICE,
                            eventId,
                            REASON,
                            ACTOR_USER_ID,
                            ACTOR_LOGIN,
                            actorRoles
                    ))
                    .expectError()
                    .verify();

            OutboxState state = readOutboxState(eventId);

            assertEquals(OutboxStatus.NEW.name(), state.status());
            assertEquals(5, state.attempts());
            assertEquals(payload, state.payload());
            assertNull(state.lastErrorMessage());
            assertNull(state.processingStartedAt());
        } finally {
            executeSql("""
                    ALTER TABLE taska.admin_audit_log_unavailable
                    RENAME TO admin_audit_log
                    """);
        }
    }

    /**
     * Проверяет snapshot, возвращаемый после успешного retry.
     *
     * @param snapshot ожидаемый результат сервиса
     * @param eventId  идентификатор события
     * @param payload  исходный payload
     * @param attempts исходное количество попыток
     */
    private void assertSuccessfulSnapshot(
            OutboxEventSnapshot snapshot,
            UUID eventId,
            JsonNode payload,
            int attempts
    ) {
        assertEquals(eventId, snapshot.id());
        assertEquals(OutboxStatus.NEW, snapshot.status());
        assertEquals(attempts, snapshot.attempts());
        assertEquals(payload, snapshot.payload());
        assertNull(snapshot.lastErrorMessage());
        assertNull(snapshot.processingStartedAt());
    }

    /**
     * Добавляет FAILED outbox event непосредственно в PostgreSQL fixture.
     *
     * @param eventId          идентификатор события
     * @param status           статус события
     * @param attempts         количество предыдущих попыток
     * @param lastErrorMessage последняя ошибка
     * @param payload          payload события
     * @throws Exception если INSERT не выполнился
     */
    private void insertEvent(
            UUID eventId,
            OutboxStatus status,
            int attempts,
            String lastErrorMessage,
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

        try (Connection connection = jdbcConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

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
            statement.setObject(9, OffsetDateTime.now());
            statement.setObject(10, null);
            statement.setObject(11, null);
            statement.setString(12, "source-" + eventId);

            statement.executeUpdate();
        }
    }

    /**
     * Читает сохранённую строку административного аудита.
     *
     * @param requestId requestId операции
     * @return строка аудита
     * @throws Exception если SELECT не выполнился
     */
    private AuditRow readAudit(String requestId) throws Exception {
        String sql = """
                SELECT request_id,
                       actor_user_id,
                       actor_login,
                       actor_roles::text AS actor_roles,
                       action,
                       target_service,
                       target_table,
                       target_id,
                       old_value::text AS old_value,
                       new_value::text AS new_value,
                       reason
                FROM taska.admin_audit_log
                WHERE request_id = ?
                """;

        try (Connection connection = jdbcConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, requestId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new AuditRow(
                        resultSet.getString("request_id"),
                        resultSet.getObject("actor_user_id", UUID.class),
                        resultSet.getString("actor_login"),
                        objectMapper.readTree(
                                resultSet.getString("actor_roles")
                        ),
                        resultSet.getString("action"),
                        resultSet.getString("target_service"),
                        resultSet.getString("target_table"),
                        resultSet.getString("target_id"),
                        objectMapper.readTree(
                                resultSet.getString("old_value")
                        ),
                        objectMapper.readTree(
                                resultSet.getString("new_value")
                        ),
                        resultSet.getString("reason")
                );
            }
        }
    }

    /**
     * Читает техническое состояние outbox после вызова сервиса.
     *
     * @param eventId идентификатор события
     * @return состояние outbox
     * @throws Exception если SELECT не выполнился
     */
    private OutboxState readOutboxState(UUID eventId) throws Exception {
        String sql = """
                SELECT status,
                       attempts,
                       payload::text AS payload,
                       last_error_message,
                       processing_started_at
                FROM taska.outbox_events
                WHERE id = ?
                """;

        try (Connection connection = jdbcConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, eventId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "Outbox event not found: " + eventId
                    );
                }

                OffsetDateTime processingStartedAt =
                        resultSet.getObject(
                                "processing_started_at",
                                OffsetDateTime.class
                        );

                return new OutboxState(
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        objectMapper.readTree(
                                resultSet.getString("payload")
                        ),
                        resultSet.getString("last_error_message"),
                        processingStartedAt == null
                                ? null
                                : processingStartedAt.toInstant()
                );
            }
        }
    }

    /**
     * Регистрирует read-only datasource указанного сервиса
     * на отдельный PostgreSQL Testcontainer.
     *
     * @param registry Spring property registry
     * @param service  ключ сервиса
     */
    private static void registerReadonly(
            DynamicPropertyRegistry registry,
            String service
    ) {
        String prefix = "admin.readonly." + service;

        registry.add(prefix + ".url", OutboxRetryServiceIT::r2dbcUrl);
        registry.add(prefix + ".username", postgres::getUsername);
        registry.add(prefix + ".password", postgres::getPassword);
        registry.add(prefix + ".pool.initial-size", () -> "1");
        registry.add(prefix + ".pool.max-size", () -> "2");
        registry.add(
                prefix + ".pool.max-idle-time-minutes",
                () -> "1"
        );
    }

    /**
     * Регистрирует outbox write datasource указанного сервиса
     * на отдельный PostgreSQL Testcontainer.
     *
     * @param registry Spring property registry
     * @param service  ключ сервиса
     */
    private static void registerOutboxWrite(
            DynamicPropertyRegistry registry,
            String service
    ) {
        String prefix = "admin.outbox-write." + service;

        registry.add(prefix + ".url", OutboxRetryServiceIT::r2dbcUrl);
        registry.add(prefix + ".username", postgres::getUsername);
        registry.add(prefix + ".password", postgres::getPassword);
        registry.add(prefix + ".pool.initial-size", () -> "1");
        registry.add(prefix + ".pool.max-size", () -> "2");
        registry.add(
                prefix + ".pool.max-idle-time-minutes",
                () -> "1"
        );
    }

    /**
     * Формирует R2DBC URL к отдельному PostgreSQL Testcontainer.
     *
     * @return R2DBC URL тестовой БД
     */
    private static String r2dbcUrl() {
        return String.format(
                "r2dbc:postgresql://127.0.0.1:%d/%s?schema=taska",
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()
        );
    }

    /**
     * Создаёт JDBC connection к отдельному PostgreSQL Testcontainer.
     *
     * @return JDBC connection
     * @throws SQLException если соединение открыть не удалось
     */
    private static Connection jdbcConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    /**
     * Выполняет служебный SQL в тестовой БД.
     *
     * @param sql SQL-команда
     * @throws Exception если команда не выполнилась
     */
    private static void executeSql(String sql) throws Exception {
        try (Connection connection = jdbcConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Представление строки административного аудита,
     * прочитанной напрямую из PostgreSQL.
     */
    private record AuditRow(
            String requestId,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles,
            String action,
            String targetService,
            String targetTable,
            String targetId,
            JsonNode oldValue,
            JsonNode newValue,
            String reason
    ) {
    }

    /**
     * Представление технического состояния outbox после retry.
     */
    private record OutboxState(
            String status,
            int attempts,
            JsonNode payload,
            String lastErrorMessage,
            Instant processingStartedAt
    ) {
    }
}
