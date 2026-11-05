package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.api.b2b.v1.ErrorCode.*;
import static ru.taska.api.b2b.v1.RestrictionCode.*;
import static ru.taska.capability.Action.*;
import static ru.taska.capability.CapabilityFacts.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.capability.*;
import ru.taska.exception.DomainException;

class CapabilityEvaluatorTest {
  static final UUID ACTOR = UUID.randomUUID(),
      TENANT = UUID.randomUUID(),
      PROJECT = UUID.randomUUID(),
      RESOURCE = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static final String SESSION = UUID.randomUUID().toString(),
      CREDENTIAL = UUID.randomUUID().toString();
  final AtomicReference<Instant> time = new AtomicReference<>(NOW);
  final Clock clock =
      new Clock() {
        public Instant instant() {
          return time.get();
        }

        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }
      };
  CapabilityTestFacts facts;
  CapabilityEvaluator evaluator;
  TrustedTenantContext.Builder claims;
  Target target = new Target(TENANT, PROJECT, RESOURCE, null);

  @BeforeEach
  void setup() {
    facts = new CapabilityTestFacts(ACTOR, TENANT, clock, SESSION, CREDENTIAL);
    evaluator = new CapabilityEvaluator(facts::current, clock);
    claims =
        TrustedTenantContext.newBuilder()
            .setContextVersion(1)
            .setActorUserId(ACTOR.toString())
            .setTenantId(TENANT.toString())
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .setSessionId(SESSION)
            .setCredentialId(CREDENTIAL)
            .setActorGeneration("1")
            .setMembershipGeneration("2")
            .addScopes("read")
            .addScopes("write")
            .setAction("evaluateCapabilities")
            .setIssuerService("api-gateway")
            .setAudienceService("project-service")
            .setIssuedAt(NOW.toString())
            .setExpiresAt(NOW.plusSeconds(30).toString())
            .setAssertionId(UUID.randomUUID().toString());
  }

  static <T> Mono<T> as(TrustedTenantContext claims, Mono<T> work) {
    var request = UUID.randomUUID().toString();
    var wire =
        RequestContext.newBuilder()
            .setContext(claims)
            .setHeader(Header.newBuilder().setRequestId(request))
            .build();
    var verified =
        new VerifiedTenantContext(
            claims.hasActorUserId() ? claims.getActorUserId() : null,
            claims.hasTenantId() ? claims.getTenantId() : null,
            request,
            claims.getAction(),
            Set.copyOf(claims.getScopesList()),
            wire.toByteArray());
    return work.contextWrite(verified::install);
  }

  CapabilityEvaluator.Decision decide(Action action) {
    return as(claims.build(), evaluator.evaluate(action, target)).block();
  }

  void allowed(Action action) {
    var d = decide(action).capability();
    assertTrue(d.getAllowed(), action + ": " + d);
  }

  void denied(Action action, ErrorCode code) {
    var d = decide(action).capability();
    assertFalse(d.getAllowed(), action.toString());
    assertTrue(d.getDenialCodesList().contains(code), d.toString());
  }

  void admin(AdminGrant.LevelValue level, boolean platform, boolean manage) {
    facts.globalAdmin = true;
    var scope =
        AdminScope.newBuilder()
            .setKind(
                platform
                    ? AdminScope.KindValue.KIND_PLATFORM
                    : AdminScope.KindValue.KIND_TENANT_SET);
    if (!platform) scope.addTenantIds(TENANT.toString());
    String id = UUID.randomUUID().toString();
    facts.grant =
        AdminGrant.newBuilder()
            .setId(id)
            .setActorUserId(ACTOR.toString())
            .setVersion("1")
            .setKind(AdminGrant.KindValue.KIND_PAT)
            .setLevel(level)
            .setScope(scope)
            .setManageGlobalAccounts(manage)
            .setExpiresAt(NOW.plusSeconds(600).toString())
            .build();
    claims
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
        .setGrantId(id)
        .setGrantGeneration("1")
        .setAdministrativeScope(scope)
        .setAdministrativeLevel(level)
        .setManageGlobalAccounts(manage);
  }

  @Test
  void independentReasonsAccumulateAndNewPaymentCannotClearPauseBlockOrOtherDispute() {
    facts.restrictions =
        Set.of(
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_PAYMENT_DISPUTE);
    facts.extraEntitlementReasons = Set.of(RESTRICTION_CODE_PAYMENT_EXPIRED);
    var first = decide(ISSUE_UPDATE).capability();
    assertEquals(4, first.getRestrictionsCount());
    facts.extraEntitlementReasons = Set.of();
    facts.entitlementVersion++;
    var paid = decide(ISSUE_UPDATE).capability();
    assertEquals(3, paid.getRestrictionsCount());
    assertFalse(paid.getAllowed());
    facts.restrictions = Set.of();
    allowed(ISSUE_UPDATE);
  }

  @Test
  void financialReadOnlyUsesExplicitRecoveryAllowlistNotUsageReduction() {
    facts.extraEntitlementReasons =
        Set.of(RESTRICTION_CODE_PAYMENT_EXPIRED, RESTRICTION_CODE_OVER_QUOTA);
    for (var action :
        List.of(
            PROJECT_READ,
            ISSUE_READ,
            BILLING_READ,
            BILLING_PAY,
            BILLING_SUBSCRIPTION_CHANGE,
            BILLING_REFUND_REQUEST,
            MEMBERSHIP_REMOVE,
            INVITATION_REVOKE,
            PROJECT_DELETE,
            INVITATION_ACCEPT,
            TENANT_DELETE)) allowed(action);
    for (var action :
        List.of(
            ISSUE_CREATE,
            ISSUE_UPDATE,
            ISSUE_DELETE,
            COMMENT_DELETE,
            ATTACHMENT_DELETE,
            ATTACHMENT_UPLOAD,
            PROJECT_ARCHIVE)) denied(action, ERROR_CODE_READ_ONLY);
    target = new Target(TENANT, null, ACTOR, null);
    facts.owner = false;
    facts.tenantAdmin = false;
    allowed(ACCOUNT_DELETE);
  }

  @Test
  void ownerPauseAllowsTransferDeleteButBlockAndDisputeDoNot() {
    facts.restrictions = Set.of(RESTRICTION_CODE_OWNER_PAUSE);
    allowed(TENANT_TRANSFER_OWNERSHIP);
    allowed(TENANT_DELETE);
    for (var r : List.of(RESTRICTION_CODE_PLATFORM_BLOCK, RESTRICTION_CODE_PAYMENT_DISPUTE)) {
      facts.restrictions = Set.of(RESTRICTION_CODE_OWNER_PAUSE, r);
      denied(TENANT_TRANSFER_OWNERSHIP, ERROR_CODE_READ_ONLY);
      denied(TENANT_DELETE, ERROR_CODE_READ_ONLY);
    }
    facts.restrictions = Set.of(RESTRICTION_CODE_OWNER_PAUSE);
    facts.plan = PlanKind.FREE;
    denied(TENANT_TRANSFER_OWNERSHIP, ERROR_CODE_READ_ONLY);
    facts.plan = PlanKind.PAID;
    facts.flags.put(Flag.PAID_TENANT_TRANSFER, false);
    denied(TENANT_TRANSFER_OWNERSHIP, ERROR_CODE_CAPABILITY_DISABLED);
  }

  @Test
  void tenantAdminInheritsProjectAdminButHasNoOwnerFinancialRights() {
    facts.owner = false;
    facts.projectRole = ProjectRole.NONE;
    allowed(PROJECT_UPDATE);
    allowed(MEMBERSHIP_REMOVE);
    denied(BILLING_READ, ERROR_CODE_OWNER_REQUIRED);
    facts.tenantAdmin = false;
    facts.projectRole = ProjectRole.VIEWER;
    allowed(ISSUE_READ);
    denied(ISSUE_UPDATE, ERROR_CODE_SCOPE_DENIED);
    facts.projectRole = ProjectRole.NONE;
    denied(ISSUE_READ, ERROR_CODE_RESOURCE_NOT_FOUND);
    facts.projectRole = ProjectRole.ADMIN;
    allowed(PROJECT_UPDATE);
    denied(PROJECT_CREATE, ERROR_CODE_SCOPE_DENIED);
  }

  @Test
  void currentMembershipRoleAndCredentialRevocationDoNotWaitForAnyCache() {
    allowed(PROJECT_CREATE);
    facts.tenantAdmin = false;
    denied(PROJECT_CREATE, ERROR_CODE_SCOPE_DENIED);
    facts.tenantAdmin = true;
    facts.membershipState = Membership.StateValue.STATE_SUSPENDED;
    denied(PROJECT_CREATE, ERROR_CODE_MEMBERSHIP_INACTIVE);
    facts.membershipState = Membership.StateValue.STATE_ACTIVE;
    facts.membershipGeneration = "3";
    denied(PROJECT_CREATE, ERROR_CODE_MEMBERSHIP_INACTIVE);
    assertTrue(facts.reads.get() >= 4);
  }

  @Test
  void forgedOrRevokedGlobalRoleAndUserPatNeverAcquireAdminBypass() {
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, false);
    facts.globalAdmin = false;
    denied(ISSUE_UPDATE, ERROR_CODE_ADMIN_GRANT_REQUIRED);
    facts.globalAdmin = true;
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT);
    facts.restrictions = Set.of(RESTRICTION_CODE_OWNER_PAUSE);
    denied(ISSUE_UPDATE, ERROR_CODE_READ_ONLY);
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT);
    facts.webMfa = false;
    denied(ISSUE_UPDATE, ERROR_CODE_MFA_REQUIRED);
  }

  @Test
  void adminBypassKeepsScopeLevelAuthenticationIntegrityAndAudit() {
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, false);
    facts.restrictions =
        Set.of(
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_PAYMENT_DISPUTE);
    facts.flags.replaceAll((k, v) -> false);
    facts.entitlementAvailable = false;
    facts.membershipState = Membership.StateValue.STATE_REMOVED;
    var decision = decide(ISSUE_UPDATE);
    assertTrue(decision.capability().getAllowed());
    assertTrue(decision.auditRequired());
    facts.integrityErrors.put(ISSUE_UPDATE, Set.of(ERROR_CODE_VERSION_CONFLICT));
    denied(ISSUE_UPDATE, ERROR_CODE_VERSION_CONFLICT);
    facts.integrityErrors.clear();
    facts.grant = facts.grant.toBuilder().setLevel(AdminGrant.LevelValue.LEVEL_READ).build();
    claims.setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ);
    allowed(ISSUE_READ);
    denied(ISSUE_UPDATE, ERROR_CODE_ADMIN_SCOPE_DENIED);
    denied(EXPORT_CREATE, ERROR_CODE_ADMIN_SCOPE_DENIED);
    claims.clearTenantId();
    target = new Target(UUID.randomUUID(), PROJECT, RESOURCE, null);
    denied(ISSUE_READ, ERROR_CODE_ADMIN_SCOPE_DENIED);
  }

  @Test
  void administrativeIntegrationsCannotExpandGrantsAndNeedPlatformAccountPermission() {
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, true);
    denied(ADMIN_GRANT_ISSUE_EXPAND, ERROR_CODE_SESSION_REQUIRED);
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, true, false);
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, true, true);
    allowed(ADMIN_ACCOUNT_BLOCK);
    facts.grant = facts.grant.toBuilder().setRevoked(true).build();
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
  }

  @Test
  void acceptedReceiptOnlyPinsActionFlagAndCannotBypassPatOrMcpDisable() {
    facts.flags.put(Flag.ATTACHMENT_UPLOAD, false);
    denied(ATTACHMENT_CONFIRM, ERROR_CODE_CAPABILITY_DISABLED);
    UUID op = UUID.randomUUID();
    target = new Target(TENANT, PROJECT, RESOURCE, op);
    facts.accepted =
        new Accepted(op, target, Set.of(ATTACHMENT_CONFIRM), Set.of(Flag.ATTACHMENT_UPLOAD));
    allowed(ATTACHMENT_CONFIRM);
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT);
    facts.flags.put(Flag.PERSONAL_API_TOKENS, false);
    denied(ATTACHMENT_CONFIRM, ERROR_CODE_CAPABILITY_DISABLED);
    facts.flags.put(Flag.PERSONAL_API_TOKENS, true);
    facts.channel = Channel.MCP;
    facts.flags.put(Flag.MCP, false);
    denied(ATTACHMENT_CONFIRM, ERROR_CODE_CAPABILITY_DISABLED);
  }

  @Test
  void mcpPatAndFreeFlagsIntersectPerTargetTenant() {
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT);
    facts.channel = Channel.MCP;
    facts.plan = PlanKind.FREE;
    denied(ISSUE_READ, ERROR_CODE_CAPABILITY_DISABLED);
    facts.flags.put(Flag.FREE_MCP, true);
    denied(ISSUE_READ, ERROR_CODE_CAPABILITY_DISABLED);
    facts.flags.put(Flag.FREE_PERSONAL_API_TOKENS, true);
    allowed(ISSUE_READ);
    facts.plan = PlanKind.PAID;
    facts.flags.put(Flag.FREE_MCP, false);
    facts.flags.put(Flag.FREE_PERSONAL_API_TOKENS, false);
    allowed(ISSUE_READ);
    target = new Target(UUID.randomUUID(), PROJECT, RESOURCE, null);
    denied(ISSUE_READ, ERROR_CODE_TENANT_MISMATCH);
  }

  @Test
  void contentReadFlagDoesNotHideControlOrFinancialRecovery() {
    facts.restrictions = Set.of(RESTRICTION_CODE_OWNER_PAUSE, RESTRICTION_CODE_PLATFORM_BLOCK);
    allowed(ISSUE_READ);
    facts.flags.put(Flag.READ_WHILE_RESTRICTED, false);
    denied(ISSUE_READ, ERROR_CODE_READ_ONLY);
    allowed(TENANT_READ);
    allowed(BILLING_READ);
    allowed(BILLING_PAY);
    facts.controlNotification = true;
    facts.flags.put(Flag.INBOX, false);
    allowed(NOTIFICATION_READ);
  }

  @Test
  void missingSnapshotBlocksNewWritesButRetainsAclReadsAndOldInviteRecovery() {
    facts.entitlementAvailable = false;
    denied(ISSUE_CREATE, ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    allowed(ISSUE_READ);
    allowed(BILLING_PAY);
    allowed(INVITATION_ACCEPT);
    facts.entitlementAvailable = true;
    facts.paidThrough = NOW;
    denied(ISSUE_CREATE, ERROR_CODE_READ_ONLY);
    denied(TENANT_TRANSFER_OWNERSHIP, ERROR_CODE_READ_ONLY);
  }

  @Test
  void selfAccountDeletionIgnoresTenantRestrictionsButPreservesIntegrityAndWebChannel() {
    target = new Target(TENANT, null, ACTOR, null);
    facts.owner = false;
    facts.tenantAdmin = false;
    facts.restrictions =
        Set.of(
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_PAYMENT_DISPUTE);
    allowed(ACCOUNT_DELETE);
    facts.integrityErrors.put(ACCOUNT_DELETE, Set.of(ERROR_CODE_LAST_ADMIN));
    denied(ACCOUNT_DELETE, ERROR_CODE_LAST_ADMIN);
    facts.integrityErrors.clear();
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_OAUTH);
    denied(ACCOUNT_DELETE, ERROR_CODE_SESSION_REQUIRED);
  }

  @Test
  void missingOrExpiredAuthorityFailsClosedAndSameContextIsDeterministic() {
    var once = decide(ISSUE_UPDATE);
    var again = decide(ISSUE_UPDATE);
    assertEquals(once, again);
    facts.missingIntegrity = true;
    denied(ISSUE_UPDATE, ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    facts.missingIntegrity = false;
    facts.flags.remove(Flag.PROJECT_CREATION);
    denied(PROJECT_CREATE, ERROR_CODE_DEPENDENCY_UNAVAILABLE);
    evaluator = new CapabilityEvaluator((v, t, a) -> Mono.empty(), clock);
    assertThrows(DomainException.class, () -> decide(ISSUE_READ));
    assertThrows(DomainException.class, () -> evaluator.evaluate(ISSUE_READ, target).block());
  }

  @Test
  void explicitWorkerNeedsDurableScopeReceiptAndNeverTrustsServiceStringAlone() {
    claims
        .clearActorUserId()
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
        .clearScopes()
        .addScopes("worker:recover");
    facts.actor = null;
    denied(ISSUE_UPDATE, ERROR_CODE_SCOPE_DENIED);
    UUID op = UUID.randomUUID();
    target = new Target(TENANT, PROJECT, RESOURCE, op);
    facts.accepted = new Accepted(op, target, Set.of(ISSUE_UPDATE), Set.of());
    facts.worker =
        new Worker(
            "api-gateway",
            "OPERATION_RECOVER",
            "worker:recover",
            target,
            Set.of(ISSUE_UPDATE),
            NOW.plusSeconds(20));
    allowed(ISSUE_UPDATE);
    facts.integrityErrors.put(ISSUE_UPDATE, Set.of(ERROR_CODE_VERSION_CONFLICT));
    denied(ISSUE_UPDATE, ERROR_CODE_VERSION_CONFLICT);
  }

  @Test
  void oldWireInvoicePinsOnlyItsPaymentFlagAndMfaWebHasExplicitAdministrativeException() {
    facts.flags.put(Flag.WIRE_PAYMENT, false);
    denied(BILLING_PAY, ERROR_CODE_CAPABILITY_DISABLED);
    UUID operation = UUID.randomUUID();
    target = new Target(TENANT, null, RESOURCE, operation);
    facts.accepted =
        new Accepted(operation, target, Set.of(BILLING_PAY), Set.of(Flag.WIRE_PAYMENT));
    allowed(BILLING_PAY);
    facts.accepted =
        new Accepted(UUID.randomUUID(), target, Set.of(BILLING_PAY), Set.of(Flag.WIRE_PAYMENT));
    denied(BILLING_PAY, ERROR_CODE_CAPABILITY_DISABLED);
    facts.globalAdmin = true;
    facts.webMfa = true;
    facts.restrictions = Set.of(RESTRICTION_CODE_PLATFORM_BLOCK);
    allowed(BILLING_PAY);
    assertTrue(decide(BILLING_PAY).auditRequired());
    claims.setSessionId(UUID.randomUUID().toString());
    denied(BILLING_PAY, ERROR_CODE_SESSION_REQUIRED);
  }

  @Test
  void missingFlagIsDependencyFailureRatherThanAnExplicitlyDisabledFeature() {
    facts.flags.remove(Flag.PROJECT_CREATION);
    var decision = decide(PROJECT_CREATE).capability();
    assertEquals(List.of(ERROR_CODE_DEPENDENCY_UNAVAILABLE), decision.getDenialCodesList());
    facts.flags.put(Flag.PROJECT_CREATION, false);
    assertEquals(
        List.of(ERROR_CODE_CAPABILITY_DISABLED),
        decide(PROJECT_CREATE).capability().getDenialCodesList());
  }

  @Test
  void initialUnpaidAndDeletedCompaniesNeverGainContentWritesFromLifecycleOrAdminBypass() {
    facts.lifecycle = Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT;
    allowed(TENANT_READ);
    allowed(TENANT_CANCEL_CREATION);
    denied(ISSUE_READ, ERROR_CODE_INVALID_STATE);
    denied(INVITATION_ACCEPT, ERROR_CODE_INVALID_STATE);
    facts.lifecycle = Tenant.LifecycleValue.LIFECYCLE_DELETED;
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, false);
    denied(ISSUE_UPDATE, ERROR_CODE_INVALID_STATE);
  }

  @Test
  void primaryGrantGenerationExpiryAndSpecialAccountPermissionCannotBeClaimedByWire() {
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, true, true);
    allowed(ADMIN_ACCOUNT_BLOCK);
    claims.setGrantGeneration("2");
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
    claims.setGrantGeneration("1");
    facts.grant = facts.grant.toBuilder().setExpiresAt(NOW.toString()).build();
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, true, false);
    claims.setManageGlobalAccounts(true);
    denied(ADMIN_ACCOUNT_BLOCK, ERROR_CODE_ADMIN_SCOPE_DENIED);
  }

  @Test
  void sourceBindingAndEnvelopeTimeAreCheckedEvenWhenRolesWouldOtherwiseAllow() {
    facts.actor = UUID.randomUUID();
    assertThrows(DomainException.class, () -> decide(ISSUE_READ));
    facts.actor = ACTOR;
    time.set(NOW.plusSeconds(30));
    denied(ISSUE_UPDATE, ERROR_CODE_AUTHENTICATION_REQUIRED);
  }

  @Test
  void orderedIndependentRestrictionsRemainStableAcrossInputSetOrder() {
    facts.restrictions =
        Set.of(
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_PAYMENT_DISPUTE);
    facts.extraEntitlementReasons =
        Set.of(
            RESTRICTION_CODE_PAYMENT_EXPIRED,
            RESTRICTION_CODE_OVER_QUOTA,
            RESTRICTION_CODE_PENDING_FIRST_PAYMENT);
    assertEquals(
        List.of(
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PAYMENT_DISPUTE,
            RESTRICTION_CODE_PENDING_FIRST_PAYMENT,
            RESTRICTION_CODE_PAYMENT_EXPIRED,
            RESTRICTION_CODE_OVER_QUOTA),
        decide(ISSUE_UPDATE).capability().getRestrictionsList());
  }

  @Test
  void primaryCredentialAndScopesAreRecheckedRatherThanCopiedFromSignedOldClaims() {
    allowed(ISSUE_UPDATE);
    facts.credentialActive = false;
    denied(ISSUE_UPDATE, ERROR_CODE_TOKEN_REVOKED);
    facts.credentialActive = true;
    facts.actorGeneration = "2";
    denied(ISSUE_UPDATE, ERROR_CODE_AUTHENTICATION_REQUIRED);
    facts.actorGeneration = "1";
    facts.currentScopes = Set.of("read");
    denied(ISSUE_UPDATE, ERROR_CODE_SCOPE_DENIED);
    allowed(ISSUE_READ);
  }

  @Test
  void evenAdministrativeSelfDeleteKeepsOwnerIntegrity() {
    target = new Target(TENANT, null, ACTOR, null);
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, true, true);
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT);
    denied(ACCOUNT_DELETE, ERROR_CODE_OWNER_MEMBERSHIP_PROTECTED);
  }

  @Test
  void quotaBusinessBypassIsExplicitAndCannotBeInferredFromAnAuditObligation() {
    facts.businessErrors.put(ISSUE_CREATE, Set.of(ERROR_CODE_QUOTA_EXCEEDED));
    denied(ISSUE_CREATE, ERROR_CODE_QUOTA_EXCEEDED);
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, false);
    var bypass = decide(ISSUE_CREATE);
    assertTrue(bypass.capability().getAllowed());
    assertTrue(bypass.businessBypass());
    assertTrue(bypass.auditRequired());
    facts.integrityErrors.put(ISSUE_CREATE, Set.of(ERROR_CODE_VERSION_CONFLICT));
    var denied = decide(ISSUE_CREATE);
    assertFalse(denied.capability().getAllowed());
    assertFalse(denied.businessBypass());
    assertTrue(denied.auditRequired());
  }

  @Test
  void financialCleanupRequiresTenantAdministrationAndForeignTenantIsRejectedBeforeLookup() {
    facts.owner = false;
    facts.tenantAdmin = false;
    facts.projectRole = ProjectRole.ADMIN;
    allowed(PROJECT_DELETE);
    facts.extraEntitlementReasons = Set.of(RESTRICTION_CODE_OVER_QUOTA);
    denied(PROJECT_DELETE, ERROR_CODE_READ_ONLY);
    facts.tenantAdmin = true;
    allowed(PROJECT_DELETE);
    int before = facts.reads.get();
    target = new Target(UUID.randomUUID(), PROJECT, RESOURCE, null);
    denied(ISSUE_READ, ERROR_CODE_TENANT_MISMATCH);
    assertEquals(before, facts.reads.get());
  }

  @Test
  void administrativeCollectionQueryRequiresExplicitSubsetOfCurrentSelectedGrant() {
    admin(AdminGrant.LevelValue.LEVEL_READ, false, false);
    claims.clearTenantId();
    target = new Target(null, null, null, null, Set.of(TENANT));
    allowed(ADMIN_QUERY);
    target = new Target(null, null, null, null, Set.of(TENANT, UUID.randomUUID()));
    denied(ADMIN_QUERY, ERROR_CODE_ADMIN_SCOPE_DENIED);
    target = new Target(null, null, null, null);
    denied(ADMIN_QUERY, ERROR_CODE_ADMIN_SCOPE_DENIED);
    target = new Target(null, null, null, null, Set.of(TENANT));
    denied(ADMIN_AUDIT_READ, ERROR_CODE_ADMIN_SCOPE_DENIED);
  }

  @Test
  void administrativeBusinessExceptionRetainsIndependentCausesForAuditAndLeavesStateIntact() {
    admin(AdminGrant.LevelValue.LEVEL_MODIFY, false, false);
    facts.restrictions =
        Set.of(
            RESTRICTION_CODE_PLATFORM_BLOCK,
            RESTRICTION_CODE_OWNER_PAUSE,
            RESTRICTION_CODE_PAYMENT_DISPUTE);
    facts.extraEntitlementReasons = Set.of(RESTRICTION_CODE_PAYMENT_EXPIRED);
    facts.flags.put(Flag.PAID_TENANT_TRANSFER, false);
    var result = decide(TENANT_TRANSFER_OWNERSHIP);
    assertTrue(result.capability().getAllowed());
    assertTrue(result.businessBypass());
    assertTrue(result.auditRequired());
    assertEquals(4, result.bypassedRestrictions().size());
    assertEquals(3, facts.restrictions.size());
    facts.globalAdmin = false;
    claims.setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT);
    denied(TENANT_TRANSFER_OWNERSHIP, ERROR_CODE_READ_ONLY);
  }

  @Test
  void financialTransferReasonsAreExplicitEvenIfPaidThroughIsStillInFuture() {
    for (var reason : List.of(RESTRICTION_CODE_PAYMENT_EXPIRED, RESTRICTION_CODE_OVER_QUOTA)) {
      facts.extraEntitlementReasons = Set.of(reason);
      var decision = decide(TENANT_TRANSFER_OWNERSHIP).capability();
      assertFalse(decision.getAllowed());
      assertTrue(decision.getRestrictionsList().contains(reason));
    }
  }

  @Test
  void globalWebCompanyAccessNeedsTenantBindingExceptExplicitInviteAndLeaveProtocols() {
    claims.clearTenantId().setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
    denied(ISSUE_READ, ERROR_CODE_TENANT_REQUIRED);
    facts.owner = false;
    facts.tenantAdmin = false;
    allowed(INVITATION_ACCEPT);
    allowed(MEMBERSHIP_LEAVE);
    facts.globalAdmin = true;
    facts.webMfa = true;
    allowed(ISSUE_READ);
  }
}

