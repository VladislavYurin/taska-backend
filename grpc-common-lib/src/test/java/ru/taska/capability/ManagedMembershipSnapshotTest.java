package ru.taska.capability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;

class ManagedMembershipSnapshotTest {
  final UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();

  MembershipValidationBatch snapshot() {
    return MembershipValidationBatch.newBuilder()
        .setTenantId(tenant.toString())
        .setRestrictionVersion("3")
        .addIndependentRestrictions(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE)
        .addItems(
            MembershipValidation.newBuilder()
                .setUserId(actor.toString())
                .setFound(true)
                .setMembership(
                    MembershipSnapshot.newBuilder()
                        .setTenantId(tenant.toString())
                        .setUserId(actor.toString())
                        .setRestrictionVersion("3")
                        .addIndependentRestrictions(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE)))
        .build();
  }

  @Test
  void acceptsConsistentCurrentRootReasons() {
    var value = snapshot();
    assertSame(
        value, ManagedDomainReads.membershipSnapshot(tenant, List.of(actor.toString()), value));
  }

  @Test
  void olderOrInconsistentRootCannotErasePause() {
    var current = snapshot();
    var rows =
        List.of(
            current.toBuilder().clearRestrictionVersion().build(),
            current.toBuilder().setRestrictionVersion("9223372036854775808").build(),
            current.toBuilder().clearIndependentRestrictions().build(),
            current.toBuilder().addIndependentRestrictionsValue(999).build(),
            current.toBuilder()
                .setItems(
                    0,
                    current.getItems(0).toBuilder()
                        .setMembership(
                            current.getItems(0).getMembership().toBuilder()
                                .setRestrictionVersion("2")))
                .build(),
            current.toBuilder().setTenantId(UUID.randomUUID().toString()).build());
    for (var value : rows)
      assertEquals(
          DomainStatus.UNAVAILABLE,
          assertThrows(
                  DomainException.class,
                  () ->
                      ManagedDomainReads.membershipSnapshot(
                          tenant, List.of(actor.toString()), value))
              .getStatus());
  }
}
