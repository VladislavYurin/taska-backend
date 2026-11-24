package ru.taska.capability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipSnapshot;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.api.b2b.v1.RpcActorFacts;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.capability.CapabilityFacts.Accepted;
import ru.taska.capability.CapabilityFacts.Channel;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.capability.CapabilityFacts.Identity;
import ru.taska.capability.CapabilityFacts.PaymentChannel;
import ru.taska.capability.CapabilityFacts.PlanKind;
import ru.taska.capability.CapabilityFacts.ProjectRole;
import ru.taska.capability.CapabilityFacts.Target;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcRoutePolicies;
import ru.taska.security.tenant.RpcTrustBackend;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Primary facts for one admitted API invocation. No actor/role decision is cached across calls. */
public final class TenantDecisionFacts {
  public record Binding(String rpc, RpcContextSecurity.Policy policy) {}

  public record Frame(
      VerifiedTenantContext context,
      RpcActorFacts actor,
      MembershipSnapshot membership,
      Tenant.LifecycleValue lifecycle,
      Entitlement entitlement,
      PlanKind plan,
      PlatformConfigurationSnapshotCache.Lease configuration,
      Instant until) {
    public boolean companyAdmin() {
      return membership.getRole() == Membership.RoleValue.ROLE_TENANT_ADMIN;
    }

    public boolean webAdmin() {
      var kind = RpcContextBridge.details(context).getContext().getCredentialKind();
      return actor.getGlobalAdmin()
          && actor.getWebMfa()
          && (kind == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
              || kind == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
    }

    public boolean implicitAdmin() {
      return companyAdmin() || webAdmin();
    }

    public CapabilityFacts facts(
        Target target,
        ProjectRole role,
        Set<Action> actions,
        Map<Action, Set<ErrorCode>> integrity) {
      return facts(target, role, actions, integrity, null);
    }

    public CapabilityFacts facts(
        Target target,
        ProjectRole role,
        Set<Action> actions,
        Map<Action, Set<ErrorCode>> integrity,
        Accepted accepted) {
      var checks = new EnumMap<Action, Set<ErrorCode>>(Action.class);
      var business = new EnumMap<Action, Set<ErrorCode>>(Action.class);
      for (var action : actions) {
        checks.put(action, integrity.getOrDefault(action, Set.of()));
        business.put(action, Set.of());
      }
      var claims = RpcContextBridge.details(context);
      // The token's generation belongs to this individual membership, not the collection cursor.
      var bound =
          membership.toBuilder().setTenantMembershipVersion(membership.getVersion()).build();
      return new CapabilityFacts(
          UUID.fromString(context.actorId()),
          target,
          context.requestId(),
          until,
          new Identity(
              true,
              actor.getGlobalAdmin(),
              actor.getActorGeneration(),
              true,
              actor.getCredentialId(),
              actor.hasSessionId() ? actor.getSessionId() : null,
              actor.getWebMfa(),
              Set.copyOf(actor.getScopesList())),
          bound,
          role,
          lifecycle,
          true,
          membership.getIsOwner(),
          null,
          entitlement,
          plan,
          Set.copyOf(membership.getIndependentRestrictionsList()),
          configuration.flags(),
          configuration.snapshot().getVersion(),
          "MCP".equals(claims.getHeader().getActionChannel()) ? Channel.MCP : Channel.DIRECT,
          PaymentChannel.NONE,
          false,
          checks,
          business,
          accepted,
          null);
    }
  }

  private final ManagedDomainReads reads;
  private final RpcTrustBackend identity;
  private final Map<String, Binding> nativeBindings;
  private final PlatformConfigurationSnapshotCache flags;
  private final EntitlementProjection commercial;
  private final EntitlementProjection administrativeCommercial;
  private final Clock clock;

  public TenantDecisionFacts(
      TenantTransactionExecutor transactions,
      ManagedDomainReads reads,
      RpcTrustBackend identity,
      Map<String, Binding> nativeBindings,
      Clock clock) {
    this.reads = reads;
    this.identity = identity;
    this.nativeBindings = Map.copyOf(nativeBindings);
    this.clock = clock;
    flags =
        new PlatformConfigurationSnapshotCache(
            reads::configuration, (c, e) -> Mono.just(false), clock, Duration.ofSeconds(30));
    commercial =
        new EntitlementProjection(
            transactions,
            reads::entitlement,
            (c, use) ->
                reads.actorMembership(c).map(m -> Set.copyOf(m.getIndependentRestrictionsList())),
            (c, e) -> Mono.just(false),
            clock,
            Duration.ofSeconds(30),
            1024);
    administrativeCommercial =
        new EntitlementProjection(
            transactions,
            reads::entitlement,
            (c, use) ->
                actor(c)
                    .flatMap(
                        a ->
                            webAdministrative(a, c)
                                ? reads
                                    .memberships(
                                        UUID.fromString(c.tenantId()),
                                        List.of(UUID.fromString(c.actorId())))
                                    .map(m -> Set.<RestrictionCode>of())
                                : Mono.error(denied("CURRENT_ADMIN_REQUIRED"))),
            (c, e) -> Mono.just(false),
            clock,
            Duration.ofSeconds(30),
            1024);
  }

  public Binding binding(VerifiedTenantContext c) {
    var claim = RpcContextBridge.details(c).getContext();
    var nativeBinding = nativeBindings.get(c.action());
    if (nativeBinding != null) return nativeBinding;
    var matches =
        RpcRoutePolicies.all().entrySet().stream()
            .filter(
                e ->
                    e.getValue().audience().equals(claim.getAudienceService())
                        && e.getValue().action().equals(c.action()))
            .toList();
    if (matches.size() != 1) throw denied("Unregistered principal binding");
    return new Binding(matches.getFirst().getKey(), matches.getFirst().getValue().policy());
  }

  public Mono<RpcActorFacts> actor(VerifiedTenantContext c) {
    var b = binding(c);
    return identity
        .actorFacts(RpcContextBridge.details(c), b.rpc(), b.policy())
        .map(
            actor -> {
              var claim = RpcContextBridge.details(c).getContext();
              if (!Objects.equals(c.actorId(), actor.getActorUserId())
                  || !claim.getActorGeneration().equals(actor.getActorGeneration())
                  || !claim.getCredentialId().equals(actor.getCredentialId())
                  || !Set.copyOf(actor.getScopesList()).equals(c.scopes())
                  || !clock.instant().isBefore(Instant.parse(actor.getExpiresAt())))
                throw denied("Current credential required");
              boolean web =
                  claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                      || claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL;
              if (web && actor.getGlobalAdmin() && !actor.getWebMfa()) throw denied("MFA_REQUIRED");
              return actor;
            });
  }

  public Mono<Void> access(VerifiedTenantContext c) {
    return actor(c).then(reads.actorMembership(c)).then();
  }

  public Mono<Frame> current() {
    return current(null);
  }

  public Mono<Frame> current(String scope) {
    return current(scope, false);
  }

  /** Явный административный ресурсный путь: текущий GLOBAL_ADMIN/MFA проверяется primary. */
  public Mono<Frame> currentAdministrative(String scope) {
    return current(scope, true);
  }

  private static boolean webAdministrative(RpcActorFacts actor, VerifiedTenantContext c) {
    return actor.getGlobalAdmin()
        && actor.getWebMfa()
        && RpcContextBridge.details(c).getContext().getCredentialKind()
            == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL;
  }

  private Mono<Frame> current(String scope, boolean administrative) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (scope != null && !c.scopes().contains(scope))
                return Mono.error(denied("SCOPE_DENIED"));
              return actor(c)
                  .flatMap(
                      actor ->
                          (administrative && webAdministrative(actor, c)
                                  ? reads.memberships(
                                      UUID.fromString(c.tenantId()),
                                      List.of(UUID.fromString(c.actorId())))
                                  : reads.actorMembership(c))
                              .flatMap(
                                  members ->
                                      Mono.zip(
                                              flags.resolve(
                                                  PlatformConfigurationSnapshotCache.Use.ADMISSION),
                                              (administrative && webAdministrative(actor, c)
                                                      ? administrativeCommercial
                                                      : commercial)
                                                  .resolveLease(
                                                      EntitlementProjection.Use.NEW_RESOURCE))
                                          .flatMap(
                                              values ->
                                                  reads
                                                      .plan(
                                                          values
                                                              .getT2()
                                                              .view()
                                                              .snapshot()
                                                              .getPlanVersionId())
                                                      .map(
                                                          plan -> {
                                                            Instant until =
                                                                Collections.min(
                                                                    List.of(
                                                                        Instant.parse(
                                                                            actor.getExpiresAt()),
                                                                        values.getT1().validUntil(),
                                                                        values.getT2().validUntil(),
                                                                        clock
                                                                            .instant()
                                                                            .plusSeconds(30)));
                                                            if (!clock.instant().isBefore(until))
                                                              throw new DomainException(
                                                                  DomainStatus.UNAVAILABLE,
                                                                  "Current facts expired");
                                                            return new Frame(
                                                                c,
                                                                actor,
                                                                members.getItems(0).getMembership(),
                                                                members.getTenantLifecycle(),
                                                                values.getT2().view().snapshot(),
                                                                plan,
                                                                values.getT1(),
                                                                until);
                                                          }))));
            });
  }

  public Mono<CapabilityEvaluator.Decision> decision(
      Frame frame, Action action, UUID project, Set<ErrorCode> integrity) {
    var target = new Target(UUID.fromString(frame.context().tenantId()), project, null, null);
    return decision(frame, action, target, ProjectRole.NONE, integrity);
  }

  public Mono<CapabilityEvaluator.Decision> acceptedCreation(Frame frame, UUID operation) {
    var target = new Target(UUID.fromString(frame.context().tenantId()), null, null, operation);
    return decide(
        frame,
        Action.PROJECT_CREATE,
        target,
        ProjectRole.NONE,
        Set.of(),
        new Accepted(
            operation, target, Set.of(Action.PROJECT_CREATE), Set.of(Flag.PROJECT_CREATION)));
  }

  public Mono<CapabilityEvaluator.Decision> decision(
      Frame frame, Action action, Target target, ProjectRole role, Set<ErrorCode> integrity) {
    return decide(frame, action, target, role, integrity, null);
  }

  /** Only permits receipt lookup past business restrictions; it never admits a new resource. */
  public Mono<Void> creationPrincipal(Frame frame) {
    return evaluate(
            frame,
            Action.PROJECT_CREATE,
            new Target(UUID.fromString(frame.context().tenantId()), null, null, null),
            ProjectRole.NONE,
            Set.of(),
            null)
        .map(
            decision -> {
              var business =
                  Set.of(
                      ErrorCode.ERROR_CODE_READ_ONLY,
                      ErrorCode.ERROR_CODE_CAPABILITY_DISABLED,
                      ErrorCode.ERROR_CODE_QUOTA_EXCEEDED);
              if (!decision.capability().getAllowed()
                  && !business.containsAll(decision.capability().getDenialCodesList()))
                requireAllowed(decision);
              return true;
            })
        .then();
  }

  public Mono<CapabilityEvaluator.Decision> acceptedAttachment(
      Frame frame, Target target, ProjectRole role, Set<ErrorCode> integrity) {
    return decide(
        frame,
        Action.ATTACHMENT_CONFIRM,
        target,
        role,
        integrity,
        new Accepted(
            target.operation(),
            target,
            Set.of(Action.ATTACHMENT_CONFIRM),
            Set.of(Flag.ATTACHMENT_UPLOAD)));
  }

  /** Только повтор/поиск сохранённого намерения: бизнес-отказ не становится новым admission. */
  public Mono<CapabilityEvaluator.Decision> attachmentPrincipal(
      Frame frame, Target target, ProjectRole role, Set<ErrorCode> integrity) {
    return evaluate(frame, Action.ATTACHMENT_CONFIRM, target, role, integrity, null)
        .map(
            d -> {
              var business =
                  Set.of(
                      ErrorCode.ERROR_CODE_READ_ONLY,
                      ErrorCode.ERROR_CODE_CAPABILITY_DISABLED,
                      ErrorCode.ERROR_CODE_QUOTA_EXCEEDED);
              if (!d.capability().getAllowed()
                  && !business.containsAll(d.capability().getDenialCodesList())) requireAllowed(d);
              return d;
            });
  }

  private Mono<CapabilityEvaluator.Decision> decide(
      Frame frame,
      Action action,
      Target target,
      ProjectRole role,
      Set<ErrorCode> integrity,
      Accepted accepted) {
    return evaluate(frame, action, target, role, integrity, accepted)
        .map(TenantDecisionFacts::requireAllowed);
  }

  private Mono<CapabilityEvaluator.Decision> evaluate(
      Frame frame,
      Action action,
      Target target,
      ProjectRole role,
      Set<ErrorCode> integrity,
      Accepted accepted) {
    return new CapabilityEvaluator(
            (c, t, actions) -> {
              if (c != frame.context() || !t.tenant().toString().equals(c.tenantId()))
                return Mono.error(denied("Tenant facts mismatch"));
              return Mono.just(frame.facts(t, role, actions, Map.of(action, integrity), accepted));
            },
            clock)
        .evaluate(action, target);
  }

  private static CapabilityEvaluator.Decision requireAllowed(
      CapabilityEvaluator.Decision decision) {
    if (!decision.capability().getAllowed()) {
      var code =
          decision.capability().getDenialCodesList().stream()
              .findFirst()
              .orElse(ErrorCode.ERROR_CODE_SCOPE_DENIED)
              .name()
              .replace("ERROR_CODE_", "");
      var details = new HashMap<String, String>();
      details.put("code", code);
      if (!decision.capability().getRestrictionsList().isEmpty())
        details.put(
            "reason",
            decision.capability().getRestrictions(0).name().replace("RESTRICTION_CODE_", ""));
      throw new DomainException(
          code.equals("RESOURCE_NOT_FOUND")
              ? DomainStatus.NOT_FOUND
              : DomainStatus.PERMISSION_DENIED,
          code,
          details);
    }
    return decision;
  }

  private static DomainException denied(String code) {
    return new DomainException(DomainStatus.PERMISSION_DENIED, code);
  }
}
