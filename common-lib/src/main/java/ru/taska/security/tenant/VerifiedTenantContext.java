package ru.taska.security.tenant;

import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Request-local authority. Only the verifier in this package can mint/install it. */
public final class VerifiedTenantContext {
  private final String actorId, tenantId, requestId, action;
  private final Set<String> scopes;
  private final byte[] wire;

  VerifiedTenantContext(
      String actorId,
      String tenantId,
      String requestId,
      String action,
      Set<String> scopes,
      byte[] wire) {
    this.actorId = actorId;
    this.tenantId = tenantId;
    this.requestId = requestId;
    this.action = action;
    this.scopes = Set.copyOf(scopes);
    this.wire = wire.clone();
  }

  public String actorId() {
    return actorId;
  }

  public String tenantId() {
    return tenantId;
  }

  public String requestId() {
    return requestId;
  }

  public String action() {
    return action;
  }

  public Set<String> scopes() {
    return scopes;
  }

  byte[] wire() {
    return wire.clone();
  }

  Context install(Context target) {
    return target.put(VerifiedTenantContext.class, this);
  }

  public static VerifiedTenantContext require(ContextView context) {
    if (!context.hasKey(VerifiedTenantContext.class))
      throw new IllegalStateException("Verified context required");
    return context.get(VerifiedTenantContext.class);
  }

  public static Mono<VerifiedTenantContext> current() {
    return Mono.deferContextual(c -> Mono.just(require(c)));
  }

  @Override
  public String toString() {
    return "VerifiedTenantContext[redacted]";
  }
}
