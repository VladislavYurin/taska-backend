package ru.taska.tenant;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.CapabilityFacts.*;
import ru.taska.configuration.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/** Advisory tenant-level policy. Current actor and membership precede every source/cache read. */
public final class TenantCapabilities {
  public static final String RPC =
      "ru.taska.b2b.v1.TenantCapabilitiesService/GetTenantCapabilities";
  private final TenantTransactionExecutor transactions;
  private final RpcTrustBackend identities;
  private final PlatformConfigurationSnapshotCache flags;
  private final EntitlementProjection commercial;
  private final Function<String, Mono<PlanKind>> plans;
  private final Clock clock;

  private record Access(
      MembershipSnapshot membership,
      Tenant.LifecycleValue lifecycle,
      Instant changedAt,
      long admins) {}

  private record Frame(
      Access access, RpcActorFacts actor, VersionedCapabilitySource.Inputs inputs, Instant until) {}

  public TenantCapabilities(
      TenantTransactionExecutor transactions,
      RpcTrustBackend identities,
      PlatformConfigurationSnapshotCache flags,
      EntitlementProjection.Source billing,
      Function<String, Mono<PlanKind>> plans,
      Clock clock) {
    this.transactions = transactions;
    this.identities = identities;
    this.flags = flags;
    this.plans = plans;
    this.clock = clock;
    // No broker event adapter is exposed. Every display request reconciles both primary
    // sources.
    this.commercial =
        new EntitlementProjection(
            transactions,
            billing,
            (actor, use) -> access(actor).map(a -> Set.<RestrictionCode>of()),
            (actor, event) -> Mono.just(false),
            clock,
            Duration.ofSeconds(30),
            1024);
  }

  private Mono<Access> access(VerifiedTenantContext actor) {
    var c = RpcContextBridge.details(actor).getContext();
    if (actor.actorId() == null || actor.tenantId() == null || !c.hasMembershipGeneration())
      return Mono.error(denied());
    return transactions
        .execute(
            db ->
                db.sql(
                        "SELECT t.lifecycle,t.owner_id,t.membership_version"
                            + " AS generation,t.updated_at,m.*, (SELECT"
                            + " count(*) FROM taska.tenant_memberships a"
                            + " WHERE a.tenant_id=t.id AND"
                            + " a.status='ACTIVE' AND"
                            + " a.role='TENANT_ADMIN') AS admins FROM"
                            + " taska.tenants t JOIN"
                            + " taska.tenant_memberships m ON"
                            + " m.tenant_id=t.id AND m.user_id=:actor WHERE"
                            + " t.id=:tenant AND t.lifecycle<>'DELETED' AND"
                            + " m.status='ACTIVE' AND"
                            + " m.version::text=:generation")
                    .bind("actor", UUID.fromString(actor.actorId()))
                    .bind("tenant", UUID.fromString(actor.tenantId()))
                    .bind("generation", c.getMembershipGeneration())
                    .fetch()
                    .one()
                    .map(
                        row -> {
                          var m =
                              MembershipSnapshot.newBuilder()
                                  .setTenantId(actor.tenantId())
                                  .setUserId(actor.actorId())
                                  .setState(Membership.StateValue.STATE_ACTIVE)
                                  .setRole(Membership.RoleValue.valueOf("ROLE_" + row.get("role")))
                                  .setVersion(row.get("version").toString())
                                  .setTenantMembershipVersion(row.get("version").toString())
                                  .setIsOwner(
                                      UUID.fromString(actor.actorId()).equals(row.get("owner_id")))
                                  .setJoinedAt(
                                      ((OffsetDateTime) row.get("joined_at"))
                                          .toInstant()
                                          .toString())
                                  .build();
                          return new Access(
                              m,
                              Tenant.LifecycleValue.valueOf("LIFECYCLE_" + row.get("lifecycle")),
                              ((OffsetDateTime) row.get("updated_at")).toInstant(),
                              ((Number) row.get("admins")).longValue());
                        }))
        .switchIfEmpty(Mono.error(denied()));
  }

