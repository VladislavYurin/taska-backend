package ru.taska.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded replay of the original bytes. Protocol and schema errors remain owned by the SDK. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class McpRequestPreflightFilter implements WebFilter {
  private final ToolRegistry registry;
  private final McpTransportLimits limits;
  private final JsonMapper mapper;
  private final List<ToolInterceptor> observers;

  public McpRequestPreflightFilter(
      ToolRegistry registry,
      McpTransportLimits limits,
      JsonMapper mapper,
      ObjectProvider<ToolInterceptor> observers) {
    this.registry = registry;
    this.limits = limits;
    this.mapper = mapper;
    this.observers = observers.orderedStream().toList();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!McpAuthenticationFilter.matchesMcp(exchange.getRequest())
        || exchange.getRequest().getMethod() != HttpMethod.POST) return chain.filter(exchange);
    if (exchange.getRequest().getHeaders().getContentLength() > limits.maxRequestBytes())
      return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE, false);
    return DataBufferUtils.join(exchange.getRequest().getBody(), limits.maxRequestBytes())
        .map(
            buffer -> {
              try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
              } finally {
                DataBufferUtils.release(buffer);
              }
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            bytes -> {
              var context = (McpRequestContext) exchange.getAttribute(McpRequestContext.KEY);
              if (context != null && jsonContent(exchange) && deniedWrite(bytes, context, exchange))
                return reject(exchange, HttpStatus.FORBIDDEN, true);
              var request =
                  new ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public Flux<DataBuffer> getBody() {
                      return Flux.defer(
                          () -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                    }
                  };
              return chain.filter(exchange.mutate().request(request).build());
            })
        .onErrorResume(
            DataBufferLimitException.class,
            ignored -> reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE, false));
  }

  private boolean deniedWrite(byte[] bytes, McpRequestContext context, ServerWebExchange exchange) {
    JsonNode root;
    try {
      root = mapper.readTree(bytes);
    } catch (RuntimeException invalidJson) {
      return false;
    }
    if (root == null
        || !root.isObject()
        || !root.path("jsonrpc").isString()
        || !"2.0".equals(root.path("jsonrpc").asText(""))
        || !"tools/call".equals(root.path("method").asText(""))
        || !(root.path("id").isString() || root.path("id").isNumber())) return false;
    JsonNode params = root.path("params"),
        name = params.path("name"),
        args = params.path("arguments");
    if ((params.has("_meta") && !params.get("_meta").isObject())
        || !params.isObject()
        || !name.isString()
        || !(args.isObject() || args.isMissingNode())) return false;
    var metadata = McpCallMetadata.from(params.path("_meta"));
    context =
        new McpRequestContext(context.requestId(), context.bearerToken(), context.user(), metadata);
    exchange.getAttributes().put(McpRequestContext.KEY, context);
    var registration = registry.find(name.asText());
    if (registration == null
        || !"write".equals(registration.definition().scope())
        || context.user().getScopesList().contains("write")) return false;
    Map<String, Object> arguments =
        args.isMissingNode()
            ? Map.of()
            : mapper.convertValue(
                args, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    // Only recognize a valid invocation for the scope challenge. Invalid arguments still reach SDK
    // errors.
    if (!McpJsonDefaults.getSchemaValidator()
        .validate(registration.definition().inputSchema(), arguments)
        .valid()) return false;
    var call =
        new ToolInvocation(registration.definition(), context, new ToolArguments(arguments), null);
    for (var observer : observers) {
      try {
        observer.observe(call, "insufficient_scope", 0);
      } catch (RuntimeException observerFailure) {
        org.slf4j.LoggerFactory.getLogger(McpRequestPreflightFilter.class)
            .warn("MCP scope observer failed");
      }
    }
    return true;
  }

  private static boolean jsonContent(ServerWebExchange exchange) {
    try {
      return org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(
          exchange.getRequest().getHeaders().getContentType());
    } catch (IllegalArgumentException invalidContentType) {
      return false;
    }
  }

  private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, boolean scope) {
    exchange.getResponse().setStatusCode(status);
    if (scope)
      exchange
          .getResponse()
          .getHeaders()
          .set(
              HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"write\"");
    return exchange.getResponse().setComplete();
  }
}
