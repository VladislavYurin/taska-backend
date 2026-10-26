package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Public SDK transport SPI: only resources/list becomes request-scoped; all lifecycle stays
 * SDK-owned.
 */
public final class ResourceListTransport implements McpStatelessServerTransport {
  private final McpStatelessServerTransport delegate;
  private final ResourceService resources;

  public ResourceListTransport(McpStatelessServerTransport delegate, ResourceService resources) {
    this.delegate = delegate;
    this.resources = resources;
  }

  @Override
  public void setMcpHandler(McpStatelessServerHandler handler) {
    delegate.setMcpHandler(
        new McpStatelessServerHandler() {
          @Override
          public Mono<McpSchema.JSONRPCResponse> handleRequest(
              McpTransportContext context, McpSchema.JSONRPCRequest request) {
            if (!"resources/list".equals(request.method()))
              return handler.handleRequest(context, request);
            return Mono.defer(
                    () -> {
                      if (request.params() != null) {
                        if (!(request.params() instanceof Map<?, ?> params)
                            || !params.keySet().stream()
                                .allMatch(key -> "cursor".equals(key) || "_meta".equals(key))
                            || params.get("cursor") != null
                            || params.get("_meta") != null
                                && !(params.get("_meta") instanceof Map<?, ?>))
                          return Mono.error(
                              ToolArguments.invalid(
                                  "resources/list отдаёт только последние 20 доступных задач без"
                                      + " cursor; используйте search_issues для страниц."));
                      }
                      return resources
                          .list(context)
                          .map(result -> McpSchema.JSONRPCResponse.result(request.id(), result));
                    })
                .onErrorResume(
                    McpError.class,
                    error ->
                        Mono.just(
                            McpSchema.JSONRPCResponse.error(request.id(), error.getJsonRpcError())))
                .onErrorResume(
                    error ->
                        Mono.just(
                            McpSchema.JSONRPCResponse.error(
                                request.id(),
                                McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                                    .message("Ресурсы временно недоступны; повторите чтение позже.")
                                    .build()
                                    .getJsonRpcError())));
          }

          @Override
          public Mono<Void> handleNotification(
              McpTransportContext context, McpSchema.JSONRPCNotification notification) {
            return handler.handleNotification(context, notification);
          }
        });
  }

  @Override
  public Mono<Void> closeGracefully() {
    return delegate.closeGracefully();
  }

  @Override
  public List<String> protocolVersions() {
    return delegate.protocolVersions();
  }
}