/**
 * Test-only stand-in for current primary authorities. Mutable facts are re-read each subscription.
 */
final class CapabilityTestFacts {
  UUID actor;
  final UUID tenant;
  final Clock clock;
  final Instant baseInstant;
  final String session, credential;
  boolean active = true, credentialActive = true;
  String actorGeneration = "1";
  Set<String> currentScopes = Set.of("read", "write");
  boolean globalAdmin,
      webMfa = true,
      owner = true,
      tenantAdmin = true,
      entitlementAvailable = true,
      missingIntegrity,
      controlNotification;
  Tenant.LifecycleValue lifecycle = Tenant.LifecycleValue.LIFECYCLE_ACTIVE;
  Membership.StateValue membershipState = Membership.StateValue.STATE_ACTIVE;
  String membershipGeneration = "2";
  long entitlementVersion = 1;
  ProjectRole projectRole = ProjectRole.ADMIN;
  PlanKind plan = PlanKind.PAID;
  Channel channel = Channel.DIRECT;
  PaymentChannel payment = PaymentChannel.WIRE;
  Instant paidThrough;
  AdminGrant grant;
  Accepted accepted;
  Worker worker;
  final AtomicInteger reads = new AtomicInteger();
  final Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
  final Map<Action, Set<ErrorCode>> businessErrors = new EnumMap<>(Action.class);
  final Map<Action, Set<ErrorCode>> integrityErrors = new EnumMap<>(Action.class);
  Set<RestrictionCode> restrictions = Set.of(), extraEntitlementReasons = Set.of();

