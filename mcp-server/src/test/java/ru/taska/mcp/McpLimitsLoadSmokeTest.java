package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

/** Local stub smoke, not evidence of stage capacity or a global quota across replicas. */
class McpLimitsLoadSmokeTest {
  @Test
  void fortyParallelAgentCallsReachGatewayOnlyWithinTokenAndUserAdmissionLimits() {
    var requests = new AtomicInteger();
    var active = new AtomicInteger();
    var peak = new AtomicInteger();
    var server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) -> {
                  requests.incrementAndGet();
                  peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                  return response
                      .header("Content-Type", "application/json")
                      .sendString(
                          Mono.delay(Duration.ofMillis(300)).map(ignored -> "{\"ok\":true}"))
                      .then()
                      .doFinally(ignored -> active.decrementAndGet());
                })
            .bindNow();
    var meters = new SimpleMeterRegistry();
    try {
      var gateway =
          new GatewayClient(
              WebClient.builder(),
              McpAuthenticationFilterTest.properties(
                  URI.create("http://localhost:" + server.port()), Duration.ofSeconds(3), 2097152));
      var beans = new DefaultListableBeanFactory();
      beans.registerSingleton(
          "policy",
          new McpToolPolicy(
              new McpQuotaLimiter(McpQuotaProperties.defaults()), McpToolPolicyTest.JSON));
      var executor =
          new ToolExecutor(
              new ToolResolver(gateway),
              new ToolResultFactory(McpToolPolicyTest.JSON, ToolLimits.defaults()),
              ToolLimits.defaults(),
              beans.getBeanProvider(ToolInterceptor.class));
      var registration =
          McpToolPolicyTest.registration(
              false,
              call ->
                  gateway
                      .get(GatewayEndpoint.ME, call.context(), Map.of(), Map.of())
                      .map(value -> new ToolData(value, "stub")));
      List<McpSchema.CallToolResult> results =
          Flux.range(0, 40)
              .flatMap(
                  n -> {
                    String tokenId =
                        n % 2 == 0
                            ? McpToolPolicyTest.TOKEN_ID
                            : "33333333-3333-4333-8333-333333333333";
                    var context =
                        new McpRequestContext(
                            "load-" + n,
                            McpAuthenticationFilterTest.TOKEN,
                            McpAuthenticationFilterTest.user().toBuilder()
                                .setUserId(McpToolPolicyTest.USER)
                                .setTokenId(tokenId)
                                .build());
                    return executor.execute(
                        registration,
                        McpTransportContext.create(Map.of(McpRequestContext.KEY, context)),
                        new McpSchema.CallToolRequest("read_probe", Map.of()));
                  },
                  40)
              .collectList()
              .block(Duration.ofSeconds(10));
      assertThat(results).hasSize(40);
      assertThat(results.stream().filter(result -> !Boolean.TRUE.equals(result.isError())))
          .hasSize(8);
      assertThat(
              results.stream()
                  .filter(result -> "rate_limited".equals(McpToolPolicyTest.code(result))))
          .hasSize(32);
      assertThat(requests).hasValue(8);
      assertThat(peak.get()).isBetween(1, 8);
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(active).hasValue(0));
    } finally {
      meters.close();
      server.disposeNow();
    }
  }
}
