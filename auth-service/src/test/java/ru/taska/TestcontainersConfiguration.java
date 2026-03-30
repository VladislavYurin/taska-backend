package ru.taska;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Stream;

@TestConfiguration
public class TestcontainersConfiguration {

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:15")
                        .asCompatibleSubstituteFor("postgres")
        )
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true); // Опционально: переиспользовать контейнер между тестами

        static {
            // Запускаем контейнер
            Startables.deepStart(Stream.of(postgres)).join();
        }

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            TestPropertyValues.of(
                    "spring.r2dbc.url=r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/testdb",
                    "spring.r2dbc.username=test",
                    "spring.r2dbc.password=test",
                    "spring.flyway.url=" + postgres.getJdbcUrl(),
                    "spring.flyway.user=test",
                    "spring.flyway.password=test",
                    "grpc.server.port=9090"
            ).applyTo(applicationContext.getEnvironment());
        }
    }
}