  CapabilityTestFacts(UUID actor, UUID tenant, Clock clock, String session, String credential) {
    this.actor = actor;
    this.tenant = tenant;
    this.clock = clock;
    this.baseInstant = clock.instant();
    this.session = session;
    this.credential = credential;
    paidThrough = clock.instant().plusSeconds(2678400);
    for (var flag : Flag.values()) flags.put(flag, true);
    flags.put(Flag.FREE_MCP, false);
    flags.put(Flag.FREE_PERSONAL_API_TOKENS, false);
  }

  Mono<CapabilityFacts> current(VerifiedTenantContext v, Target target, Set<Action> actions) {
    reads.incrementAndGet();
    var integrity = new EnumMap<Action, Set<ErrorCode>>(Action.class);
    var business = new EnumMap<Action, Set<ErrorCode>>(Action.class);
    for (var a : actions) business.put(a, businessErrors.getOrDefault(a, Set.of()));
    if (!missingIntegrity)
      for (var a : actions) integrity.put(a, integrityErrors.getOrDefault(a, Set.of()));
    var entitlement =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(Long.toString(entitlementVersion))
            .setPlanVersionId("30000000-0000-0000-0000-000000000001")
            .setValidFrom(baseInstant.minusSeconds(100).toString())
            .setIssuedAt(baseInstant.toString())
            .addAllRestrictions(extraEntitlementReasons);
    if (plan == PlanKind.PAID)
      entitlement
          .setCoveragePeriodId("40000000-0000-0000-0000-000000000001")
          .setPaidThrough(paidThrough.toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      entitlement.addLimits(QuotaLimit.newBuilder().setKind(kind).setLimit("50"));
    var member =
        MembershipSnapshot.newBuilder()
            .setTenantId(tenant.toString())
            .setUserId(actor == null ? "" : actor.toString())
            .setState(membershipState)
            .setRole(
                tenantAdmin
                    ? Membership.RoleValue.ROLE_TENANT_ADMIN
                    : Membership.RoleValue.ROLE_MEMBER)
            .setIsOwner(owner)
            .setVersion("1")
            .setTenantMembershipVersion(membershipGeneration)
            .build();
    return Mono.just(
        new CapabilityFacts(
            actor,
            target,
            v.requestId(),
            clock.instant().plusSeconds(20),
            new Identity(
                active,
                globalAdmin,
                actorGeneration,
                credentialActive,
                credential,
                session,
                webMfa,
                actor == null ? Set.of("worker:recover") : currentScopes),
            member,
            projectRole,
            lifecycle,
            true,
            true,
            grant,
            entitlementAvailable ? entitlement.build() : null,
            plan,
            restrictions,
            flags,
            "1",
            channel,
            payment,
            controlNotification,
            integrity,
            business,
            accepted,
            worker));
  }
}
