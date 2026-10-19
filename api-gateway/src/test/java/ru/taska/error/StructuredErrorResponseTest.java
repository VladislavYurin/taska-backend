package ru.taska.error;

import io.grpc.Metadata;
import io.grpc.Status;
import mapper.GrpcErrorDetails;
import mapper.GrpcExceptionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredErrorResponseTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final GatewayErrorHandler handler = new GatewayErrorHandler(json, new RestErrorMapper());

    @Test
    void domainDetailsReachJsonWithoutParsingHumanMessage() {
        Map<String, String> details = Map.of("lockedUntil", "2026-09-22T11:55:50.398486Z",
                "reason", "Блокировка 🔒\n\"try later\"");
        var error = GrpcExceptionMapper.toStatusRuntimeException(
                new DomainException(DomainStatus.PERMISSION_DENIED, "Account temporarily locked", details));
        assertThat(error.getTrailers().get(GrpcErrorDetails.KEY).chars().allMatch(c -> c < 128)).isTrue();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));
        StepVerifier.create(handler.handleError(exchange, error, "request-239")).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo("request-239");
        var body = json.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(body.get("message").asString()).isEqualTo("Account temporarily locked");
        details.forEach((key, value) -> assertThat(body.get("details").get(key).asString()).isEqualTo(value));
    }

    @Test
    void missingOrMalformedDetailsPreserveOriginalError() {
        for (String value : new String[]{null, "{broken", "[]", "null", "{\"key\":123}", "{\"key\":null}"}) {
            Metadata trailers = new Metadata();
            if (value != null) {
                trailers.put(GrpcErrorDetails.KEY, value);
            }
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test"));
            StepVerifier.create(handler.handleError(exchange,
                    Status.ABORTED.withDescription("Version conflict").asRuntimeException(trailers), "request-239"))
                    .verifyComplete();
            assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(409);
            var body = json.readTree(exchange.getResponse().getBodyAsString().block());
            assertThat(body.get("code").asString()).isEqualTo("ABORTED");
            assertThat(body.get("message").asString()).isEqualTo("Version conflict");
            assertThat(body.get("details") == null || body.get("details").isNull()).isTrue();
        }
    }
}
