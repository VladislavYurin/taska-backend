package ru.taska.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ToolExecutor {
  private final ToolResolver resolver;
  private final ToolResultFactory results;
  private final ToolLimits limits;
  private final List<ToolInterceptor> interceptors;

  public ToolExecutor(
      ToolResolver resolver,
      ToolResultFactory results,
      ToolLimits limits,
      ObjectProvider<ToolInterceptor> interceptors) {
    this.resolver = resolver;
    this.results = results;
    this.limits = limits;
    this.interceptors = interceptors.orderedStream().toList();
  }

  public Mono<McpSchema.CallToolResult> execute(
      ToolRegistration registration,
      McpTransportContext transport,
      McpSchema.CallToolRequest request) {
    return Mono.defer(
        () -> {
          var definition = registration.definition();
          var context = McpRequestContext.from(transport);
          var args = new ToolArguments(request.arguments());
          var call = new ToolInvocation(definition, context, args, resolver.session(context));
          long started = System.nanoTime();
          if (!McpJsonDefaults.getSchemaValidator()
              .validate(definition.inputSchema(), args.values())
              .valid())
            return observed(
                call,
                Mono.error(
                    ToolArguments.invalid(
                        "Неверные аргументы " + definition.name() + "; проверьте tools/list.")),
                started);
          if (!context.user().getScopesList().contains(definition.scope()))
            return observed(
                call,
                Mono.just(
                    results.error(
                        context,
                        new GatewayToolException(
                            "insufficient_scope",
                            "Требуется scope " + definition.scope() + ".",
                            Map.of("required_scope", definition.scope())))),
                started);
          Supplier<Mono<ToolData>> pipeline = () -> registration.handler().apply(call);
          for (int i = interceptors.size() - 1; i >= 0; i--) {
            var next = pipeline;
            var interceptor = interceptors.get(i);
            pipeline = () -> interceptor.intercept(call, next);
          }
          return observed(
              call,
              Mono.defer(pipeline)
                  .timeout(limits.timeout())
                  .switchIfEmpty(
                      Mono.error(
                          new GatewayToolException(
                              "upstream_error", "Taska вернула пустой результат операции.")))
                  .map(data -> results.success(call, data))
                  .onErrorResume(
                      error -> {
                        if (error instanceof McpError) return Mono.error(error);
                        if (error instanceof GatewayToolException toolError) {
                          if (!definition.readOnly()
                              && ("timeout".equals(toolError.code())
                                  || "upstream_error".equals(toolError.code())))
                            toolError =
                                new GatewayToolException(
                                    toolError.code(),
                                    "Ответ записи не получен. Сначала перечитайте объект; не"
                                        + " повторяйте запись автоматически.",
                                    toolError.details());
                          return Mono.just(results.error(context, toolError));
                        }
                        if (!(error instanceof Exception)) return Mono.error(error);
                        return Mono.just(
                            results.error(
                                context,
                                new GatewayToolException(
                                    error instanceof java.util.concurrent.TimeoutException
                                        ? "timeout"
                                        : "upstream_error",
                                    definition.readOnly()
                                        ? "Taska временно недоступна. Повторите чтение позже."
                                        : "Ответ записи не получен. Сначала перечитайте объект; не"
                                            + " повторяйте запись автоматически.")));
                      }),
              started);
        });
  }

  private Mono<McpSchema.CallToolResult> observed(
      ToolInvocation call, Mono<McpSchema.CallToolResult> result, long started) {
    var recorded = new AtomicBoolean();
    java.util.function.Consumer<String> record =
        outcome -> {
          if (recorded.compareAndSet(false, true)) observe(call, outcome, started);
        };
    return result
        .doOnNext(
            value -> {
              String outcome = "success";
              if (Boolean.TRUE.equals(value.isError())
                  && value.structuredContent() instanceof Map<?, ?> envelope
                  && envelope.get("error") instanceof Map<?, ?> error
                  && error.get("code") instanceof String code) outcome = code;
              record.accept(outcome);
            })
        .doOnError(
            error ->
                record.accept(error instanceof McpError ? "invalid_argument" : "upstream_error"))
        .doOnCancel(() -> record.accept("cancelled"));
  }

  private void observe(ToolInvocation call, String outcome, long started) {
    for (var interceptor : interceptors) {
      try {
        interceptor.observe(call, outcome, System.nanoTime() - started);
      } catch (RuntimeException observerFailure) {
        org.slf4j.LoggerFactory.getLogger(ToolExecutor.class).warn("MCP outcome observer failed");
      }
    }
  }
}
