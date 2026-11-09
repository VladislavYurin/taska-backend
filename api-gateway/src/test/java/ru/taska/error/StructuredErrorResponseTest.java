package ru.taska.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Map;
import mapper.GrpcErrorDetails;
import mapper.GrpcExceptionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.json.JsonMapper;

class StructuredErrorResponseTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final GatewayErrorHandler handler = new GatewayErrorHandler(json, new RestErrorMapper());

  @Test
  void domainDetailsReachJsonWithoutParsingHumanMessage() {
    Map<String, String> details =
        Map.of(
            "lockedUntil", "2026-09-22T11:55:50.398486Z", "reason", "Блокировка 🔒\n\"try later\"");
    var error =
        GrpcExceptionMapper.toStatusRuntimeException(
            new DomainException(
                DomainStatus.PERMISSION_DENIED, "Account temporarily locked", details));
    assertThat(error.getTrailers().get(GrpcErrorDetails.KEY).chars().allMatch(c -> c < 128))
        .isTrue();
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));
    StepVerifier.create(handler.handleError(exchange, error, "request-239")).verifyComplete();
    assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
        .isEqualTo("request-239");
    var body = json.readTree(exchange.getResponse().getBodyAsString().block());
    assertThat(body.get("code").asString()).isEqualTo("PERMISSION_DENIED");
    assertThat(body.get("message").asString()).isEqualTo("Account temporarily locked");
    details.forEach(
        (key, value) -> assertThat(body.get("details").get(key).asString()).isEqualTo(value));
  }

  @Test
  void trustedProblemCodeRefinesTheAuthoritativeTransportStatus() {
    org.springframework.test.util.ReflectionTestUtils.setField(handler, "trustedEnabled", true);
    Object[][] rows = {
      {DomainStatus.PERMISSION_DENIED, "PROJECT_MEMBERSHIP_REQUIRED", 403},
      {DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED", 401},
      {DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", 503},
      {DomainStatus.ABORTED, "VERSION_CONFLICT", 412},
      {DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT", 428},
      {DomainStatus.FAILED_PRECONDITION, "INVALID_STATE", 409},
      {DomainStatus.FAILED_PRECONDITION, "QUOTA_EXCEEDED", 422}
    };
    for (var row : rows) {
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/projects"));
      var error =
          GrpcExceptionMapper.toStatusRuntimeException(
              new DomainException(
                  (DomainStatus) row[0], "Fixed message", Map.of("reason", (String) row[1])));
      handler.handleError(exchange, error, "owned-trace").block();
      assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(row[2]);
      assertThat(exchange.getResponse().getHeaders().getContentType())
          .isEqualTo(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
      var body = json.readTree(exchange.getResponse().getBodyAsString().block());
      assertThat(body.path("code").asString()).isEqualTo(row[1]);
      assertThat(body.path("status").asInt()).isEqualTo(row[2]);
      assertThat(body.path("requestId").asString()).isEqualTo("owned-trace");
      assertThat(body.path("reasons").get(0).asString()).isEqualTo(row[1]);
      assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
          .isEqualTo("owned-trace");
    }
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/projects"));
    handler
        .handleError(
            exchange,
            GrpcExceptionMapper.toStatusRuntimeException(
                new DomainException(
                    DomainStatus.PERMISSION_DENIED,
                    "Denied",
                    Map.of("code", "READ_ONLY", "reason", "OWNER_PAUSED"))),
            "owned-trace")
        .block();
    var body = json.readTree(exchange.getResponse().getBodyAsString().block());
    assertThat(body.path("code").asString()).isEqualTo("READ_ONLY");
    assertThat(body.path("reasons").get(0).asString()).isEqualTo("OWNER_PAUSED");
  }

  @Test
  void trustedMissingCredentialAndDependencyTimeoutHaveStableProblemCodes() {
    org.springframework.test.util.ReflectionTestUtils.setField(handler, "trustedEnabled", true);
    var missing = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/projects"));
    handler
        .handleError(
            missing,
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing bearer"),
            "trace")
        .block();
    assertThat(missing.getResponse().getStatusCode().value()).isEqualTo(401);
    assertThat(
            json.readTree(missing.getResponse().getBodyAsString().block()).path("code").asString())
        .isEqualTo("AUTHENTICATION_REQUIRED");
    var timeout = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/projects"));
    handler.handleError(timeout, Status.DEADLINE_EXCEEDED.asRuntimeException(), "trace").block();
    assertThat(timeout.getResponse().getStatusCode().value()).isEqualTo(503);
    assertThat(
            json.readTree(timeout.getResponse().getBodyAsString().block()).path("code").asString())
        .isEqualTo("DEPENDENCY_UNAVAILABLE");
  }

  @Test
  void missingOrMalformedDetailsPreserveOriginalError() {
    for (String value :
        new String[] {null, "{broken", "[]", "null", "{\"key\":123}", "{\"key\":null}"}) {
      Metadata trailers = new Metadata();
      if (value != null) {
        trailers.put(GrpcErrorDetails.KEY, value);
      }
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test"));
      StepVerifier.create(
              handler.handleError(
                  exchange,
                  Status.ABORTED.withDescription("Version conflict").asRuntimeException(trailers),
                  "request-239"))
          .verifyComplete();
      assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(409);
      var body = json.readTree(exchange.getResponse().getBodyAsString().block());
      assertThat(body.get("code").asString()).isEqualTo("ABORTED");
      assertThat(body.get("message").asString()).isEqualTo("Version conflict");
      assertThat(body.get("details") == null || body.get("details").isNull()).isTrue();
    }
  }
}
