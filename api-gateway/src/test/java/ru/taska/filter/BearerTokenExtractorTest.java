package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BearerTokenExtractor Tests")
class BearerTokenExtractorTest {

    private final BearerTokenExtractor extractor = new BearerTokenExtractor();
    private static final String REQUEST_ID = "test-request-id";

    @Test
    @DisplayName("Должен вернуть токен без префикса Bearer при корректном заголовке")
    void extract_validBearerToken_returnsToken() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer my-token-value")
                        .build()
        );

        String result = extractor.extract(exchange, REQUEST_ID);

        assertThat(result).isEqualTo("my-token-value");
    }

    @Test
    @DisplayName("Должен выбросить 401, если заголовок Authorization отсутствует")
    void extract_missingAuthorizationHeader_throws401() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        assertThatThrownBy(() -> extractor.extract(exchange, REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    var rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    assertThat(rse.getReason()).isEqualTo("Authorization header is missing");
                });
    }

    @Test
    @DisplayName("Должен выбросить 401, если заголовок Authorization не начинается с 'Bearer '")
    void extract_invalidHeaderFormat_throws401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "dXNlcjpwYXNz")
                        .build()
        );

        assertThatThrownBy(() -> extractor.extract(exchange, REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    var rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    assertThat(rse.getReason()).isEqualTo("Invalid Authorization header format");
                });
    }

    @Test
    @DisplayName("Должен выбросить 401, если токен после 'Bearer ' состоит из пробелов")
    void extract_blankTokenAfterBearer_throws401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer    ")
                        .build()
        );

        assertThatThrownBy(() -> extractor.extract(exchange, REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    var rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    assertThat(rse.getReason()).isEqualTo("Bearer token is blank");
                });
    }

    @Test
    @DisplayName("Должен выбросить 401, если после 'Bearer ' нет токена")
    void extract_onlyBearerPrefix_throws401() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                        .build()
        );

        assertThatThrownBy(() -> extractor.extract(exchange, REQUEST_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    var rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    assertThat(rse.getReason()).isEqualTo("Bearer token is blank");
                });
    }
}
