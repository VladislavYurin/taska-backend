package ru.taska.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.reactive.CorsWebFilter;
import ru.taska.config.props.CorsProperties;

import static org.assertj.core.api.Assertions.assertThat;

class CorsMaxAgeTest {
    private static final String ORIGIN = "https://taska.ozero.dev";
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class, CorsConfig.class)
            .withPropertyValues(
                    "app.cors.allowed-origins=" + ORIGIN,
                    "app.cors.allow-credentials=true",
                    "app.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD",
                    "app.cors.allowed-headers=Authorization,Content-Type",
                    "app.cors.exposed-headers=X-Request-Id");

    @Test
    void preflight_usesDefaultMaxAgeOnPublicAndProtectedRoutes() {
        contextRunner.run(context -> {
            WebTestClient client = client(context.getBean(CorsWebFilter.class));
            for (String path : new String[]{"/api/v1/auth/login", "/api/v1/projects", "/api/v1/issues"}) {
                preflight(client, path).expectHeader()
                        .valueEquals(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "7200");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 600, 7200})
    void preflight_usesConfiguredMaxAge(long maxAge) {
        contextRunner.withPropertyValues("app.cors.max-age=" + maxAge).run(context ->
                preflight(client(context.getBean(CorsWebFilter.class)), "/api/v1/projects")
                        .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_MAX_AGE, Long.toString(maxAge)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid"})
    void invalidMaxAge_preventsStartup(String maxAge) {
        contextRunner.withPropertyValues("app.cors.max-age=" + maxAge).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasCauseInstanceOf(ConfigurationPropertiesBindException.class);
        });
    }

    private static WebTestClient client(CorsWebFilter filter) {
        return WebTestClient.bindToWebHandler(exchange -> exchange.getResponse().setComplete())
                .webFilter(filter).configureClient().baseUrl("https://api.taska.ozero.dev").build();
    }

    private static WebTestClient.ResponseSpec preflight(WebTestClient client, String path) {
        return client.options().uri(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class PropertiesConfiguration {
    }
}
