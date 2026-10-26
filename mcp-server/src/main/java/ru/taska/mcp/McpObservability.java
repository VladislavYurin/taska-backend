package ru.taska.mcp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Final results only: no arguments, result text, credentials or identity labels. */
@Component
public class McpObservability implements ToolInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(McpObservability.class);
  private static final Set<String> OUTCOMES =
      Set.of(
          "ok",
          "auth",
          "scope",
          "not_found",
          "conflict",
          "rate_limited",
          "upstream_error",
          "timeout",
          "invalid_argument",
          "failed_precondition",
          "response_too_large",
          "partial_success",
          "cancelled");
  private static final Set<String> AUTH_OUTCOMES =
      Set.of("auth", "scope", "origin", "upstream_error");
  private final MeterRegistry meters;
  private final Set<String> tools;
  private final JsonMapper mapper;
  private final AtomicInteger active = new AtomicInteger();

  public McpObservability(MeterRegistry meters, ToolRegistry registry, JsonMapper mapper) {
    this.meters = meters;
    this.mapper = mapper;
    this.tools =
        registry.tools().stream()
            .map(tool -> tool.definition().name())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Gauge.builder("taska.mcp.requests.active", active, AtomicInteger::get)
        .description("Currently subscribed MCP HTTP requests, including authentication")
        .register(meters);
  }

  Mono<Void> request(Supplier<Mono<Void>> next) {
    return Mono.defer(
        () -> {
          active.incrementAndGet();
          return Mono.defer(next).doFinally(ignored -> active.decrementAndGet());
        });
  }

  @Override
  public Mono<ToolData> intercept(ToolInvocation call, Supplier<Mono<ToolData>> next) {
    return Mono.defer(next);
  }

  @Override
  public void observe(ToolInvocation call, String requestedOutcome, long durationNanos) {
    String tool = tools.contains(call.definition().name()) ? call.definition().name() : "unknown";
    String outcome = outcome(requestedOutcome);
    long duration = Math.max(0, durationNanos);
    meters.counter("taska.mcp.tool.calls", "tool", tool, "outcome", outcome).increment();
    // Outcome belongs on the counter only, avoiding tool × outcome histogram cardinality.
    Timer.builder("taska.mcp.tool.duration")
        .tag("tool", tool)
        .description("Final MCP tool duration including projection and result limits")
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofSeconds(30))
        .serviceLevelObjectives(
            Duration.ofMillis(100),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(20))
        .register(meters)
        .record(duration, TimeUnit.NANOSECONDS);
    if ("rate_limited".equals(outcome))
      meters.counter("taska.mcp.limit.rejections", "tool", tool).increment();
    var fields = fields("mcp_tool", call.context().requestId());
    fields.put("tool", tool);
    fields.put("outcome", outcome);
    fields.put("duration_ms", TimeUnit.NANOSECONDS.toMillis(duration));
    var metadata =
        call.context().metadata() == null ? McpCallMetadata.NONE : call.context().metadata();
    fields.put("client_name", metadata.clientName());
    if (metadata.clientVersion() != null) fields.put("client_version", metadata.clientVersion());
    if (metadata.traceparent() != null) fields.put("traceparent", metadata.traceparent());
    LOG.info("{}", mapper.writeValueAsString(fields));
  }

  void authRejected(String requestId, String requestedOutcome, int status) {
    String outcome = AUTH_OUTCOMES.contains(requestedOutcome) ? requestedOutcome : "upstream_error";
    meters.counter("taska.mcp.auth.rejections", "outcome", outcome).increment();
    var fields = fields("mcp_auth_rejected", requestId);
    fields.put("outcome", outcome);
    fields.put("status", status);
    LOG.info("{}", mapper.writeValueAsString(fields));
  }

  static String outcome(String value) {
    if ("success".equals(value)) return "ok";
    if ("unauthenticated".equals(value)) return "auth";
    if ("insufficient_scope".equals(value)) return "scope";
    return value != null && OUTCOMES.contains(value) ? value : "upstream_error";
  }

  private static LinkedHashMap<String, Object> fields(String event, String requestId) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("event", event);
    fields.put("request_id", McpRequestIds.safe(requestId) ? requestId : "redacted");
    return fields;
  }
}
