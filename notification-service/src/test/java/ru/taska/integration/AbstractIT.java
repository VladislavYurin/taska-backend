package ru.taska.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Базовый класс для интеграционных тестов notification-service.
 *
 * <p>Использует <b>singleton</b>-контейнер PostgreSQL, который стартует один раз
 * на весь JVM-цикл и не останавливается между тестовыми классами. Это нужно,
 * чтобы Spring test context (который кэшируется по конфигурации) всегда указывал
 * на живую БД.</p>
 *
 * <p>В отличие от других сервисов, notification-service:</p>
 * <ul>
 *   <li>не вызывает project-service — gRPC-клиент к нему отсутствует;</li>
 *   <li>предоставляет gRPC-сервер для inbox — использует {@code spring.grpc.server.port};</li>
 *   <li>не слушает Kafka — listener отключается в тестах, чтобы не требовать брокера;</li>
 *   <li>не отправляет email — в тестах SMTP-хост заглушается.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIT {

    /**
     * Singleton-контейнер: стартует один раз на JVM.
     * {@code @Container} из {@code @Testcontainers} здесь НЕ используется,
     * потому что JUnit останавливает {@code @Container}-контейнер после первого класса,
     * а Spring context кэширует DataSource и падает со второго IT.
     */
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ===== Liquibase =====
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);

        // ===== R2DBC =====
        registry.add("spring.r2dbc.url", AbstractIT::r2dbcUrl);
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);

        // ===== gRPC server: случайный порт =====
        registry.add("spring.grpc.server.port", () -> "0");

        // ===== Kafka: не подключаться =====
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:0");

        // ===== Mail: заглушка =====
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "0");
    }

    /**
     * URL R2DBC к тестовому контейнеру.
     * Host форсится на {@code 127.0.0.1}, чтобы избежать проблем с IPv6 localhost.
     */
    private static String r2dbcUrl() {
        return String.format(
                "r2dbc:postgresql://127.0.0.1:%d/%s",
                POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName());
    }
}