package ru.taska.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3001",
        "app.cors.allow-credentials=true"
})
@DisplayName("CORS WebFilter Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CorsWebFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3001";
    private static final String PUBLIC_ENDPOINT = "/api/gateway/public";
    private static final String ME_ENDPOINT = "/api/gateway/me";

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeAll
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("Разрешённый origin: ответ содержит CORS-заголовки")
    void getPublicEndpoint_withAllowedOrigin_returnsCorsHeaders() {
        webTestClient.get()
                .uri(PUBLIC_ENDPOINT)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, ".*X-Request-Id.*");
    }

    @Test
    @DisplayName("Preflight OPTIONS для разрешённого origin возвращает CORS-заголовки")
    void preflightOptions_withAllowedOrigin_returnsCorsHeaders() {
        webTestClient.method(HttpMethod.OPTIONS)
                .uri(PUBLIC_ENDPOINT)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ".*GET.*")
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ".*Authorization.*")
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ".*Content-Type.*");
    }

    @Test
    @DisplayName("Запрещённый origin: CORS-заголовок Access-Control-Allow-Origin не возвращается")
    void getPublicEndpoint_withDisallowedOrigin_doesNotReturnAllowOriginHeader() {
        webTestClient.get()
                .uri(PUBLIC_ENDPOINT)
                .header(HttpHeaders.ORIGIN, "https://evil.com")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Защищённый /me без JWT: статус 401, CORS-заголовки для разрешённого origin остаются")
    void getMeEndpoint_withoutJwt_returnsUnauthorizedAndCorsHeaders() {
        webTestClient.get()
                .uri(ME_ENDPOINT)
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
}
