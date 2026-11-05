package ru.taska.capability;

import static ru.taska.api.b2b.v1.ErrorCode.*;
import static ru.taska.api.b2b.v1.RestrictionCode.*;
import static ru.taska.capability.CapabilityFacts.*;

import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** One domain policy. Current authentication/scope/integrity precede all business exceptions. */
public final class CapabilityEvaluator {
  public static final String POLICY_VERSION = "TAS-360/SA-2026-11-03/v1";

  public interface Source {
    /**
     * Fetch current facts without cached actor allow. Verify primary identity/grant and scope
     * before querying/counting the target. Never construct these facts from request role flags.
     */
    Mono<CapabilityFacts> current(
        VerifiedTenantContext context, Target target, Set<Action> actions);
  }

  public record Decision(
      Capability capability,
      boolean auditRequired,
      boolean businessBypass,
      Set<RestrictionCode> bypassedRestrictions) {
    public Decision {
      bypassedRestrictions = Set.copyOf(bypassedRestrictions);
    }
  }

  private record Rule(
      String action,
      String effect,
      String scope,
      String profile,
      List<String> roles,
      String userMode,
      boolean grantOwner) {}

  private static final List<RestrictionCode> REASON_ORDER =
      List.of(
          RESTRICTION_CODE_PLATFORM_BLOCK,
          RESTRICTION_CODE_OWNER_PAUSE,
          RESTRICTION_CODE_PAYMENT_DISPUTE,
          RESTRICTION_CODE_PENDING_FIRST_PAYMENT,
          RESTRICTION_CODE_PAYMENT_EXPIRED,
          RESTRICTION_CODE_OVER_QUOTA);
  private static final Map<Action, Rule> RULES = rules();
  private static final Set<String> GLOBAL =
      Set.of(
          "GLOBAL_SELF",
          "GLOBAL_ACCOUNT",
          "NEW_TENANT",
          "PLATFORM",
          "EXISTING_GRANT",
          "ACCOUNT_VISIBLE_TENANTS",
          "ADMIN_SELECTED_SCOPE");
  private static final Set<String> CONTENT = Set.of("CONTENT_READ", "DIRECTORY", "EXPORT", "INBOX");
  private static final Set<String> NO_COMMERCIAL =
      Set.of(
          "ACCOUNT",
          "ACCOUNT_DELETE",
          "CONTROL",
          "FINANCE_READ",
          "PAYMENT",
          "FINANCE_RECOVERY",
          "LEAVE",
          "INVITE_ACCEPT",
          "PERSONAL_INBOX",
          "PROTOCOL",
          "ADMIN",
          "ADMIN_WEB");
  private static final Set<String> WORKERS =
      Set.of(
          "OUTBOX_PUBLISH",
          "NOTIFICATION_DELIVER",
          "OPERATION_RECOVER",
          "MEMBERSHIP_CLEANUP",
          "STORAGE_CLEANUP",
          "EXPORT_BUILD",
          "BILLING_RECONCILE",
          "ACCOUNT_DELETE");
  private final Source source;
  private final Clock clock;

