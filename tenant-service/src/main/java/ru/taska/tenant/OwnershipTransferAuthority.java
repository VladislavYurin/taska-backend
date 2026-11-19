package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.RpcActorFacts;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityEvaluator;
import ru.taska.capability.CapabilityFacts;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.configuration.PlatformConfigurationSnapshotCache.Lease;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Первичные факты читаются до SQL; внутри блокировки используется общий evaluator. */
public final class OwnershipTransferAuthority {
  public static final String RPC = "ru.taska.b2b.v1.TenantService/TransferTenantOwnership";
  private final RpcTrustBackend identities;
  private final ManagedDomainReads reads;
  private final Clock clock;

  public record Frame(
      RpcActorFacts actor,
      Entitlement entitlement,
      CapabilityFacts.PlanKind kind,
      Lease flags,
      Instant until) {}

  public OwnershipTransferAuthority(
      RpcTrustBackend identities, ManagedDomainReads reads, Clock clock) {
    this.identities = identities;
    this.reads = reads;
    this.clock = clock;
  }

  public Mono<Frame> current(VerifiedTenantContext v) {
    var claims = RpcContextBridge.details(v).getContext();
    return identities
        .actorFacts(RpcContextBridge.details(v), RPC, new RpcContextSecurity.Policy(true, true))
        .flatMap(
            actor -> {
              if (!actor.getActorUserId().equals(v.actorId())
                  || !actor.getActorGeneration().equals(claims.getActorGeneration())
                  || !actor.getCredentialId().equals(claims.getCredentialId())
                  || !actor.getSessionId().equals(claims.getSessionId()))
                return Mono.error(fail(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
              return Mono.zip(
                      reads.entitlement(UUID.fromString(v.tenantId()), 0), reads.configuration())
                  .flatMap(
                      pair ->
                          reads
                              .plan(pair.getT1().getPlanVersionId())
                              .map(
                                  kind -> {
                                    Instant now = clock.instant(),
                                        until = Instant.parse(actor.getExpiresAt());
                                    if (until.isAfter(now.plusSeconds(30)))
                                      until = now.plusSeconds(30);
                                    if (!now.isBefore(until))
                                      throw fail(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED");
                                    return new Frame(
                                        actor,
                                        pair.getT1(),
                                        kind,
                                        new Lease(pair.getT2(), now, until),
                                        until);
                                  }));
            });
  }

  public Mono<Void> member(
      DatabaseClient db, Map<String, Object> tenant, VerifiedTenantContext v, boolean admin) {
    if (admin) return Mono.empty();
    return db.sql(
            "SELECT status,version FROM taska.tenant_memberships WHERE tenant_id=:t AND user_id=:u")
        .bind("t", tenant.get("id"))
        .bind("u", UUID.fromString(v.actorId()))
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE")))
        .flatMap(
            m -> {
              if (!"ACTIVE".equals(m.get("status"))
                  || !m.get("version")
                      .toString()
                      .equals(RpcContextBridge.details(v).getContext().getMembershipGeneration()))
                return Mono.error(fail(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
              return Mono.empty();
            });
  }

  public Mono<Void> allow(
      DatabaseClient db, Map<String, Object> row, VerifiedTenantContext v, Frame f) {
    var c = RpcContextBridge.details(v).getContext();
    boolean admin = TenantRenameService.administrative(c, (UUID) row.get("id"));
    if (!clock.instant().isBefore(f.until()))
      return Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
    if (!"ACTIVE".equals(row.get("lifecycle")))
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
    // Административные credentials требуют отдельного актуального grant; до его реализации fail
    // closed.
    if (admin
        && (c.getCredentialKind() == ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
            || c.getCredentialKind()
                == ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH))
      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "GRANT_REQUIRED"));
    return member(db, row, v, admin)
        .then(TenantRestrictions.decorate(db, row))
        .flatMap(
            tenant -> {
              var membership =
                  MembershipSnapshot.newBuilder()
                      .setTenantId(v.tenantId())
                      .setUserId(v.actorId())
                      .setState(Membership.StateValue.STATE_ACTIVE)
                      .setRole(Membership.RoleValue.ROLE_TENANT_ADMIN)
                      .setVersion(c.getMembershipGeneration())
                      .setTenantMembershipVersion(c.getMembershipGeneration())
                      .setIsOwner(UUID.fromString(v.actorId()).equals(tenant.get("owner_id")))
                      .build();
              var target = new CapabilityFacts.Target((UUID) tenant.get("id"), null, null, null);
              var facts =
                  new CapabilityFacts(
                      UUID.fromString(v.actorId()),
                      target,
                      v.requestId(),
                      f.until(),
                      new CapabilityFacts.Identity(
                          true,
                          f.actor().getGlobalAdmin(),
                          f.actor().getActorGeneration(),
                          true,
                          f.actor().getCredentialId(),
                          f.actor().hasSessionId() ? f.actor().getSessionId() : null,
                          f.actor().getWebMfa(),
                          Set.copyOf(f.actor().getScopesList())),
                      membership,
                      CapabilityFacts.ProjectRole.NONE,
                      Tenant.LifecycleValue.LIFECYCLE_ACTIVE,
                      true,
                      membership.getIsOwner(),
                      null,
                      f.entitlement(),
                      f.kind(),
                      TenantRestrictions.codes(tenant.get("independent_restrictions")),
                      f.flags().flags(),
                      f.flags().snapshot().getVersion(),
                      "MCP".equals(RpcContextBridge.details(v).getHeader().getActionChannel())
                          ? CapabilityFacts.Channel.MCP
                          : CapabilityFacts.Channel.DIRECT,
                      CapabilityFacts.PaymentChannel.NONE,
                      false,
                      Map.of(Action.TENANT_TRANSFER_OWNERSHIP, Set.<ErrorCode>of()),
                      Map.of(Action.TENANT_TRANSFER_OWNERSHIP, Set.<ErrorCode>of()),
                      null,
                      null);
              return new CapabilityEvaluator((actor, t, actions) -> Mono.just(facts), clock)
                  .evaluate(Action.TENANT_TRANSFER_OWNERSHIP, target)
                  .flatMap(
                      decision ->
                          decision.capability().getAllowed()
                              ? Mono.empty()
                              : Mono.error(
                                  fail(
                                      DomainStatus.PERMISSION_DENIED,
                                      decision.capability().getDenialCodesCount() == 0
                                          ? "SCOPE_DENIED"
                                          : decision
                                              .capability()
                                              .getDenialCodes(0)
                                              .name()
                                              .replace("ERROR_CODE_", ""))));
            });
  }
}
