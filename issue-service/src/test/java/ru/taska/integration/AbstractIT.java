package ru.taska.integration;

import java.util.UUID;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.VerifiedTenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
// The class-scoped container is stopped after each test class; do not reuse its Spring context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIT {

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

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

        registry.add("spring.grpc.server.address", () -> "127.0.0.1:0");
        registry.add("app.grpc.client.project-service.host", () -> "127.0.0.1");
        registry.add("app.grpc.client.project-service.port", () -> "9090");

    }

    protected static <T> Mono<T> withTenant(Mono<T> operation) {
        var verified = verifiedTenant();
        return operation.contextWrite(context -> context.put(VerifiedTenantContext.class, verified));
    }

    protected static <T> Flux<T> withTenant(Flux<T> operation) {
        var verified = verifiedTenant();
        return operation.contextWrite(context -> context.put(VerifiedTenantContext.class, verified));
    }

    private static VerifiedTenantContext verifiedTenant() {
        var verified = Mockito.mock(VerifiedTenantContext.class);
        Mockito.when(verified.tenantId()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(verified.requestId()).thenReturn(UUID.randomUUID().toString());
        return verified;
    }
}