  public Mono<Capabilities> get(String tenant) {
    try {
      if (!UUID.fromString(tenant).toString().equals(tenant)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid tenantId"));
    }
    return VerifiedTenantContext.current()
        .flatMap(
            verified -> {
              if (!tenant.equals(verified.tenantId())
                  || !"getTenantCapabilities".equals(verified.action())
                  || !verified.scopes().contains("read")) return Mono.error(denied());
              var target = new Target(UUID.fromString(tenant), null, null, null);
              var frame = new AtomicReference<Frame>();
              var source =
                  new VersionedCapabilitySource(
                      (context, t, actions) ->
                          identities
                              .actorFacts(
                                  RpcContextBridge.details(context),
                                  RPC,
                                  new RpcContextSecurity.Policy(true, false))
                              .flatMap(
                                  actor -> {
                                    if (!actor.getActorUserId().equals(context.actorId())
                                        || !actor
                                            .getActorGeneration()
                                            .equals(
                                                RpcContextBridge.details(context)
                                                    .getContext()
                                                    .getActorGeneration()))
                                      return Mono.error(denied());
                                    var credentialKind =
                                        RpcContextBridge.details(context)
                                            .getContext()
                                            .getCredentialKind();
                                    boolean web =
                                        credentialKind == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                                            || credentialKind
                                                == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL;
                                    if (actor.getGlobalAdmin() && web && !actor.getWebMfa())
                                      return Mono.error(
                                          new DomainException(
                                              DomainStatus.PERMISSION_DENIED,
                                              "MFA_REQUIRED",
                                              Map.of("reason", "MFA_REQUIRED")));
                                    return access(context)
                                        .map(
                                            access ->
                                                (VersionedCapabilitySource.Assembler)
                                                    inputs ->
                                                        plans
                                                            .apply(
                                                                inputs
                                                                    .entitlement()
                                                                    .view()
                                                                    .snapshot()
                                                                    .getPlanVersionId())
                                                            .map(
                                                                kind -> {
                                                                  Instant until =
                                                                      Collections.min(
                                                                          List.of(
                                                                              clock
                                                                                  .instant()
                                                                                  .plusSeconds(30),
                                                                              Instant.parse(
                                                                                  actor
                                                                                      .getExpiresAt()),
                                                                              inputs.validUntil()));
                                                                  if (!clock
                                                                      .instant()
                                                                      .isBefore(until))
                                                                    throw unavailable();
                                                                  frame.set(
                                                                      new Frame(
                                                                          access, actor, inputs,
                                                                          until));
                                                                  var integrity =
                                                                      new EnumMap<
                                                                          Action, Set<ErrorCode>>(
                                                                          Action.class);
                                                                  var business =
                                                                      new EnumMap<
                                                                          Action, Set<ErrorCode>>(
                                                                          Action.class);
                                                                  for (Action action : actions) {
                                                                    integrity.put(action, Set.of());
                                                                    business.put(action, Set.of());
                                                                  }
                                                                  if (actions.contains(
                                                                      Action.MEMBERSHIP_LEAVE)) {
                                                                    if (access
                                                                        .membership()
                                                                        .getIsOwner())
                                                                      integrity.put(
                                                                          Action.MEMBERSHIP_LEAVE,
                                                                          Set.of(
                                                                              ErrorCode
                                                                                  .ERROR_CODE_OWNER_MEMBERSHIP_PROTECTED));
                                                                    else if (access
                                                                                .membership()
                                                                                .getRole()
                                                                            == Membership.RoleValue
                                                                                .ROLE_TENANT_ADMIN
                                                                        && access.admins() <= 1)
                                                                      integrity.put(
                                                                          Action.MEMBERSHIP_LEAVE,
                                                                          Set.of(
                                                                              ErrorCode
                                                                                  .ERROR_CODE_LAST_ADMIN));
                                                                  }
                                                                  var claims =
                                                                      RpcContextBridge.details(
                                                                          context);
                                                                  var channel =
                                                                      "MCP"
                                                                              .equals(
                                                                                  claims
                                                                                      .getHeader()
                                                                                      .getActionChannel())
                                                                          ? Channel.MCP
                                                                          : Channel.DIRECT;
                                                                  return new CapabilityFacts(
                                                                      UUID.fromString(
                                                                          context.actorId()),
                                                                      target,
                                                                      context.requestId(),
                                                                      until,
                                                                      new Identity(
                                                                          true,
                                                                          actor.getGlobalAdmin(),
                                                                          actor
                                                                              .getActorGeneration(),
                                                                          true,
                                                                          actor.getCredentialId(),
                                                                          actor.hasSessionId()
                                                                              ? actor.getSessionId()
                                                                              : null,
                                                                          actor.getWebMfa(),
                                                                          Set.copyOf(
                                                                              actor
                                                                                  .getScopesList())),
                                                                      access.membership(),
                                                                      ProjectRole.NONE,
                                                                      access.lifecycle(),
                                                                      true,
                                                                      access
                                                                          .membership()
                                                                          .getIsOwner(),
                                                                      null,
                                                                      inputs
                                                                          .entitlement()
                                                                          .view()
                                                                          .snapshot(),
                                                                      kind,
                                                                      Set.of(),
                                                                      inputs
                                                                          .configuration()
                                                                          .flags(),
                                                                      inputs.key().flagsVersion(),
                                                                      channel,
                                                                      PaymentChannel.NONE,
                                                                      false,
                                                                      integrity,
                                                                      business,
                                                                      null,
                                                                      null);
                                                                }));
                                  }),
                      flags,
                      commercial,
                      clock);
              // current() deliberately reconciles on every observation; no delayed
              // broker
              // invalidation.
              return new CapabilityEvaluator(source, clock)
                  .decisions(CapabilityEvaluator.tenantActions(), target)
                  .map(
                      decisions -> {
                        Frame f = frame.get();
                        Instant evaluated = clock.instant();
                        if (f == null || !evaluated.isBefore(f.until())) throw unavailable();
                        Instant effective =
                            f.inputs().configuration().snapshot().getItemsList().stream()
                                .map(v -> Instant.parse(v.getEffectiveAt()))
                                .max(Comparator.naturalOrder())
                                .orElseThrow();
                        Instant commercialAt =
                            Instant.parse(
                                f.inputs().entitlement().view().snapshot().getValidFrom());
                        if (commercialAt.isAfter(effective)
                            && !commercialAt.isAfter(clock.instant())) effective = commercialAt;
                        if (f.access().changedAt().isAfter(effective))
                          effective = f.access().changedAt();
                        // DB clocks can lead the evaluator; metadata cannot describe a future
                        // decision.
                        if (effective.isAfter(evaluated)) effective = evaluated;
                        var result =
                            Capabilities.newBuilder()
                                .setTenantId(tenant)
                                .setActorUserId(verified.actorId())
                                .setPolicyVersion(CapabilityEvaluator.POLICY_VERSION)
                                .setFlagVersion(f.inputs().key().flagsVersion())
                                .setEntitlementVersion(f.inputs().key().entitlementVersion())
                                .setMembershipVersion(f.access().membership().getVersion())
                                .setEvaluatedAt(evaluated.toString())
                                .setEffectiveAt(effective.toString())
                                .setExpiresAt(f.until().toString());
                        decisions.entrySet().stream()
                            .sorted(Comparator.comparing(e -> e.getKey().wire()))
                            .forEach(e -> result.addItems(e.getValue().capability()));
                        for (String key :
                            List.of(
                                "UNPAID_CREATION_WINDOW_SECONDS",
                                "WIRE_RECONCILIATION_TARGET_SECONDS"))
                          result.putDurationsSeconds(
                              key, f.inputs().configuration().duration(key).toSeconds());
                        return result.build();
                      });
            })
        .onErrorMap(
            e -> {
              if (e instanceof DomainException) return e;
              return switch (io.grpc.Status.fromThrowable(e).getCode()) {
                case UNAUTHENTICATED ->
                    new DomainException(
                        DomainStatus.UNAUTHENTICATED, "Current credential required");
                case PERMISSION_DENIED -> denied();
                default -> unavailable();
              };
            });
  }

  private static DomainException denied() {
    return new DomainException(
        DomainStatus.PERMISSION_DENIED, "Current tenant membership required");
  }

  private static DomainException unavailable() {
    return new DomainException(DomainStatus.UNAVAILABLE, "Current capability sources unavailable");
  }
}
