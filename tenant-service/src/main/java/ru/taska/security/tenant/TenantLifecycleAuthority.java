package ru.taska.security.tenant;

import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.TenantCreationLifecycleServiceGrpc;

/** A routing row grants no rights: every expiry attempt passes the online service policy. */
public final class TenantLifecycleAuthority {
  private final RpcContextSecurity security;
  private final Clock clock;

  public TenantLifecycleAuthority(RpcContextSecurity security, Clock clock) {
    this.security = security;
    this.clock = clock;
  }

  public <T> Mono<T> execute(UUID tenant, Mono<T> work) {
    return Mono.defer(
            () ->
                security.authorize(
                    ProvisioningAuthority.context(
                        tenant, "tenant-service", "expireTenantCreation", clock),
                    TenantCreationLifecycleServiceGrpc.getExpireTenantCreationMethod()
                        .getFullMethodName(),
                    new RpcContextSecurity.Policy(true, true)))
        .flatMap(verified -> work.contextWrite(verified::install));
  }
}
