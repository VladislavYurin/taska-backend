package ru.taska.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIT {

    /**
     * Порт, на котором в тестах поднимается реальный gRPC-сервер project-service.
     * Используется тестами, стучащимися в него напрямую через {@code ManagedChannel}
     * (например, {@code UpdateProjectGrpcServerIT}).
     *
     * <p>Не задаётся через {@code @DynamicPropertySource}: в используемой версии
     * spring-grpc-server-spring-boot-autoconfigure {@code spring.grpc.server.port},
     * в отличие от {@code spring.grpc.server.address}, не подхватывается из динамических
     * тестовых свойств и сервер всегда поднимается на дефолтном порту библиотеки — 9090
     * (проверено эмпирически). Совпадает с портом, на котором уже поднимает свой gRPC-сервер
     * {@code auth-service} в своих тестах (см. {@code auth-service/.../AbstractIT.java}).</p>
     */
    protected static final int GRPC_SERVER_PORT = 9090;

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    /**
     * Singleton-контейнер: стартует один раз на JVM и НЕ останавливается между тестовыми
     * классами — так как Spring кэширует и переиспользует ApplicationContext между всеми
     * наследниками {@code AbstractIT} (у них идентичная конфигурация), контейнер должен
     * жить всё это время.
     *
     * <p>Намеренно не используется {@code @Container}/{@code @Testcontainers}: стандартный
     * JUnit 5-жизненный цикл Testcontainers останавливает контейнер сразу после первого
     * тестового класса, который его использовал, — а закэшированный Spring-контекст (и его
     * R2DBC connection pool) при этом продолжает считать, что контейнер жив, и падает с
     * {@code Connection validation failed} при первом же запросе из ВТОРОГО тестового класса.
     * Тот же паттерн уже применён в {@code admin-service/.../integration/AbstractIT.java}.</p>
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

        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(),
                postgres.getMappedPort(5432),
                postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        registry.add("spring.grpc.server.address", () -> "127.0.0.1");
    }
}