  public CapabilityEvaluator(Source source, Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<Decision> evaluate(Action action, Target target) {
    return decisions(Set.of(Objects.requireNonNull(action)), target).map(d -> d.get(action));
  }

  /**
   * No result cache: every subscription obtains new current facts for exactly this actor/target.
   */
  public Mono<Map<Action, Decision>> decisions(Set<Action> actions, Target target) {
    Set<Action> requested = Set.copyOf(actions);
    Objects.requireNonNull(target);
    if (requested.isEmpty())
      return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Actions required"));
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> new DomainException(DomainStatus.UNAUTHENTICATED, "Verified context required"))
        .flatMap(
            verified -> {
              // Reject a different tenant before any authority adapter can query its data.
              if (!tenantBinding(verified.tenantId(), target)) {
                var denied = new EnumMap<Action, Decision>(Action.class);
                var kind = RpcContextBridge.details(verified).getContext().getCredentialKind();
                boolean audit =
                    kind == CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
                        || kind == CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH;
                for (Action action : requested)
                  denied.put(action, deny(action, ERROR_CODE_TENANT_MISMATCH, audit));
                return Mono.just(Collections.unmodifiableMap(denied));
              }
              return source
                  .current(verified, target, requested)
                  .timeout(Duration.ofSeconds(5))
                  .switchIfEmpty(Mono.error(unavailable()))
                  .map(
                      facts -> {
                        Instant now = clock.instant();
                        if (!Objects.equals(
                                verified.actorId(),
                                facts.actor() == null ? null : facts.actor().toString())
                            || !verified.requestId().equals(facts.requestId())
                            || !target.equals(facts.target())
                            || !now.isBefore(facts.validUntil())
                            || facts.validUntil().isAfter(now.plusSeconds(30))) throw unavailable();
                        var result = new EnumMap<Action, Decision>(Action.class);
                        for (Action action : requested) {
                          Decision decision = decide(verified, action, target, facts, now);
                          if (decision.businessBypass())
                            decision =
                                new Decision(
                                    decision.capability(),
                                    true,
                                    true,
                                    activeForAudit(facts, target, now));
                          result.put(action, decision);
                        }
                        return Collections.unmodifiableMap(result);
                      });
            })
        .onErrorMap(error -> error instanceof DomainException ? error : unavailable());
  }

