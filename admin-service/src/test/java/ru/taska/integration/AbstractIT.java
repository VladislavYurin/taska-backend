package ru.taska.integration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Base for admin-service integration tests.
 * Uses a singleton Postgres container so Spring's cached ApplicationContext
 * always points at a live database across IT classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIT {

    public static final String FIXTURE_SERVICE = "admin";
    public static final String FIXTURE_TABLE = "it_users";
    public static final String FIXTURE_SCHEMA = "taska";
    public static final String FIXTURE_USER_ID = "11111111-1111-1111-1111-111111111111";
    public static final String FIXTURE_USER_NAME = "alice";
    public static final String FIXTURE_USER_EMAIL = "alice@example.com";
    public static final String AUDIT_TABLE = "admin_audit_log";

    /**
     * Вторая запись аудита: другой actor и action, target_service в legacy-формате с суффиксом "-service"
     * (так писали операции над пользователями до миграции 0002), в old/new есть sensitive-колонка email.
     */
    public static final String SECOND_AUDIT_ACTOR_ID = "22222222-2222-2222-2222-222222222222";
    public static final String SECOND_AUDIT_ACTOR_NAME = "bob";
    public static final String SECOND_AUDIT_ACTION = "second-action";
    public static final String SECOND_AUDIT_TARGET_SERVICE = FIXTURE_SERVICE + "-service";

    /**
     * Singleton container: started once for the JVM, not stopped between test classes.
     * (JUnit @Container lifecycle would stop it after the first class while Spring context is still cached.)
     */
    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);

        registry.add("spring.r2dbc.url", AbstractIT::r2dbcUrl);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.grpc.server.port", () -> "0");

        registerReadonly(registry, "auth");
        registerReadonly(registry, "project");
        registerReadonly(registry, "issue");
        registerReadonly(registry, "workflow");
        registerReadonly(registry, "notification");
        registerReadonly(registry, "admin");

        // Sensitive column for mask wiring check in AdminReadOnlyServiceIT (unit covers mask rules).
        registry.add("admin.metadata.services.admin.tables.sensitive-columns." + FIXTURE_TABLE + ".email",
                () -> "MASK_FULL");
    }

    private static void registerReadonly(DynamicPropertyRegistry registry, String dbKey) {
        registry.add("admin.readonly." + dbKey + ".url", AbstractIT::r2dbcUrl);
        registry.add("admin.readonly." + dbKey + ".username", postgres::getUsername);
        registry.add("admin.readonly." + dbKey + ".password", postgres::getPassword);
        registry.add("admin.readonly." + dbKey + ".pool.initial-size", () -> "1");
        registry.add("admin.readonly." + dbKey + ".pool.max-size", () -> "2");
        registry.add("admin.readonly." + dbKey + ".pool.max-idle-time-minutes", () -> "1");
    }

    /**
     * schema=taska so unqualified table names in ReadOnlyQueryBuilder resolve like production search_path.
     * Host is forced to 127.0.0.1 to avoid IPv6 localhost connection issues on Windows.
     */
    private static String r2dbcUrl() {
        return String.format(
                "r2dbc:postgresql://127.0.0.1:%d/%s?schema=%s",
                postgres.getMappedPort(5432),
                postgres.getDatabaseName(),
                FIXTURE_SCHEMA
        );
    }

    @BeforeAll
    static void ensureReadonlyFixture() throws Exception {
        // Install the real schema before seeding fixtures; changesets must see their historical schema.
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {

            statement.execute("CREATE SCHEMA IF NOT EXISTS " + FIXTURE_SCHEMA);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS %s.%s (
                    id uuid PRIMARY KEY,
                    display_name varchar(64) NOT NULL,
                    email varchar(255) NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    age integer
                )
                """.formatted(FIXTURE_SCHEMA, FIXTURE_TABLE));
            statement.execute("DELETE FROM " + FIXTURE_SCHEMA + "." + FIXTURE_TABLE);
            statement.execute("""
                INSERT INTO %s.%s (id, display_name, email, age) VALUES
                ('%s', '%s', '%s', 30),
                ('22222222-2222-2222-2222-222222222222', 'bob', 'bob@example.com', 25)
                """.formatted(
                    FIXTURE_SCHEMA,
                    FIXTURE_TABLE,
                    FIXTURE_USER_ID,
                    FIXTURE_USER_NAME,
                    FIXTURE_USER_EMAIL
            ));

            statement.execute("""
                CREATE TABLE IF NOT EXISTS %s.%s (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    request_id text NULL,
                    actor_user_id uuid NOT NULL,
                    actor_display_name text NOT NULL,
                    actor_email varchar(320),
                    actor_roles jsonb NOT NULL,
                    action text NOT NULL,
                    target_service text NOT NULL,
                    target_table text NOT NULL,
                    target_id text NOT NULL,
                    old_value jsonb NULL,
                    new_value jsonb NULL,
                    reason text NOT NULL,
                    created_at timestamptz NOT NULL DEFAULT now()
                )
                """.formatted(FIXTURE_SCHEMA, AUDIT_TABLE));

            statement.execute("DELETE FROM " + FIXTURE_SCHEMA + "." + AUDIT_TABLE);
            statement.execute("""
                INSERT INTO %s.%s (
                    id, request_id, actor_user_id, actor_display_name, actor_roles, action,
                    target_service, target_table, target_id, old_value, new_value,
                    reason, created_at
                ) VALUES (
                    '33333333-3333-3333-3333-333333333333',
                    'requestId',
                    '%s',
                    '%s',
                    '["ADMIN"]'::jsonb,
                    'action',
                    '%s',
                    '%s',
                    '00000000-0000-0000-0000-000000000001',
                    '{"name":"old-name"}'::jsonb,
                    '{"name":"new-name"}'::jsonb,
                    'test-reason',
                    '2024-01-15T12:00:00Z'
                ), (
                    '44444444-4444-4444-4444-444444444444',
                    'requestId-2',
                    '%s',
                    '%s',
                    '["ADMIN"]'::jsonb,
                    '%s',
                    '%s',
                    '%s',
                    '00000000-0000-0000-0000-000000000002',
                    '{"display_name":"bob","email":"bob@example.com"}'::jsonb,
                    '{"display_name":"bob","email":"bob.new@example.com"}'::jsonb,
                    'second-reason',
                    '2024-02-01T09:30:00Z'
                )
                """.formatted(
                    FIXTURE_SCHEMA,
                    AUDIT_TABLE,
                    FIXTURE_USER_ID,
                    FIXTURE_USER_NAME,
                    FIXTURE_SERVICE,
                    FIXTURE_TABLE,
                    SECOND_AUDIT_ACTOR_ID,
                    SECOND_AUDIT_ACTOR_NAME,
                    SECOND_AUDIT_ACTION,
                    SECOND_AUDIT_TARGET_SERVICE,
                    FIXTURE_TABLE
            ));
        }
    }
}
