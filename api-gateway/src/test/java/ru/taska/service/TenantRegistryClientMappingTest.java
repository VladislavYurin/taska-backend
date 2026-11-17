package ru.taska.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import ru.taska.api.b2b.v1.CallerMembership;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantPage;
import ru.taska.domain.dto.TenantCallerMembershipDto;
import ru.taska.domain.dto.TenantMembershipRole;
import ru.taska.domain.dto.TenantMembershipState;

/** TAS-338: the caller's membership crosses the gateway unchanged or fails closed. */
class TenantRegistryClientMappingTest {
  static Tenant.Builder tenant(String id) {
    return Tenant.newBuilder()
        .setId(id)
        .setName("Company")
        .setOwnerId("00000000-0000-0000-0000-000000000101")
        .setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
        .setCreatedAt("2026-11-03T12:00:00Z")
        .setVersion("2")
        .setOwnerVersion("1");
  }

  static CallerMembership.Builder membership() {
    return CallerMembership.newBuilder()
        .setRole(Membership.RoleValue.ROLE_TENANT_ADMIN)
        .setState(Membership.StateValue.STATE_ACTIVE)
        .setIsOwner(true)
        .setVersion("4");
  }

  @Test
  void eachItemKeepsItsOwnMembershipAndAbsentMembershipStaysAbsent() {
    var page =
        TenantPage.newBuilder()
            .addItems(tenant("00000000-0000-0000-0000-000000000001").setMembership(membership()))
            .addItems(
                tenant("00000000-0000-0000-0000-000000000002")
                    .setMembership(
                        membership()
                            .setRole(Membership.RoleValue.ROLE_MEMBER)
                            .setIsOwner(false)
                            .setVersion("19")))
            .addItems(tenant("00000000-0000-0000-0000-000000000003"))
            .build();

    var rest = TenantRegistryClient.toRestPage(page, 50);

    assertEquals(
        new TenantCallerMembershipDto()
            .role(TenantMembershipRole.TENANT_ADMIN)
            .state(TenantMembershipState.ACTIVE)
            .isOwner(true)
            .version("4"),
        rest.getItems().get(0).getMembership());
    assertEquals(
        new TenantCallerMembershipDto()
            .role(TenantMembershipRole.MEMBER)
            .state(TenantMembershipState.ACTIVE)
            .isOwner(false)
            .version("19"),
        rest.getItems().get(1).getMembership());
    assertNull(rest.getItems().get(2).getMembership());
  }

  @Test
  void malformedMembershipIsRejectedAsInvalidUpstreamResponse() {
    for (var invalid :
        new CallerMembership[] {
          membership().setVersion("0").build(),
          membership().setVersion("").build(),
          membership().setRole(Membership.RoleValue.ROLE_UNSPECIFIED).build(),
          membership().setState(Membership.StateValue.STATE_UNSPECIFIED).build()
        }) {
      var page =
          TenantPage.newBuilder()
              .addItems(tenant("00000000-0000-0000-0000-000000000001").setMembership(invalid))
              .build();
      var error =
          assertThrows(
              StatusRuntimeException.class, () -> TenantRegistryClient.toRestPage(page, 50));
      assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode());
    }
  }
}
