package ru.taska.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ResourceListTransportTest {
  final ResourceServiceTest fixture = new ResourceServiceTest();
  final Delegate delegate = new Delegate();
  int forwarded;
  ResourceListTransport transport;
  McpTransportContext context = ResourceServiceTest.context(ResourceServiceTest.TOKEN_A);

  @BeforeEach
  void setup() {
    fixture.setup();
    transport = new ResourceListTransport(delegate, fixture.service);
    transport.setMcpHandler(
        new McpStatelessServerHandler() {
          public Mono<McpSchema.JSONRPCResponse> handleRequest(
              McpTransportContext ctx, McpSchema.JSONRPCRequest request) {
            forwarded++;
            return Mono.just(McpSchema.JSONRPCResponse.result(request.id(), "sdk-owned"));
          }

          public Mono<Void> handleNotification(
              McpTransportContext ctx, McpSchema.JSONRPCNotification request) {
            forwarded++;
            return Mono.empty();
          }
        });
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  @Test
  void onlyRequestScopedListIsOverriddenAndLifecycleStaysSdkOwned() {
    var response =
        delegate
            .handler
            .handleRequest(context, new McpSchema.JSONRPCRequest("resources/list", 7, Map.of()))
            .block();
    assertThat(response.error()).isNull();
    assertThat(response.id()).isEqualTo(7);
    assertThat(((McpSchema.ListResourcesResult) response.result()).resources()).hasSize(1);
    assertThat(forwarded).isZero();
    assertThat(
            delegate
                .handler
                .handleRequest(context, new McpSchema.JSONRPCRequest("resources/templates/list", 8))
                .block()
                .result())
        .isEqualTo("sdk-owned");
    delegate
        .handler
        .handleNotification(context, new McpSchema.JSONRPCNotification("notifications/initialized"))
        .block();
    assertThat(forwarded).isEqualTo(2);
    assertThat(transport.protocolVersions()).containsExactly("2025-11-25");
    transport.closeGracefully().block();
    assertThat(delegate.closed).isTrue();
  }

  @Test
  void malformedListParametersFailBeforeBusinessRead() {
    for (Object params :
        List.of(
            "bad",
            Map.of("cursor", "unknown"),
            Map.of("_meta", "not-an-object"),
            Map.of("url", "https://evil"))) {
      var response =
          delegate
              .handler
              .handleRequest(context, new McpSchema.JSONRPCRequest("resources/list", 9, params))
              .block();
      assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
      assertThat(response.id()).isEqualTo(9);
    }
    assertThat(fixture.requests).isEmpty();
    assertThat(forwarded).isZero();
  }

  static final class Delegate implements McpStatelessServerTransport {
    McpStatelessServerHandler handler;
    boolean closed;

    public void setMcpHandler(McpStatelessServerHandler value) {
      handler = value;
    }

    public Mono<Void> closeGracefully() {
      closed = true;
      return Mono.empty();
    }

    public List<String> protocolVersions() {
      return List.of("2025-11-25");
    }
  }
}
