package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class McpToolPolicyTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String USER = "11111111-1111-4111-8111-111111111111";
  static final String TOKEN_ID = "22222222-2222-4222-8222-222222222222";
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> audit = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger("ru.taska.mcp.audit");
  private McpRequestContext context;
  private GatewayClient gateway;

  @BeforeEach
  void setup() {
    audit.start();
    logger.addAppender(audit);
    context =
        new McpRequestContext(
            "policy-test",
            McpAuthenticationFilterTest.TOKEN,
            McpAuthenticationFilterTest.user().toBuilder()
                .setUserId(USER)
                .setTokenId(TOKEN_ID)
                .addScopes("write")
                .build());
    gateway = mock(GatewayClient.class);
    when(gateway.get(any(), any(), any(), any()))
        .thenReturn(Mono.just(JSON.createObjectNode().putArray("items")));
  }

  @AfterEach
  void stop() {
    logger.detachAppender(audit);
    audit.stop();
    meters.close();
  }

  @Test
  void cancellingAllFourCallsReleasesLeasesAndDoesNotRetryWrites() {
    var executor = executor(new ToolLimits(Duration.ofSeconds(20), 131072, 4000, 8192));
    var starts = new AtomicInteger();
    var cancels = new AtomicInteger();
    var registration =
        registration(
            true,
            call ->
                Mono.defer(
                    () -> {
                      starts.incrementAndGet();
                      return Mono.<ToolData>never().doOnCancel(cancels::incrementAndGet);
                    }));
    List<Disposable> pending = new ArrayList<>();
    for (int n = 0; n < 4; n++) pending.add(run(executor, registration).subscribe());
    var rejected = run(executor, registration).block();
    assertThat(code(rejected)).isEqualTo("rate_limited");
    assertThat(starts).hasValue(4);
    pending.forEach(Disposable::dispose);
    assertThat(cancels).hasValue(4);
    assertThat(run(executor, registration(true, call -> success())).block().isError()).isFalse();
    assertThat(audit.list).hasSize(6);
    assertThat(
            audit.list.stream()
                .map(event -> JSON.readTree(event.getFormattedMessage()).path("outcome").asText()))
        .containsExactlyInAnyOrder(
            "rate_limited", "cancelled", "cancelled", "cancelled", "cancelled", "success");
    assertThat(meters.get("taska.mcp.limit.rejections").counter().count()).isEqualTo(1);
    assertThat(meters.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags().toString())
                    .doesNotContain(USER, TOKEN_ID, context.requestId()));
  }

  @Test
  void timeoutCancelsDownstreamAndAuditsTimeoutRatherThanCancellationWithoutAutomaticRetry() {
    var cancels = new AtomicInteger();
    var starts = new AtomicInteger();
    var executor = executor(new ToolLimits(Duration.ofMillis(100), 131072, 4000, 8192));
    var registration =
        registration(
            true,
            call -> {
              starts.incrementAndGet();
              return Mono.<ToolData>never().doOnCancel(cancels::incrementAndGet);
            });
    StepVerifier.withVirtualTime(() -> run(executor, registration))
        .thenAwait(Duration.ofMillis(101))
        .assertNext(
            result -> {
              assertThat(code(result)).isEqualTo("timeout");
              assertThat(JSON.writeValueAsString(result)).contains("не повторяйте");
            })
        .verifyComplete();
    assertThat(starts).hasValue(1);
    assertThat(cancels).hasValue(1);
    assertThat(audit.list).hasSize(1);
    assertThat(JSON.readTree(audit.list.getFirst().getFormattedMessage()).path("outcome").asText())
        .isEqualTo("timeout");
    assertThat(run(executor, registration(true, call -> success())).block().isError()).isFalse();
  }

  @Test
  void auditContainsOnlyIdentitySafeTargetAndFinalOutcomeWithoutArgumentsOrSecret() {
    var executor = executor(ToolLimits.defaults());
    var result =
        run(
                executor,
                registration(
                    true,
                    call -> {
                      call.auditTarget().record("TAS-29");
                      return success();
                    }))
            .block();
    assertThat(result.isError()).isFalse();
    String log = audit.list.getFirst().getFormattedMessage();
    assertThat(log)
        .contains(USER, TOKEN_ID, "write_probe", "TAS-29", "success", "policy-test")
        .doesNotContain(
            McpAuthenticationFilterTest.TOKEN,
            "private comment text",
            "Bearer",
            "body",
            "summary",
            "token_name");
    assertThat(JSON.readTree(log).properties()).hasSize(9);
    assertThat(context.toString()).doesNotContain(McpAuthenticationFilterTest.TOKEN);
    var target = new ToolAuditTarget();
    target.record("Bearer private comment");
    assertThat(target.key()).isNull();
  }

  @Test
  void finalSerializedResultCapIsAuditedAndHandlerRechecksScope() {
    var executor = executor(ToolLimits.defaults());
    var huge =
        registration(
            true,
            call ->
                Mono.just(
                    new ToolData(
                        JSON.createObjectNode().put("ok", "x".repeat(150000)), "summary")));
    assertThat(code(run(executor, huge).block())).isEqualTo("response_too_large");
    assertThat(JSON.readTree(audit.list.getFirst().getFormattedMessage()).path("outcome").asText())
        .isEqualTo("response_too_large");
    context =
        new McpRequestContext(
            context.requestId(),
            context.bearerToken(),
            context.user().toBuilder().clearScopes().addScopes("read").build());
    var starts = new AtomicInteger();
    var denied =
        run(
                executor,
                registration(
                    true,
                    call -> {
                      starts.incrementAndGet();
                      return success();
                    }))
            .block();
    assertThat(code(denied)).isEqualTo("insufficient_scope");
    assertThat(starts).hasValue(0);
    assertThat(audit.list).hasSize(2);
  }

  static ToolRegistration registration(
      boolean write, Function<ToolInvocation, Mono<ToolData>> handler) {
    Map<String, Object> input = Map.of("type", "object");
    Map<String, Object> output =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "request_id",
                Map.of("type", "string"),
                "truncated",
                Map.of("type", "boolean"),
                "data",
                Map.of("type", "object", "properties", Map.of("ok", Map.of())),
                "error",
                Map.of("type", "object"),
                "context",
                Map.of("type", "object")),
            "required",
            List.of("request_id", "truncated"),
            "oneOf",
            List.of(Map.of("required", List.of("data")), Map.of("required", List.of("error"))));
    return new ToolRegistration(
        new ToolDefinition(
            write ? "write_probe" : "read_probe",
            "Probe",
            write ? "write" : "read",
            !write,
            false,
            input,
            output),
        handler);
  }

  private ToolExecutor executor(ToolLimits limits) {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "policy",
        new McpToolPolicy(new McpQuotaLimiter(McpQuotaProperties.defaults()), meters, JSON));
    return new ToolExecutor(
        new ToolResolver(gateway),
        new ToolResultFactory(JSON, limits),
        limits,
        beans.getBeanProvider(ToolInterceptor.class));
  }

  private Mono<McpSchema.CallToolResult> run(ToolExecutor executor, ToolRegistration registration) {
    return executor.execute(
        registration,
        McpTransportContext.create(Map.of(McpRequestContext.KEY, context)),
        new McpSchema.CallToolRequest(
            registration.definition().name(),
            Map.of("body", "private comment text", "issue_key", "TAS-1")));
  }

  static String code(McpSchema.CallToolResult result) {
    return JSON.valueToTree(result.structuredContent()).path("error").path("code").asText("");
  }

  private static Mono<ToolData> success() {
    return Mono.just(new ToolData(JSON.createObjectNode().put("ok", true), "done"));
  }
}
