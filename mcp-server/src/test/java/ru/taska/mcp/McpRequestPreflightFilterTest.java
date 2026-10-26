package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class McpRequestPreflightFilterTest {
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final AtomicInteger dispatched = new AtomicInteger();
  private final AtomicReference<String> forwarded = new AtomicReference<>();
  private final AtomicReference<McpRequestContext> context = new AtomicReference<>();
  private final ToolDefinition write =
      new ToolDefinition(
          "write_probe",
          "write",
          "write",
          false,
          false,
          Map.of(
              "type",
              "object",
              "properties",
              Map.of("body", Map.of("type", "string")),
              "required",
              List.of("body"),
              "additionalProperties",
              false),
          Map.of());

  @Test
  void knownValidWriteRequiresScopeBeforeDispatchButInvalidProtocolOrSchemaBelongsToSdk() {
    var denied = exchange(call("write_probe", "{\"body\":\"secret\"}"));
    filter().filter(denied, this::dispatch).block();
    assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(denied.getResponse().getHeaders().getFirst("WWW-Authenticate"))
        .isEqualTo("Bearer error=\"insufficient_scope\", scope=\"write\"");
    assertThat(dispatched).hasValue(0);
    for (String body :
        List.of(
            "{",
            "[]",
            "{\"jsonrpc\":\"2.0\",\"id\":1}",
            "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"tools/call\"}",
            call("unknown", "{}"),
            call("write_probe", "{}"),
            call("write_probe", "{\"body\":13}"),
            call("write_probe", "{\"body\":\"a\",\"url\":\"https://evil\"}"))) {
      filter().filter(exchange(body), this::dispatch).block();
      assertThat(forwarded.get()).isEqualTo(body);
    }
    assertThat(dispatched).hasValue(8);
  }

  @Test
  void writeScopePassesExactBytesAndOnlyNormalizedMetadataSurvives() {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"write_probe\",\"arguments\":{\"body\":\"private"
            + " text\"},\"_meta\":{\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"1.2.3\"},\"traceparent\":\"00-11111111111111111111111111111111-2222222222222222-01\",\"private\":\"never"
            + " retain\"}}}";
    var exchange = exchange(body);
    var original = (McpRequestContext) exchange.getAttribute(McpRequestContext.KEY);
    exchange
        .getAttributes()
        .put(
            McpRequestContext.KEY,
            new McpRequestContext(
                original.requestId(),
                original.bearerToken(),
                original.user().toBuilder().addScopes("write").build()));
    filter().filter(exchange, this::dispatch).block();
    assertThat(forwarded.get()).isEqualTo(body);
    assertThat(context.get().metadata())
        .isEqualTo(
            new McpCallMetadata(
                "claude-code", "1.2.3", "00-11111111111111111111111111111111-2222222222222222-01"));
    assertThat(context.get().toString())
        .doesNotContain("private", "never retain", McpAuthenticationFilterTest.TOKEN);
  }

  @Test
  void declaredAndChunkedBodiesAreCappedBeforeDispatchIncludingEncodedRoute() {
    var declared =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/mcp").contentLength(65537).body("not read"));
    filter().filter(declared, this::dispatch).block();
    assertThat(declared.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    var factory = new DefaultDataBufferFactory();
    var chunked =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/mcp;path=x")
                .body(Flux.just(factory.wrap(new byte[40000]), factory.wrap(new byte[40000]))));
    filter().filter(chunked, this::dispatch).block();
    assertThat(chunked.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(dispatched).hasValue(0);
    String boundary = " ".repeat(65536);
    filter().filter(exchange(boundary), this::dispatch).block();
    assertThat(forwarded.get()).hasSize(65536);
  }

  @Test
  void hostileClientInfoAndTraceCannotCarryArbitraryContentIntoAudit() {
    var meta =
        McpCallMetadata.from(
            mapper.readTree(
                "{\"clientInfo\":{\"name\":\"tsk_pat_secret comment text\\n"
                    + "\",\"version\":\"1.Bearer-secret\"},\"traceparent\":\"comment"
                    + " body\",\"token\":\"private\"}"));
    assertThat(meta).isEqualTo(new McpCallMetadata("other", null, null));
    assertThat(
            McpCallMetadata.from(
                    mapper.readTree(
                        "{\"traceparent\":\"00-00000000000000000000000000000000-2222222222222222-01\"}"))
                .traceparent())
        .isNull();
  }

  private Mono<Void> dispatch(org.springframework.web.server.ServerWebExchange exchange) {
    dispatched.incrementAndGet();
    context.set((McpRequestContext) exchange.getAttribute(McpRequestContext.KEY));
    return DataBufferUtils.join(exchange.getRequest().getBody())
        .doOnNext(
            buffer -> {
              try {
                forwarded.set(buffer.toString(StandardCharsets.UTF_8));
              } finally {
                DataBufferUtils.release(buffer);
              }
            })
        .then();
  }

  private MockServerWebExchange exchange(String body) {
    var result =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/mcp").contentType(MediaType.APPLICATION_JSON).body(body));
    result
        .getAttributes()
        .put(
            McpRequestContext.KEY,
            new McpRequestContext(
                "test-id", McpAuthenticationFilterTest.TOKEN, McpAuthenticationFilterTest.user()));
    return result;
  }

  private McpRequestPreflightFilter filter() {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "writes",
        (ToolContributor) () -> List.of(new ToolRegistration(write, ignored -> Mono.empty())));
    return new McpRequestPreflightFilter(
        new ToolRegistry(beans.getBeanProvider(ToolContributor.class)),
        new McpTransportLimits(65536),
        mapper,
        beans.getBeanProvider(ToolInterceptor.class));
  }

  private static String call(String tool, String args) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
        + tool
        + "\",\"arguments\":"
        + args
        + "}}";
  }
}
