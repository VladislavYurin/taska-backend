package ru.taska.mcp;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Extension point for bounded quotas, audit and metrics; implementations must release on
 * cancellation.
 */
public interface ToolInterceptor {
  Mono<ToolData> intercept(ToolInvocation call, Supplier<Mono<ToolData>> next);

  /**
   * Observes the final public outcome, including result caps and cancellation; observers must never
   * log field text.
   */
  default void observe(ToolInvocation call, String outcome, long durationNanos) {}
}
