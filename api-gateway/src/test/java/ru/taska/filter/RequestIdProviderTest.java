package ru.taska.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestIdProvider Tests")
class RequestIdProviderTest {

    private final RequestIdProvider provider = new RequestIdProvider();

    @Test
    @DisplayName("Должен вернуть значение заголовка X-Request-Id, если он присутствует")
    void resolve_headerPresent_returnsHeaderValue() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("X-Request-Id", "my-request-id")
                        .build()
        );

        String result = provider.resolve(exchange);

        assertThat(result).isEqualTo("my-request-id");
    }

    @Test
    @DisplayName("Должен сгенерировать UUID, если заголовок X-Request-Id отсутствует")
    void resolve_headerMissing_generatesUuid() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build()
        );

        String result = provider.resolve(exchange);

        assertThat(result).isNotBlank();
        assertThat(result).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Должен сгенерировать UUID, если заголовок X-Request-Id содержит только пробелы")
    void resolve_headerBlank_generatesUuid() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("X-Request-Id", "   ")
                        .build()
        );

        String result = provider.resolve(exchange);

        assertThat(result).isNotBlank();
        assertThat(result).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Должен генерировать уникальные UUID при каждом вызове без заголовка")
    void resolve_calledTwiceWithoutHeader_generatesDifferentUuids() {
        var exchange1 = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        var exchange2 = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        String id1 = provider.resolve(exchange1);
        String id2 = provider.resolve(exchange2);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("Должен возвращать тот же ID при повторном вызове с тем же exchange")
    void resolve_calledTwiceOnSameExchange_returnsSameId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());

        String id1 = provider.resolve(exchange);
        String id2 = provider.resolve(exchange);

        assertThat(id1).isEqualTo(id2);
    }
}
