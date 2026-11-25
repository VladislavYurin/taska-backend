package ru.taska.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AttachmentUploadDependencyErrorTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void uploadDependencyFailureIsSafeProblemOnlyInTrustedProfile(boolean trusted) {
    var json = JsonMapper.builder().build();
    var handler = new GatewayErrorHandler(json, new RestErrorMapper());
    ReflectionTestUtils.setField(handler, "trustedEnabled", trusted);
    for (var status : new Status[] {Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED}) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/v1/projects/p/issues/i/attachments/upload-url"));
      handler
          .handleError(
              exchange,
              status.withDescription("private dependency diagnostic").asRuntimeException(),
              "owned-trace")
          .block();
      assertThat(exchange.getResponse().getStatusCode().value())
          .isEqualTo(trusted || status.getCode() == Status.Code.UNAVAILABLE ? 503 : 504);
      var body = json.readTree(exchange.getResponse().getBodyAsString().block());
      assertThat(body.path("code").asString())
          .isEqualTo(trusted ? "DEPENDENCY_UNAVAILABLE" : status.getCode().name());
      assertThat(body.path("requestId").asString()).isEqualTo("owned-trace");
      assertThat(exchange.getResponse().getHeaders().getContentType())
          .isEqualTo(trusted ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
      if (trusted) {
        assertThat(body.path("details").path("code").asString())
            .isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(body.path("message").asString()).doesNotContain("private dependency diagnostic");
      }
    }
  }
}