  private static Decision decide(
      VerifiedTenantContext verified,
      Action action,
      Target target,
      CapabilityFacts f,
      Instant now) {
    Rule rule = RULES.get(action);
    var c = RpcContextBridge.details(verified).getContext();
    var identity = f.identity();
    var kind = c.getCredentialKind();
    boolean adminKind =
        kind == CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
            || kind == CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH;
    boolean web =
        kind == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
            || kind == CredentialKind.CREDENTIAL_KIND_WEB_TENANT;
    boolean user =
        kind == CredentialKind.CREDENTIAL_KIND_USER_PAT
            || kind == CredentialKind.CREDENTIAL_KIND_USER_OAUTH;
    boolean audit = identity.globalAdmin() && (adminKind || web);
    if (!identity.active() || !identity.actorGeneration().equals(c.getActorGeneration()))
      return deny(action, ERROR_CODE_AUTHENTICATION_REQUIRED, audit);
    if (!identity.credentialActive()) return deny(action, ERROR_CODE_TOKEN_REVOKED, audit);
    try {
      if (!now.isBefore(Instant.parse(c.getExpiresAt())))
        return deny(action, ERROR_CODE_AUTHENTICATION_REQUIRED, audit);
    } catch (RuntimeException invalid) {
      return deny(action, ERROR_CODE_AUTHENTICATION_REQUIRED, audit);
    }
    if (!f.integrity().containsKey(action) || !f.businessDenials().containsKey(action))
      return deny(action, ERROR_CODE_DEPENDENCY_UNAVAILABLE, audit);
    if (kind == CredentialKind.CREDENTIAL_KIND_SERVICE) {
      if (rule.profile().equals("ADMIN_WEB"))
        return deny(action, ERROR_CODE_SESSION_REQUIRED, false);
      if (rule.profile().equals("PROTOCOL")) return deny(action, ERROR_CODE_SCOPE_DENIED, false);
      var worker = f.worker();
      var accepted = f.accepted();
      if (worker == null
          || accepted == null
          || !WORKERS.contains(worker.purpose())
          || !worker.issuer().equals(c.getIssuerService())
          || !worker.target().equals(target)
          || !worker.actions().contains(action)
          || !now.isBefore(worker.validUntil())
          || worker.requiredScope().isBlank()
          || !verified.scopes().contains(worker.requiredScope())
          || !identity.scopes().contains(worker.requiredScope())
          || target.operation() == null
          || !target.operation().equals(accepted.operation())
          || !accepted.target().equals(target)
          || !accepted.actions().contains(action))
        return deny(action, ERROR_CODE_SCOPE_DENIED, false);
      if (!Objects.equals(
          verified.tenantId(), target.tenant() == null ? null : target.tenant().toString()))
        return deny(action, ERROR_CODE_TENANT_MISMATCH, false);
      return integrity(action, f, false);
    }
    if (!c.hasActorUserId() || verified.actorId() == null)
      return deny(action, ERROR_CODE_AUTHENTICATION_REQUIRED, false);
    if (!web && !user && !adminKind) return deny(action, ERROR_CODE_AUTHENTICATION_REQUIRED, false);
    if (user && verified.tenantId() == null) return deny(action, ERROR_CODE_TENANT_REQUIRED, audit);
    if (web
        && (!c.hasSessionId()
            || c.getSessionId().isBlank()
            || !c.getSessionId().equals(identity.sessionId())))
      return deny(action, ERROR_CODE_SESSION_REQUIRED, audit);
    if ((user || adminKind)
        && (!c.hasCredentialId()
            || c.getCredentialId().isBlank()
            || !c.getCredentialId().equals(identity.credentialId())))
      return deny(action, ERROR_CODE_TOKEN_REVOKED, audit);
    if (web && identity.globalAdmin() && !identity.webMfa())
      return deny(action, ERROR_CODE_MFA_REQUIRED, true);
    boolean modify = !rule.effect().equals("READ") && !rule.effect().equals("PROTOCOL");
    String needed = modify ? "write" : "read";
    if (!rule.effect().equals("PROTOCOL")
        && (!verified.scopes().contains(needed) || !identity.scopes().contains(needed)))
      return deny(action, ERROR_CODE_SCOPE_DENIED, audit);
    if (f.channel() == Channel.MCP && !user && !adminKind)
      return deny(action, ERROR_CODE_SCOPE_DENIED, audit);
    if (action == Action.TENANT_CREATE && web && kind != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
      return deny(action, ERROR_CODE_SESSION_REQUIRED, audit);
    if ((rule.userMode().equals("SESSION_REQUIRED") || rule.profile().equals("ADMIN_WEB")) && !web)
      return deny(action, ERROR_CODE_SESSION_REQUIRED, audit);
    if (rule.effect().equals("PROTOCOL")
        && kind != CredentialKind.CREDENTIAL_KIND_USER_OAUTH
        && kind != CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
      return deny(action, ERROR_CODE_SCOPE_DENIED, audit);
    boolean bypass = false;
    if (adminKind) {
      if (!identity.globalAdmin()) return deny(action, ERROR_CODE_ADMIN_GRANT_REQUIRED, false);
      if (!adminGrant(c, f.adminGrant(), target, rule, modify, now))
        return deny(action, ERROR_CODE_ADMIN_SCOPE_DENIED, true);
      bypass = true;
    } else if (web && identity.globalAdmin()) bypass = true;
    if (!tenantBinding(verified.tenantId(), target))
      return deny(action, ERROR_CODE_TENANT_MISMATCH, audit);
    if ((!GLOBAL.contains(rule.scope()) || action == Action.PAT_CREATE) && target.tenant() == null)
      return deny(action, ERROR_CODE_TENANT_REQUIRED, audit);
    if (!bypass
        && !GLOBAL.contains(rule.scope())
        && verified.tenantId() == null
        && !Set.of("INVITE_ACCEPT", "LEAVE").contains(rule.profile()))
      return deny(action, ERROR_CODE_TENANT_REQUIRED, false);
    Decision invariants = integrity(action, f, audit);
    if (!invariants.capability().getAllowed()) return invariants;
    if (bypass) return allow(action, true);
    if (rule.profile().equals("ADMIN") || rule.profile().equals("ADMIN_WEB"))
      return deny(action, ERROR_CODE_ADMIN_GRANT_REQUIRED, false);
    if (rule.grantOwner() && !f.ownsResource()) return deny(action, ERROR_CODE_SCOPE_DENIED, false);
    if ((!GLOBAL.contains(rule.scope()) || action == Action.PAT_CREATE)
        && !Set.of("INVITE_ACCEPT", "LEAVE").contains(rule.profile())) {
      var membership = f.membership();
      if (membership == null
          || membership.getState() != Membership.StateValue.STATE_ACTIVE
          || !Objects.equals(verified.actorId(), membership.getUserId())
          || !Objects.equals(target.tenant().toString(), membership.getTenantId())
          || !c.hasMembershipGeneration()
          || !c.getMembershipGeneration().equals(membership.getTenantMembershipVersion()))
        return deny(action, ERROR_CODE_MEMBERSHIP_INACTIVE, false);
    }
    String role = role(rule, f);
    if (!rule.grantOwner() && !rule.roles().contains(role))
      return deny(
          action,
          rule.roles().equals(List.of("OWNER"))
              ? ERROR_CODE_OWNER_REQUIRED
              : (role.equals("NONE") && Set.of("PROJECT", "ISSUE").contains(rule.scope())
                  ? ERROR_CODE_RESOURCE_NOT_FOUND
                  : ERROR_CODE_SCOPE_DENIED),
          false);
    var errors = EnumSet.noneOf(ErrorCode.class);
    errors.addAll(f.businessDenials().get(action));
    var reasons = EnumSet.noneOf(RestrictionCode.class);
    if (!GLOBAL.contains(rule.scope())) {
      if (f.lifecycle() == Tenant.LifecycleValue.LIFECYCLE_DELETED
          || f.lifecycle() == Tenant.LifecycleValue.LIFECYCLE_DELETING
          || f.lifecycle() == Tenant.LifecycleValue.LIFECYCLE_UNSPECIFIED
          || f.lifecycle() == Tenant.LifecycleValue.UNRECOGNIZED)
        errors.add(ERROR_CODE_INVALID_STATE);
      if (f.lifecycle() != Tenant.LifecycleValue.LIFECYCLE_ACTIVE
          && !Set.of(
                  "CONTROL",
                  "PENDING_CONTROL",
                  "FINANCE_READ",
                  "PAYMENT",
                  "FINANCE_RECOVERY",
                  "LEAVE",
                  "COMPANY_DELETE")
              .contains(rule.profile())) errors.add(ERROR_CODE_INVALID_STATE);
      if (rule.profile().equals("PENDING_CONTROL")
          && f.lifecycle() != Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT)
        errors.add(ERROR_CODE_INVALID_STATE);
    }
    var restrictions = EnumSet.noneOf(RestrictionCode.class);
    restrictions.addAll(f.independentRestrictions());
    Entitlement entitlement = f.entitlement();
    if (entitlement != null) {
      try {
        entitlement = EntitlementProjection.validate(entitlement);
        if (target.tenant() == null
            || !target.tenant().toString().equals(entitlement.getTenantId()))
          throw new IllegalArgumentException();
        restrictions.addAll(entitlement.getRestrictionsList());
        if (now.isBefore(Instant.parse(entitlement.getValidFrom()))
            || (entitlement.hasValidUntil()
                && !now.isBefore(Instant.parse(entitlement.getValidUntil())))
            || (entitlement.hasPaidThrough()
                && !now.isBefore(Instant.parse(entitlement.getPaidThrough()))))
          restrictions.add(RESTRICTION_CODE_PAYMENT_EXPIRED);
      } catch (RuntimeException invalid) {
        entitlement = null;
      }
    }
    if (!GLOBAL.contains(rule.scope())
        && !NO_COMMERCIAL.contains(rule.profile())
        && !CONTENT.contains(rule.profile())
        && entitlement == null) errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    for (var restriction : restrictions)
      if (!(action == Action.NOTIFICATION_READ && f.controlNotification())
          && denied(rule.profile(), restriction, f, errors)) reasons.add(restriction);
    if (rule.profile().equals("TRANSFER") && f.planKind() == PlanKind.UNKNOWN)
      errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    if (rule.profile().equals("TRANSFER")
        && (entitlement == null
            || f.planKind() != PlanKind.PAID
            || !entitlement.hasPaidThrough()
            || !now.isBefore(Instant.parse(entitlement.getPaidThrough()))))
      errors.add(ERROR_CODE_READ_ONLY);
    if (rule.profile().equals("RECOVERY") && !Set.of("OWNER", "TENANT_ADMIN").contains(role)) {
      for (var reason : List.of(RESTRICTION_CODE_PAYMENT_EXPIRED, RESTRICTION_CODE_OVER_QUOTA))
        if (restrictions.contains(reason)) reasons.add(reason);
    }
    checkFlags(action, rule, c, f, errors);
    if (!reasons.isEmpty()) errors.add(ERROR_CODE_READ_ONLY);
    return result(action, errors, reasons, false);
  }

  private static String role(Rule rule, CapabilityFacts f) {
    if (GLOBAL.contains(rule.scope()) || Set.of("INVITE_ACCEPT", "LEAVE").contains(rule.profile()))
      return "MEMBER";
    var m = f.membership();
    if (m != null && m.getState() == Membership.StateValue.STATE_ACTIVE) {
      if (m.getRole() == Membership.RoleValue.ROLE_TENANT_ADMIN)
        return m.getIsOwner() ? "OWNER" : "TENANT_ADMIN";
      if (m.getRole() != Membership.RoleValue.ROLE_MEMBER) return "NONE";
      if (Set.of("PROJECT", "ISSUE").contains(rule.scope()))
        return switch (f.projectRole()) {
          case ADMIN -> "PROJECT_ADMIN";
          case MEMBER -> "MEMBER";
          case VIEWER -> "VIEWER";
          case NONE -> "NONE";
        };
      return "MEMBER";
    }
    return "NONE";
  }

  private static boolean adminGrant(
      TrustedTenantContext c, AdminGrant g, Target target, Rule rule, boolean modify, Instant now) {
    if (g == null
        || g.getRevoked()
        || !c.hasGrantId()
        || !g.getId().equals(c.getGrantId())
        || !g.getActorUserId().equals(c.getActorUserId())
        || !c.hasGrantGeneration()
        || !g.getVersion().equals(c.getGrantGeneration())
        || (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_PAT
            && g.getKind() != AdminGrant.KindValue.KIND_PAT)
        || (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH
            && g.getKind() != AdminGrant.KindValue.KIND_OAUTH)
        || !c.hasAdministrativeScope()
        || !c.getAdministrativeScope().equals(g.getScope())
        || !c.hasAdministrativeLevel()
        || c.getAdministrativeLevel() != g.getLevel()
        || (g.getLevel() != AdminGrant.LevelValue.LEVEL_READ
            && g.getLevel() != AdminGrant.LevelValue.LEVEL_MODIFY)
        || (modify && g.getLevel() != AdminGrant.LevelValue.LEVEL_MODIFY)) return false;
    try {
      if (!now.isBefore(Instant.parse(g.getExpiresAt()))) return false;
    } catch (RuntimeException invalid) {
      return false;
    }
    boolean platform = g.getScope().getKind() == AdminScope.KindValue.KIND_PLATFORM;
    if (platform && g.getScope().getTenantIdsCount() != 0) return false;
    if (g.getScope().getKind() == AdminScope.KindValue.KIND_TENANT_SET) {
      if (g.getScope().getTenantIdsCount() == 0
          || g.getScope().getTenantIdsCount() > 100
          || new HashSet<>(g.getScope().getTenantIdsList()).size()
              != g.getScope().getTenantIdsCount()) return false;
      try {
        for (String tenant : g.getScope().getTenantIdsList())
          if (!UUID.fromString(tenant).toString().equals(tenant)) return false;
      } catch (RuntimeException invalid) {
        return false;
      }
    }
    if (Set.of("PLATFORM", "GLOBAL_ACCOUNT", "NEW_TENANT").contains(rule.scope()) && !platform)
      return false;
    if (rule.scope().equals("GLOBAL_ACCOUNT")
        && (!g.getManageGlobalAccounts() || !c.getManageGlobalAccounts())) return false;
    if (rule.action().equals("admin.audit_read") && !platform) return false;
    if (!target.selectedTenants().isEmpty()) {
      if (!rule.scope().equals("ADMIN_SELECTED_SCOPE")) return false;
      return platform
          || (g.getScope().getKind() == AdminScope.KindValue.KIND_TENANT_SET
              && g.getScope()
                  .getTenantIdsList()
                  .containsAll(target.selectedTenants().stream().map(UUID::toString).toList()));
    }
    return platform
        || (g.getScope().getKind() == AdminScope.KindValue.KIND_TENANT_SET
            && target.tenant() != null
            && g.getScope().getTenantIdsList().contains(target.tenant().toString()));
  }

  private static boolean denied(
      String profile, RestrictionCode reason, CapabilityFacts f, Set<ErrorCode> errors) {
    return switch (profile) {
      case "CONTENT_READ", "DIRECTORY", "EXPORT", "INBOX" ->
          reason == RESTRICTION_CODE_PENDING_FIRST_PAYMENT
              || ((reason == RESTRICTION_CODE_OWNER_PAUSE
                      || reason == RESTRICTION_CODE_PLATFORM_BLOCK)
                  && !flag(f, Flag.READ_WHILE_RESTRICTED, errors));
      case "NORMAL", "UPLOAD", "UPLOAD_FINISH" -> true;
      case "RECOVERY", "INVITE_ACCEPT", "OWNER_CONTROL", "AUTOPAY" ->
          Set.of(
                      RESTRICTION_CODE_PLATFORM_BLOCK,
                      RESTRICTION_CODE_PAYMENT_DISPUTE,
                      RESTRICTION_CODE_PENDING_FIRST_PAYMENT)
                  .contains(reason)
              || (reason == RESTRICTION_CODE_OWNER_PAUSE && !profile.equals("OWNER_CONTROL"));
      case "TRANSFER" -> reason != RESTRICTION_CODE_OWNER_PAUSE;
      case "COMPANY_DELETE" ->
          Set.of(
                  RESTRICTION_CODE_PLATFORM_BLOCK,
                  RESTRICTION_CODE_PAYMENT_DISPUTE,
                  RESTRICTION_CODE_PENDING_FIRST_PAYMENT)
              .contains(reason);
      default -> false;
    };
  }

  private static void checkFlags(
      Action action, Rule rule, TrustedTenantContext c, CapabilityFacts f, Set<ErrorCode> errors) {
    boolean user =
        c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_USER_PAT
            || c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_USER_OAUTH;
    if (f.channel() == Channel.MCP) {
      requireFlag(f, Flag.MCP, errors);
      if (f.planKind() == PlanKind.UNKNOWN) errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
      else if (f.planKind() == PlanKind.FREE) requireFlag(f, Flag.FREE_MCP, errors);
    }
    if ((user && c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_USER_PAT)
        || action == Action.PAT_CREATE) {
      requireFlag(f, Flag.PERSONAL_API_TOKENS, errors);
      if (f.planKind() == PlanKind.UNKNOWN) errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
      else if (f.planKind() == PlanKind.FREE) requireFlag(f, Flag.FREE_PERSONAL_API_TOKENS, errors);
    }
    Flag required =
        switch (action) {
          case TENANT_TRANSFER_OWNERSHIP -> Flag.PAID_TENANT_TRANSFER;
          case TENANT_PAUSE -> Flag.OWNER_PAUSE;
          case INVITATION_CREATE, INVITATION_RESEND -> Flag.INVITATIONS;
          case PROJECT_CREATE -> Flag.PROJECT_CREATION;
          case WORKFLOW_CONFIGURE -> Flag.WORKFLOW_CONFIGURATION;
          case ISSUE_TYPE_CONFIGURE -> Flag.ISSUE_TYPE_CONFIGURATION;
          case ATTACHMENT_UPLOAD, ATTACHMENT_CONFIRM -> Flag.ATTACHMENT_UPLOAD;
          case EXPORT_CREATE -> Flag.EXPORT;
          case NOTIFICATION_READ -> f.controlNotification() ? null : Flag.INBOX;
          case ACCOUNT_AVATAR_WRITE -> Flag.AVATAR_UPLOAD;
          case PAT_CREATE -> Flag.PERSONAL_API_TOKENS;
          case BILLING_AUTOPAY_CONSENT -> Flag.AUTOPAY;
          case BILLING_PAY ->
              switch (f.paymentChannel()) {
                case WIRE -> Flag.WIRE_PAYMENT;
                case PSP -> Flag.PSP_PAYMENT;
                case NONE -> null;
              };
          default -> null;
        };
    if (action == Action.BILLING_PAY && f.paymentChannel() == PaymentChannel.NONE)
      errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    if (required != null && !pinned(f, action, required)) requireFlag(f, required, errors);
  }

  private static void requireFlag(CapabilityFacts f, Flag flag, Set<ErrorCode> errors) {
    Boolean value = f.flags().get(flag);
    if (value == null) errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    else if (!value) errors.add(ERROR_CODE_CAPABILITY_DISABLED);
  }

  private static boolean pinned(CapabilityFacts f, Action action, Flag flag) {
    // Never let an accepted operation bypass authentication/channel/grant gates.
    return f.accepted() != null
        && f.target().operation() != null
        && f.target().operation().equals(f.accepted().operation())
        && f.accepted().target().equals(f.target())
        && f.accepted().actions().contains(action)
        && f.accepted().pinnedFlags().contains(flag);
  }

  private static boolean flag(CapabilityFacts f, Flag flag, Set<ErrorCode> errors) {
    Boolean value = f.flags().get(flag);
    if (value == null) {
      errors.add(ERROR_CODE_DEPENDENCY_UNAVAILABLE);
      return false;
    }
    return value;
  }

  private static Decision integrity(Action a, CapabilityFacts f, boolean audit) {
    Rule rule = RULES.get(a);
    Target t = f.target();
    if (!t.selectedTenants().isEmpty() && !rule.scope().equals("ADMIN_SELECTED_SCOPE"))
      return deny(a, ERROR_CODE_SCOPE_DENIED, audit);
    if (!GLOBAL.contains(rule.scope()) && t.tenant() == null)
      return deny(a, ERROR_CODE_TENANT_REQUIRED, audit);
    if ((f.lifecycle() == Tenant.LifecycleValue.LIFECYCLE_DELETED
            || f.lifecycle() == Tenant.LifecycleValue.LIFECYCLE_DELETING)
        && Set.of("NORMAL", "UPLOAD", "UPLOAD_FINISH", "TRANSFER", "EXPORT")
            .contains(rule.profile())) return deny(a, ERROR_CODE_INVALID_STATE, audit);
    if ((Set.of("PROJECT", "ISSUE").contains(rule.scope()) && t.project() == null)
        || (rule.scope().equals("ISSUE") && t.resource() == null))
      return deny(a, ERROR_CODE_RESOURCE_NOT_FOUND, audit);
    if (rule.scope().equals("GLOBAL_SELF") && !Objects.equals(f.actor(), t.resource()))
      return deny(a, ERROR_CODE_SCOPE_DENIED, audit);
    if ((a == Action.ACCOUNT_DELETE || a == Action.MEMBERSHIP_LEAVE)
        && f.membership() != null
        && f.membership().getIsOwner())
      return deny(a, ERROR_CODE_OWNER_MEMBERSHIP_PROTECTED, audit);
    return f.resourceResolved()
        ? result(a, f.integrity().get(a), Set.of(), audit)
        : deny(a, ERROR_CODE_RESOURCE_NOT_FOUND, audit);
  }

  private static Decision deny(Action a, ErrorCode reason, boolean audit) {
    return result(a, Set.of(reason), Set.of(), audit);
  }

  private static Decision allow(Action a, boolean audit) {
    var decision = result(a, Set.of(), Set.of(), audit);
    return new Decision(decision.capability(), audit, audit, Set.of());
  }

  private static Decision result(
      Action a,
      Collection<ErrorCode> errors,
      Collection<RestrictionCode> restrictions,
      boolean audit) {
    return new Decision(
        Capability.newBuilder()
            .setAction(a.wire())
            .setAllowed(errors.isEmpty() && restrictions.isEmpty())
            .addAllDenialCodes(
                errors.stream().sorted(Comparator.comparingInt(ErrorCode::getNumber)).toList())
            .addAllRestrictions(
                restrictions.stream()
                    .sorted(Comparator.comparingInt(REASON_ORDER::indexOf))
                    .toList())
            .build(),
        audit,
        false,
        Set.of());
  }

  private static boolean tenantBinding(String verified, Target target) {
    return verified == null
        || verified.equals(target.tenant() == null ? null : target.tenant().toString())
        || (target.tenant() == null
            && target.selectedTenants().size() == 1
            && target.selectedTenants().iterator().next().toString().equals(verified));
  }

  private static Set<RestrictionCode> activeForAudit(
      CapabilityFacts facts, Target target, Instant now) {
    var active = EnumSet.noneOf(RestrictionCode.class);
    active.addAll(facts.independentRestrictions());
    if (facts.entitlement() != null)
      try {
        Entitlement e = EntitlementProjection.validate(facts.entitlement());
        if (target.tenant() != null && target.tenant().toString().equals(e.getTenantId())) {
          active.addAll(e.getRestrictionsList());
          if (now.isBefore(Instant.parse(e.getValidFrom()))
              || (e.hasValidUntil() && !now.isBefore(Instant.parse(e.getValidUntil())))
              || (e.hasPaidThrough() && !now.isBefore(Instant.parse(e.getPaidThrough()))))
            active.add(RESTRICTION_CODE_PAYMENT_EXPIRED);
        }
      } catch (RuntimeException invalid) {
        /* Do not invent a financial cause from invalid/unavailable data. */
      }
    return Set.copyOf(active);
  }

  private static Map<Action, Rule> rules() {
    try (var stream =
        CapabilityEvaluator.class.getClassLoader().getResourceAsStream("capability/actions.json")) {
      var rows =
          JsonMapper.builder()
              .build()
              .readValue(Objects.requireNonNull(stream), new TypeReference<List<Rule>>() {});
      var result = new EnumMap<Action, Rule>(Action.class);
      for (var a : Action.values()) {
        var matching = rows.stream().filter(r -> a.wire().equals(r.action())).toList();
        if (matching.size() != 1)
          throw new IllegalStateException("Incomplete capability action table");
        Rule row = matching.getFirst();
        if (!Set.of("READ", "MODIFY", "PROTOCOL").contains(row.effect())
            || !Set.of(
                    "ACCOUNT_VISIBLE_TENANTS",
                    "ADMIN_SELECTED_SCOPE",
                    "EXISTING_GRANT",
                    "GLOBAL_ACCOUNT",
                    "GLOBAL_SELF",
                    "ISSUE",
                    "NEW_TENANT",
                    "PLATFORM",
                    "PROJECT",
                    "TENANT",
                    "TENANT_FINANCE",
                    "TENANT_PROJECT_SUBSET")
                .contains(row.scope())
            || !Set.of(
                    "NORMAL",
                    "CONTENT_READ",
                    "DIRECTORY",
                    "RECOVERY",
                    "INVITE_ACCEPT",
                    "TRANSFER",
                    "COMPANY_DELETE",
                    "LEAVE",
                    "ACCOUNT",
                    "ACCOUNT_DELETE",
                    "CONTROL",
                    "FINANCE_READ",
                    "OWNER_CONTROL",
                    "PENDING_CONTROL",
                    "PAYMENT",
                    "FINANCE_RECOVERY",
                    "AUTOPAY",
                    "EXPORT",
                    "INBOX",
                    "PERSONAL_INBOX",
                    "UPLOAD",
                    "UPLOAD_FINISH",
                    "ADMIN",
                    "ADMIN_WEB",
                    "PROTOCOL")
                .contains(row.profile())
            || !Set.of("READ", "WRITE", "SESSION_REQUIRED", "NOT_APPLICABLE")
                .contains(row.userMode())
            || !Set.of("OWNER", "TENANT_ADMIN", "PROJECT_ADMIN", "MEMBER", "VIEWER")
                .containsAll(row.roles()))
          throw new IllegalStateException("Invalid capability rule");
        result.put(a, row);
      }
      if (rows.size() != result.size())
        throw new IllegalStateException("Unknown capability action");
      return Map.copyOf(result);
    } catch (Exception invalid) {
      throw new ExceptionInInitializerError(invalid);
    }
  }

  private static DomainException unavailable() {
    return new DomainException(
        DomainStatus.UNAVAILABLE, "Current capability authority unavailable");
  }
}
