package ru.taska.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
public abstract class AbstractIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);

        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.grpc.server.port", () -> "9090");

        registerReadonly(registry, "auth");
        registerReadonly(registry, "project");
        registerReadonly(registry, "issue");
        registerReadonly(registry, "workflow");
        registerReadonly(registry, "notification");
    }

    private static void registerReadonly(DynamicPropertyRegistry registry, String dbKey) {
        registry.add("admin.readonly." + dbKey + ".url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()));
        registry.add("admin.readonly." + dbKey + ".username", postgres::getUsername);
        registry.add("admin.readonly." + dbKey + ".password", postgres::getPassword);
        registry.add("admin.readonly." + dbKey + ".pool.initial-size", () -> "1");
        registry.add("admin.readonly." + dbKey + ".pool.max-size", () -> "2");
        registry.add("admin.readonly." + dbKey + ".pool.max-idle-time-minutes", () -> "1");
    }
}

