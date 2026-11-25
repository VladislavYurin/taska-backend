package ru.taska.security.tenant;

import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;

/**
 * Test-only authority fixture. Wire/signature verification is covered by real mTLS tests in
 * grpc-common-lib.
 */
public final class RegistryTestContext {
  public static <T> Mono<T> as(
      UUID actor, UUID tenant, String action, CredentialKind kind, boolean session, Mono<T> work) {
    var c =
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor.toString())
            .setAction(action)
            .setCredentialKind(kind);
    if (tenant != null) c.setTenantId(tenant.toString());
    if (session) c.setSessionId(UUID.randomUUID().toString());
    var wire = RequestContext.newBuilder().setContext(c).build();
    var verified =
        new VerifiedTenantContext(
            actor.toString(),
            tenant == null ? null : tenant.toString(),
            UUID.randomUUID().toString(),
            action,
            Set.of("read", "write"),
            wire.toByteArray());
    return work.contextWrite(verified::install);
  }

  public static <T> Mono<T> withClaims(TrustedTenantContext claims, Mono<T> work) {
    var requestId = UUID.randomUUID().toString();
    // RPC fixtures need a real original lease: production delegation must not mint a new one.
    var bounded = claims.toBuilder();
    if (claims.getContextVersion() == 0) bounded.setContextVersion(1);
    var now = Instant.now();
    if (claims.getIssuedAt().isBlank()) bounded.setIssuedAt(now.toString());
    if (claims.getExpiresAt().isBlank()) bounded.setExpiresAt(now.plusSeconds(30).toString());
    claims = bounded.build();
    var verified =
        new VerifiedTenantContext(
            claims.hasActorUserId() ? claims.getActorUserId() : null,
            claims.hasTenantId() ? claims.getTenantId() : null,
            requestId,
            claims.getAction(),
            Set.copyOf(claims.getScopesList()),
            RequestContext.newBuilder()
                .setHeader(Header.newBuilder().setRequestId(requestId))
                .setContext(claims)
                .build()
                .toByteArray());
    return work.contextWrite(verified::install);
  }
}
