package ru.taska.integration;

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
    public static final String FIXTURE_USER_LOGIN = "alice";
    public static final String FIXTURE_USER_EMAIL = "alice@example.com";

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
        registry.add("admin.metadata.services.admin.tables.sensitive-columns[0]",
                () -> FIXTURE_TABLE + ".email");
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
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + FIXTURE_SCHEMA);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s.%s (
                        id uuid PRIMARY KEY,
                        login varchar(64) NOT NULL,
                        email varchar(255) NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT now(),
                        age integer
                    )
                    """.formatted(FIXTURE_SCHEMA, FIXTURE_TABLE));
            statement.execute("DELETE FROM " + FIXTURE_SCHEMA + "." + FIXTURE_TABLE);
            statement.execute("""
                    INSERT INTO %s.%s (id, login, email, age) VALUES
                    ('%s', '%s', '%s', 30),
                    ('22222222-2222-2222-2222-222222222222', 'bob', 'bob@example.com', 25)
                    """.formatted(
                    FIXTURE_SCHEMA,
                    FIXTURE_TABLE,
                    FIXTURE_USER_ID,
                    FIXTURE_USER_LOGIN,
                    FIXTURE_USER_EMAIL
            ));
        }
    }
}
