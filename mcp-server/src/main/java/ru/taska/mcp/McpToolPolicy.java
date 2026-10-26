package ru.taska.mcp;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpToolPolicy implements ToolInterceptor {
  private static final Set<String> OUTCOMES =
      Set.of(
          "success",
          "cancelled",
          "timeout",
          "upstream_error",
          "rate_limited",
          "response_too_large",
          "not_found",
          "unauthenticated",
          "insufficient_scope",
          "invalid_argument",
          "failed_precondition",
          "conflict",
          "partial_success");
  private final McpQuotaLimiter limiter;
  private final JsonMapper mapper;

  public McpToolPolicy(McpQuotaLimiter limiter, JsonMapper mapper) {
    this.limiter = limiter;
    this.mapper = mapper;
  }

  @Override
  public Mono<ToolData> intercept(ToolInvocation call, Supplier<Mono<ToolData>> next) {
    return Mono.using(
        () ->
            limiter.acquire(
                call.context().user().getUserId(),
                call.context().user().getTokenId(),
                "write".equals(call.definition().scope())),
        ignored -> Mono.defer(next),
        McpQuotaLimiter.Lease::close);
  }

  @Override
  public void observe(ToolInvocation call, String requestedOutcome, long durationNanos) {
    String outcome = OUTCOMES.contains(requestedOutcome) ? requestedOutcome : "upstream_error";
    String tool = call.definition().name();
    if (!"write".equals(call.definition().scope())) return;
    var context = call.context();
    var fields = new LinkedHashMap<String, Object>();
    fields.put("event", "mcp_write");
    fields.put("user_id", uuid(context.user().getUserId()));
    fields.put("token_id", uuid(context.user().getTokenId()));
    fields.put("tool", tool);
    fields.put("outcome", outcome);
    fields.put("request_id", safeRequestId(context.requestId()));
    fields.put("duration_ms", TimeUnit.NANOSECONDS.toMillis(Math.max(0, durationNanos)));
    String objectKey = call.auditTarget().key();
    if (objectKey == null) objectKey = objectKey(call.arguments());
    if (objectKey != null) fields.put("object_key", objectKey);
    var metadata = context.metadata() == null ? McpCallMetadata.NONE : context.metadata();
    fields.put("client_name", metadata.clientName());
    if (metadata.clientVersion() != null) fields.put("client_version", metadata.clientVersion());
    if (metadata.traceparent() != null) fields.put("traceparent", metadata.traceparent());
    LoggerFactory.getLogger("ru.taska.mcp.audit").info("{}", mapper.writeValueAsString(fields));
  }

  private static String uuid(String value) {
    return value.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}") ? value : "invalid";
  }

  private static String safeRequestId(String value) {
    return McpRequestIds.safe(value) ? value : "redacted";
  }

  private static String objectKey(ToolArguments arguments) {
    for (String key : new String[] {"issue_key", "project_key", "notification_id"}) {
      Object value = arguments.values().get(key);
      if (value instanceof String text) {
        String safe = ToolAuditTarget.safe(text);
        if (safe != null) return safe;
      }
    }
    return null;
  }
}
