package ru.taska.mcp;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Extension point for bounded quotas, audit and metrics; implementations must release on
 * cancellation.
 */
public interface ToolInterceptor {
  Mono<ToolData> intercept(ToolInvocation call, Supplier<Mono<ToolData>> next);
}
