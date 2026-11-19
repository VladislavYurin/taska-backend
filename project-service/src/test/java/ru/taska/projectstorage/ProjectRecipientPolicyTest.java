package ru.taska.projectstorage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MembershipValidationBatch;
import ru.taska.api.b2b.v1.PlatformConfigurationSnapshot;
import ru.taska.api.b2b.v1.PlatformFlag;
import ru.taska.api.b2b.v1.QuotaLimit;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.exception.DomainException;

class ProjectRecipientPolicyTest {
  private final UUID tenant = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-11-19T00:00:00Z");

  private Entitlement.Builder entitlement() {
    var e =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setPlanVersionId(UUID.randomUUID().toString())
            .setVersion("1")
            .setIssuedAt(now.toString())
            .setValidFrom(now.minusSeconds(60).toString());
    for (var kind :
        new QuotaLimit.KindValue[] {
          QuotaLimit.KindValue.KIND_PROJECT,
          QuotaLimit.KindValue.KIND_SEAT,
          QuotaLimit.KindValue.KIND_STORAGE_BYTES,
          QuotaLimit.KindValue.KIND_TASK_LIFETIME
        }) e.addLimits(QuotaLimit.newBuilder().setKind(kind).setUnlimited(true));
    return e;
  }

  private PlatformConfigurationSnapshotCache.Lease flags(boolean read) {
    return new PlatformConfigurationSnapshotCache.Lease(
        PlatformConfigurationSnapshot.newBuilder()
            .addItems(
                PlatformFlag.newBuilder().setKey("READ_WHILE_RESTRICTED").setBooleanValue(read))
            .build(),
        now,
        now.plusSeconds(30));
  }

  @Test
  void restoredMembershipDoesNotReviveOldProjectGrant() {
    var member =
        Membership.newBuilder()
            .setState(Membership.StateValue.STATE_ACTIVE)
            .setRole(Membership.RoleValue.ROLE_MEMBER)
            .setVersion("2")
            .build();
    assertFalse(ProjectRecipientRpc.membershipAllowed(member, 1L));
    assertFalse(ProjectRecipientRpc.membershipAllowed(member, null));
    assertTrue(ProjectRecipientRpc.membershipAllowed(member, 2L));
    assertFalse(
        ProjectRecipientRpc.membershipAllowed(
            member.toBuilder().setState(Membership.StateValue.STATE_SUSPENDED).build(), 2L));
    assertTrue(
        ProjectRecipientRpc.membershipAllowed(member.toBuilder().setIsOwner(true).build(), null));
  }

  @Test
  void foreignOrMalformedBillingResponseFailsClosedEvenWhenRestrictedReadIsOn() {
    var members = MembershipValidationBatch.getDefaultInstance();
    assertThrows(
        DomainException.class,
        () ->
            ProjectRecipientRpc.contentAllowed(
                tenant,
                members,
                entitlement().setTenantId(UUID.randomUUID().toString()).build(),
                flags(true),
                now));
    assertThrows(
        DomainException.class,
        () ->
            ProjectRecipientRpc.contentAllowed(
                tenant, members, entitlement().setVersion("0").build(), flags(true), now));
  }

  @Test
  void expiryAndIndependentPauseUseRestrictedReadFlag() {
    var members = MembershipValidationBatch.getDefaultInstance();
    var expired = entitlement().setPaidThrough(now.minusSeconds(1).toString()).build();
    assertFalse(ProjectRecipientRpc.contentAllowed(tenant, members, expired, flags(false), now));
    assertTrue(ProjectRecipientRpc.contentAllowed(tenant, members, expired, flags(true), now));
    var paused =
        members.toBuilder()
            .addIndependentRestrictions(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE)
            .build();
    assertFalse(
        ProjectRecipientRpc.contentAllowed(
            tenant, paused, entitlement().build(), flags(false), now));
    assertTrue(
        ProjectRecipientRpc.contentAllowed(
            tenant, paused, entitlement().build(), flags(true), now));
    assertTrue(
        ProjectRecipientRpc.contentAllowed(
            tenant, members, entitlement().build(), flags(false), now));
  }
}
