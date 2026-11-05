package ru.taska.security.tenant;

import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TenantProvisioningServiceGrpc;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;

/**
 * The queue supplies routing IDs, never authority. Online service policy authorizes each attempt.
 */
public final class ProvisioningAuthority {
  private final RpcContextSecurity security;
  private final Clock clock;

  public ProvisioningAuthority(RpcContextSecurity security, Clock clock) {
    this.security = security;
    this.clock = clock;
  }

  public static RequestContext context(UUID tenant, String audience, String action, Clock clock) {
    var now = clock.instant();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setContextVersion(1)
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                .setActorGeneration("service-v1")
                .setTenantId(tenant.toString())
                .setIssuerService("tenant-service")
                .setAudienceService(audience)
                .setAction(action)
                .addScopes("write")
                .addScopes("provisioning:write")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }

  public <T> Mono<T> execute(UUID tenant, Mono<T> work) {
    return Mono.defer(
            () ->
                security.authorize(
                    context(tenant, "tenant-service", "provisionTenant", clock),
                    TenantProvisioningServiceGrpc.getResumeTenantProvisioningMethod()
                        .getFullMethodName(),
                    new RpcContextSecurity.Policy(true, true)))
        .flatMap(verified -> work.contextWrite(verified::install));
  }
}
