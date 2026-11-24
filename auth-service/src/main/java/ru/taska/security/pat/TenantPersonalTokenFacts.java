package ru.taska.security.pat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.*;

/** Для каждого выпуска повторно читает primary identity, membership, flags и billing. */
public final class TenantPersonalTokenFacts implements CapabilityEvaluator.Source {
  private final ManagedDomainReads reads;
  private final RpcTrustBackend identity;
  private final Clock clock;
  private final PlatformConfigurationSnapshotCache flags;

  public TenantPersonalTokenFacts(ManagedDomainReads reads, RpcTrustBackend identity, Clock clock) {
    this.reads = reads;
    this.identity = identity;
    this.clock = clock;
    this.flags = new PlatformConfigurationSnapshotCache(
        reads::configuration, (c, e) -> Mono.just(false), clock, Duration.ofSeconds(30));
  }

  @Override
  public Mono<CapabilityFacts> current(VerifiedTenantContext c, CapabilityFacts.Target target, Set<Action> actions) {
    return load(c, target, actions, "createTenantPersonalToken",
        TenantPersonalTokenServiceGrpc.getCreateTenantPersonalTokenMethod().getFullMethodName());
  }

  public Mono<Void> admitOAuth(java.util.UUID tenant, java.util.UUID actor) {
    return VerifiedTenantContext.current().flatMap(c -> load(c,
        new CapabilityFacts.Target(tenant, null, actor, null), Set.of(Action.PAT_CREATE),
        "approveTenantOAuthGrant", TenantOAuthGrantServiceGrpc.getApproveTenantOAuthGrantMethod().getFullMethodName()))
        .flatMap(f -> {
          if (f.planKind() == CapabilityFacts.PlanKind.UNKNOWN) return Mono.error(unavailable());
          if (f.identity().globalAdmin() && f.identity().webMfa()) return Mono.empty();
          var disabled = new java.util.ArrayList<String>();
          for (var flag : f.planKind() == CapabilityFacts.PlanKind.FREE
              ? List.of(CapabilityFacts.Flag.MCP, CapabilityFacts.Flag.FREE_MCP)
              : List.of(CapabilityFacts.Flag.MCP)) {
            if (f.flags().get(flag) == null) return Mono.error(unavailable());
            if (!f.flags().get(flag)) disabled.add(flag.name());
          }
          return disabled.isEmpty() ? Mono.empty() : Mono.error(new DomainException(
              DomainStatus.PERMISSION_DENIED, "CAPABILITY_DISABLED",
              Map.of("code", "CAPABILITY_DISABLED", "disabledFlags", String.join(",", disabled))));
        });
  }

  private Mono<CapabilityFacts> load(VerifiedTenantContext c, CapabilityFacts.Target target,
      Set<Action> actions, String action, String rpc) {
    return Mono.defer(() -> {
      var claim = RpcContextBridge.details(c).getContext();
      if (!actions.equals(Set.of(Action.PAT_CREATE)) || target.tenant() == null
          || !target.tenant().toString().equals(c.tenantId())
          || target.resource() == null || !target.resource().toString().equals(c.actorId())
          || !action.equals(c.action())
          || claim.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
        return Mono.error(denied());
      return identity.actorFacts(RpcContextBridge.details(c), rpc,
              new RpcContextSecurity.Policy(true, true))
          .flatMap(actor -> {
            if (!actor.getActorUserId().equals(c.actorId())
                || !actor.getActorGeneration().equals(claim.getActorGeneration())
                || !actor.getCredentialId().equals(claim.getCredentialId())
                || !actor.hasSessionId() || !actor.getSessionId().equals(claim.getSessionId())
                || !Set.copyOf(actor.getScopesList()).equals(c.scopes())) return Mono.error(denied());
            if (actor.getGlobalAdmin() && !actor.getWebMfa()) return Mono.error(new DomainException(
                DomainStatus.PERMISSION_DENIED, "MFA_REQUIRED", Map.of("code", "MFA_REQUIRED")));
            return reads.actorMembership(c).flatMap(members ->
                Mono.zip(flags.resolve(PlatformConfigurationSnapshotCache.Use.ADMISSION),
                        reads.entitlement(target.tenant(), 0).map(EntitlementProjection::validate))
                    .flatMap(values -> {
                      var entitlement = values.getT2();
                      if (!entitlement.getTenantId().equals(c.tenantId())) return Mono.error(unavailable());
                      return reads.plan(entitlement.getPlanVersionId()).map(plan -> {
                        Instant until = Collections.min(List.of(clock.instant().plusSeconds(30),
                            Instant.parse(actor.getExpiresAt()), values.getT1().validUntil(),
                            Instant.parse(claim.getExpiresAt())));
                        var frame = new TenantDecisionFacts.Frame(c, actor,
                            members.getItems(0).getMembership(), members.getTenantLifecycle(),
                            entitlement, plan, values.getT1(), until);
                        return frame.facts(target, CapabilityFacts.ProjectRole.NONE, actions, Map.of());
                      });
                    }));
          });
    }).timeout(Duration.ofSeconds(5)).switchIfEmpty(Mono.error(unavailable()))
        .onErrorMap(e -> !(e instanceof DomainException), e -> {
          var status = io.grpc.Status.fromThrowable(e).getCode();
          if (status == io.grpc.Status.Code.UNAUTHENTICATED)
            return new DomainException(DomainStatus.UNAUTHENTICATED, "Current credential required", Map.of("code", "AUTHENTICATION_REQUIRED"));
          if (status == io.grpc.Status.Code.PERMISSION_DENIED)
            return new DomainException(DomainStatus.PERMISSION_DENIED, "Current authority required", Map.of("code", "SCOPE_DENIED"));
          return unavailable();
        });
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "SESSION_REQUIRED", Map.of("code", "SESSION_REQUIRED"));
  }
  private static DomainException unavailable() {
    return new DomainException(DomainStatus.UNAVAILABLE, "Current token authority unavailable", Map.of("code", "DEPENDENCY_UNAVAILABLE"));
  }
}
