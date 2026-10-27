package ru.taska.config;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import ru.taska.config.props.OutboxWriteDatasourcesProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the shipped configuration rather than overriding admin.outbox-write.* directly,
 * so a missing retry endpoint in container environments cannot silently select localhost.
 */
class OutboxWriteDatasourcePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxWriteDatasourcesProperties.class)
    static class PropertiesConfiguration { }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "project", "issue"})
    void retryUsesConfiguredServiceEndpointWhenNoWriteEndpointIsProvided(String service) throws IOException {
        String endpoint = "r2dbc:postgresql://" + service + "-db:5432/" + service + "_db";

        runner(Map.of(envKey(service, "RO", "URL"), endpoint)).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = datasource(context.getBean(OutboxWriteDatasourcesProperties.class), service);
            assertThat(properties.url()).isEqualTo(endpoint);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "project", "issue"})
    void explicitWriteConnectionOverridesReadonlyEndpoint(String service) throws IOException {
        String endpoint = "r2dbc:postgresql://retry-" + service + ":6432/" + service + "_db";

        runner(Map.of(
                envKey(service, "RO", "URL"), "r2dbc:postgresql://readonly-host:5432/replica",
                envKey(service, "OUTBOX_WRITE", "URL"), endpoint,
                envKey(service, "OUTBOX_WRITE", "USERNAME"), "outbox_operator",
                envKey(service, "OUTBOX_WRITE", "PASSWORD"), "write-test-password"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = datasource(context.getBean(OutboxWriteDatasourcesProperties.class), service);
            assertThat(properties.url()).isEqualTo(endpoint);
            assertThat(properties.username()).isEqualTo("outbox_operator");
            assertThat(properties.password()).isEqualTo("write-test-password");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "project", "issue"})
    void endpointFallbackDoesNotBorrowReadonlyCredentials(String service) throws IOException {
        runner(Map.of(
                envKey(service, "RO", "URL"), "r2dbc:postgresql://" + service + "-db:5432/" + service + "_db",
                envKey(service, "RO", "USERNAME"), "select_only",
                envKey(service, "RO", "PASSWORD"), "readonly-test-password"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = datasource(context.getBean(OutboxWriteDatasourcesProperties.class), service);
            assertThat(properties.username()).isEqualTo("taska");
            assertThat(properties.password()).isEqualTo("taska");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "project", "issue"})
    void standaloneLocalDefaultsRemainAvailable(String service) throws IOException {
        int port = switch (service) {
            case "auth" -> 5433;
            case "project" -> 5434;
            case "issue" -> 5436;
            default -> throw new IllegalArgumentException(service);
        };

        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = datasource(context.getBean(OutboxWriteDatasourcesProperties.class), service);
            assertThat(properties.url()).isEqualTo("r2dbc:postgresql://localhost:" + port + "/" + service + "_db");
        });
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) throws IOException {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    // Keep the tests independent of the developer's real credentials and environment.
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new MapPropertySource("test-runtime-environment", environment));
                    yaml.forEach(sources::addLast);
                });
    }

    private String envKey(String service, String kind, String field) {
        return "ADMIN_" + kind + "_" + service.toUpperCase(Locale.ROOT) + "_R2DBC_" + field;
    }

    private OutboxWriteDatasourcesProperties.DatasourceProperties datasource(
            OutboxWriteDatasourcesProperties properties, String service) {
        return switch (service) {
            case "auth" -> properties.auth();
            case "project" -> properties.project();
            case "issue" -> properties.issue();
            default -> throw new IllegalArgumentException(service);
        };
    }
}
