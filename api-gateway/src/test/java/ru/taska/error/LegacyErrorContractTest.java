package ru.taska.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class LegacyErrorContractTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void existingRestErrorsKeepTheirPublishedContract(boolean trusted) {
    var handler = new GatewayErrorHandler(json, new RestErrorMapper());
    ReflectionTestUtils.setField(handler, "trustedEnabled", trusted);
    Object[][] cases = {
      {"/api/v1/projects/p/issues", Status.FAILED_PRECONDITION, "PROJECT_ARCHIVED", 400},
      {"/api/v1/projects/p/members/u", Status.FAILED_PRECONDITION, "LAST_ADMIN", 400},
      {"/api/v1/users/me", Status.INVALID_ARGUMENT, "DISPLAY_NAME_INVALID", 400},
      {"/api/v1/auth/login", Status.PERMISSION_DENIED, "ACCOUNT_LOCKED", 403},
      {"/api/v1/issues/i/transitions", Status.FAILED_PRECONDITION, "INVALID_STATE", 400},
      {"/api/v1/issues/i", Status.NOT_FOUND, null, 404},
      {"/api/v1/projects", Status.UNAVAILABLE, null, 503},
      {"/api/v1/projects", Status.DEADLINE_EXCEEDED, null, 504}
    };
    for (var row : cases) {
      var status = (Status) row[1];
      String reason = (String) row[2];
      Map<String, String> details = reason == null ? Map.of()
          : Map.of("reason", reason, "code", reason, "lockedUntil", "2026-11-24T10:00:00Z");
      var exchange = MockServerWebExchange.from(MockServerHttpRequest.post((String) row[0]));
      handler.handleError(exchange, status.asRuntimeException(mapper.GrpcErrorDetails.encode(details)), "trace").block();
      assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(row[3]);
      assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
      var body = json.readTree(exchange.getResponse().getBodyAsString().block());
      assertThat(body.path("code").asString()).isEqualTo(status.getCode().name());
      assertThat(body.path("requestId").asString()).isEqualTo("trace");
      if (reason != null) {
        assertThat(body.path("details").path("reason").asString()).isEqualTo(reason);
        assertThat(body.path("details").path("lockedUntil").asString()).isEqualTo(details.get("lockedUntil"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingBearerAndLocalValidationKeepLegacyCodes(boolean trusted) {
    var handler = new GatewayErrorHandler(json, new RestErrorMapper());
    ReflectionTestUtils.setField(handler, "trustedEnabled", trusted);
    var missing = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/projects"));
    handler.handleError(missing, new ResponseStatusException(HttpStatus.UNAUTHORIZED), "trace").block();
    assertThat(json.readTree(missing.getResponse().getBodyAsString().block()).path("code").asString())
        .isEqualTo("UNAUTHORIZED");
    var local = MockServerWebExchange.from(MockServerHttpRequest.patch("/api/v1/users/me"));
    handler.handleError(local, new ru.taska.exception.DomainException(
        ru.taska.exception.DomainStatus.INVALID_ARGUMENT, "Invalid display name",
        Map.of("reason", "DISPLAY_NAME_INVALID")), "trace").block();
    var body = json.readTree(local.getResponse().getBodyAsString().block());
    assertThat(body.path("code").asString()).isEqualTo("INVALID_ARGUMENT");
    assertThat(body.path("details").path("reason").asString()).isEqualTo("DISPLAY_NAME_INVALID");
    if (trusted) assertThat(body.path("details").path("code").asString()).isEqualTo("DISPLAY_NAME_INVALID");
  }
}
