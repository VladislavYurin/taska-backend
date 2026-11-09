package ru.taska.security.tenant;

import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;

/**
 * Entry point for purpose-bound jobs. All requested edges/scopes still need managed authorization.
 */
public final class ManagedServiceContexts {
  private final String service;
  private final ManagedRpcTrust trust;
  private final RpcContextSecurity security;

  public ManagedServiceContexts(
      String service, ManagedRpcTrust trust, RpcContextSecurity security) {
    this.service = service;
    this.trust = trust;
    this.security = security;
  }

  public Mono<VerifiedTenantContext> authorize(
      String purpose,
      String audience,
      String rpc,
      String action,
      UUID tenant,
      Set<String> scopes,
      RpcContextSecurity.Policy policy) {
    return Mono.defer(
            () -> {
              var now = Instant.now();
              var c =
                  TrustedTenantContext.newBuilder()
                      .setContextVersion(1)
                      .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                      .setServicePurpose(purpose)
                      .setServiceIdentity(service)
                      .setActorGeneration(trust.snapshot().services().get(service).generation())
                      .addAllScopes(scopes)
                      .setIssuerService(service)
                      .setAudienceService(audience)
                      .setAction(action)
                      .setIssuedAt(now.toString())
                      .setExpiresAt(now.plusSeconds(30).toString())
                      .setAssertionId(UUID.randomUUID().toString());
              if (tenant != null) c.setTenantId(tenant.toString());
              return security.authorize(
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(c)
                      .build(),
                  rpc,
                  policy);
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }
}
