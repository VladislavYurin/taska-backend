package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class McpObservabilityTest {
  private final PrometheusMeterRegistry meters =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
  private final ToolRegistration registration =
      McpToolPolicyTest.registration(false, call -> Mono.never());
  private final McpObservability observations;

  McpObservabilityTest() {
    beans.registerSingleton("tools", (ToolContributor) () -> List.of(registration));
    observations =
        new McpObservability(
            meters,
            new ToolRegistry(beans.getBeanProvider(ToolContributor.class)),
            McpToolPolicyTest.JSON);
  }

  @AfterEach
  void close() {
    meters.close();
  }

  @Test
  void exportsBoundedCountersAndAggregableHistogramWithoutIdentityTags() {
    var call = call(registration, "request-123", McpCallMetadata.NONE);
    for (String outcome :
        List.of(
            "success",
            "unauthenticated",
            "insufficient_scope",
            "not_found",
            "conflict",
            "rate_limited",
            "upstream_error",
            "timeout")) observations.observe(call, outcome, 250_000_000);
    for (int index = 0; index < 100; index++) {
      var definition =
          new ToolDefinition(
              "untrusted_" + index,
              "private",
              "read",
              true,
              true,
              registration.definition().inputSchema(),
              registration.definition().outputSchema());
      observations.observe(
          call(
              new ToolRegistration(definition, ignored -> Mono.never()),
              "request-123",
              McpCallMetadata.NONE),
          "untrusted_" + index,
          -1);
    }
    String scrape = meters.scrape();
    assertThat(scrape)
        .contains(
            "taska_mcp_tool_calls_total{outcome=\"ok\",tool=\"read_probe\"} 1.0",
            "taska_mcp_tool_duration_seconds_bucket{tool=\"read_probe\",le=\"0.5\"}",
            "taska_mcp_limit_rejections_total{tool=\"read_probe\"} 1.0");
    assertThat(meters.get("taska.mcp.tool.duration").tag("tool", "read_probe").timer().count())
        .isEqualTo(8);
    assertThat(
            meters
                .get("taska.mcp.tool.calls")
                .tag("tool", "unknown")
                .tag("outcome", "upstream_error")
                .counter()
                .count())
        .isEqualTo(100);
    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getKey()).isIn("tool", "outcome")));
    assertThat(scrape)
        .doesNotContain(
            "untrusted_", "user-1", "token-id", "request-123", McpAuthenticationFilterTest.TOKEN);
  }

  @Test
  void activeRequestsReleaseOnSuccessErrorSynchronousFailureAndCancellation() {
    assertThat(active()).isZero();
    observations
        .request(
            () -> {
              assertThat(active()).isEqualTo(1);
              return Mono.empty();
            })
        .block();
    assertThat(active()).isZero();
    StepVerifier.create(
            observations.request(() -> Mono.error(new IllegalStateException("private"))))
        .expectError(IllegalStateException.class)
        .verify();
    assertThat(active()).isZero();
    StepVerifier.create(
            observations.request(
                () -> {
                  throw new IllegalArgumentException("private");
                }))
        .expectError(IllegalArgumentException.class)
        .verify();
    var subscription = observations.request(Mono::never).subscribe();
    assertThat(active()).isEqualTo(1);
    subscription.dispose();
    assertThat(active()).isZero();
  }

  @Test
  void logsContainOnlySafeCorrelationAndNormalizedMetadata() {
    var logger = (Logger) LoggerFactory.getLogger(McpObservability.class);
    var events = new ListAppender<ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    try {
      String trace = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
      observations.observe(
          call(registration, "request-123", new McpCallMetadata("Taska-Test", "1.2.3", trace)),
          "success",
          123_000_000);
      observations.observe(
          call(
              registration,
              McpAuthenticationFilterTest.TOKEN,
              new McpCallMetadata(
                  "private comment\n" + McpAuthenticationFilterTest.TOKEN,
                  "private version",
                  "private trace")),
          "private error",
          1);
      observations.authRejected(McpAuthenticationFilterTest.TOKEN, "private outcome", 503);
      assertThat(events.list).hasSize(3);
      String logs = events.list.toString();
      assertThat(logs)
          .contains(
              "mcp_tool",
              "request-123",
              "\"duration_ms\":123",
              "\"client_name\":\"taska-test\"",
              trace,
              "redacted");
      assertThat(logs)
          .doesNotContain(
              McpAuthenticationFilterTest.TOKEN,
              "private",
              "user-1",
              "token-id",
              "secret body",
              "project_key",
              "user_id",
              "token_id");
      assertThat(
              McpToolPolicyTest.JSON
                  .readTree(events.list.getFirst().getFormattedMessage())
                  .path("outcome")
                  .asText())
          .isEqualTo("ok");
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void authenticationRejectsAreCountedByBoundaryReasonAndSensitiveCorrelationIsReplaced() {
    for (String outcome : List.of("auth", "scope", "upstream_error")) {
      var filter =
          new McpAuthenticationFilter(
              (token, id) -> {
                assertThat(id).doesNotContain("tsk_pat_");
                return switch (outcome) {
                  case "auth" -> Mono.empty();
                  case "scope" ->
                      Mono.just(
                          McpAuthenticationFilterTest.user().toBuilder().clearScopes().build());
                  default -> Mono.error(io.grpc.Status.UNAVAILABLE.asRuntimeException());
                };
              },
              McpAuthenticationFilterTest.properties(
                  java.net.URI.create("http://localhost:8080"), Duration.ofSeconds(1), 1024),
              observations);
      var request =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/mcp")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + McpAuthenticationFilterTest.TOKEN)
                  .header("X-Request-Id", McpAuthenticationFilterTest.TOKEN));
      filter
          .filter(request, ignored -> Mono.error(new AssertionError("must not dispatch")))
          .block();
      assertThat(request.getResponse().getStatusCode())
          .isEqualTo(
              switch (outcome) {
                case "auth" -> HttpStatus.UNAUTHORIZED;
                case "scope" -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
              });
      assertThat(request.getResponse().getHeaders().getFirst("X-Request-Id"))
          .matches("[0-9a-f-]{36}");
      assertThat(meters.get("taska.mcp.auth.rejections").tag("outcome", outcome).counter().count())
          .isEqualTo(1);
      assertThat(active()).isZero();
    }
  }

  private double active() {
    return meters.get("taska.mcp.requests.active").gauge().value();
  }

  private ToolInvocation call(ToolRegistration tool, String id, McpCallMetadata metadata) {
    var context =
        new McpRequestContext(
            id, McpAuthenticationFilterTest.TOKEN, McpAuthenticationFilterTest.user(), metadata);
    return new ToolInvocation(
        tool.definition(),
        context,
        new ToolArguments(Map.of("body", "secret body", "project_key", "SECRET")),
        mock(ToolResolver.Session.class));
  }
}
