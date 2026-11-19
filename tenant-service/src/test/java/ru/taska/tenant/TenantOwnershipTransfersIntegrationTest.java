package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static ru.taska.tenant.TenantMembershipIntegrationTest.A;
import static ru.taska.tenant.TenantMembershipIntegrationTest.OWNER;
import static ru.taska.tenant.TenantMembershipIntegrationTest.SHARED;
import static ru.taska.tenant.TenantMembershipIntegrationTest.WAIT;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.PlatformConfigurationSnapshot;
import ru.taska.api.b2b.v1.PlatformFlag;
import ru.taska.api.b2b.v1.RpcActorFacts;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TransferOwnershipRequest;
import ru.taska.api.b2b.v1.TransferTenantOwnershipRpcRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.capability.CapabilityFacts;
import ru.taska.configuration.PlatformConfigurationSnapshotCache.Lease;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.OwnershipTransferClient;
import ru.taska.security.tenant.RegistryTestContext;

/**
 * Реальный PostgreSQL, tenant RLS и общий evaluator; внешние primary ответы управляются фикстурой.
 */
@Testcontainers
class TenantOwnershipTransfersIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = TenantMembershipIntegrationTest.pg;
  final TenantMembershipIntegrationTest fixture = new TenantMembershipIntegrationTest();
  TenantOwnershipTransfers transfers;
  OwnershipTransferAuthority authority;
  OwnershipTransferClient billing;
  TrustedTenantContext owner;
  final AtomicBoolean loseFinish = new AtomicBoolean();

  @BeforeAll
  static void setup() throws Exception {
    TenantMembershipIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    TenantMembershipIntegrationTest.stop();
  }

  @BeforeEach
  void seed() {
    TenantMembershipIntegrationTest.operator
        .sql(
            "TRUNCATE"
                + " taska.tenant_ownership_audit,taska.tenant_ownership_transfers,taska.tenant_ownership_dispatch")
        .then()
        .block(WAIT);
    fixture.seed();
    loseFinish.set(false);
    owner =
        fixture.owner.toBuilder().setAction("transferTenantOwnership").addScopes("write").build();
    authority = spy(new OwnershipTransferAuthority(null, null, Clock.systemUTC()));
    doReturn(Mono.just(frame())).when(authority).current(any());
    billing = mock(OwnershipTransferClient.class);
    when(billing.prepare(any(), any()))
        .thenAnswer(
            invocation -> {
              UUID operation = invocation.getArgument(1);
              return Mono.just(
                  OwnershipTransferFence.newBuilder()
                      .setTenantId(A.toString())
                      .setOperationId(operation.toString())
                      .setOldOwnerId(OWNER.toString())
                      .setOldOwnerVersion("1")
                      .setEntitlementVersion("1")
                      .setValidUntil(Instant.now().plusSeconds(20).toString())
                      .build());
            });
    when(billing.finish(any(), any()))
        .thenAnswer(
            invocation ->
                loseFinish.get()
                    ? Mono.error(new IllegalStateException("Потерян ответ Billing"))
                    : Mono.just(OwnershipTransferFence.getDefaultInstance()));
    transfers =
        new TenantOwnershipTransfers(
            TenantMembershipIntegrationTest.transactions, authority, billing, Clock.systemUTC());
  }

  OwnershipTransferAuthority.Frame frame() {
    Instant now = Instant.now();
    var flags = PlatformConfigurationSnapshot.newBuilder().setVersion("1");
    Arrays.stream(CapabilityFacts.Flag.values())
        .forEach(
            f -> flags.addItems(PlatformFlag.newBuilder().setKey(f.name()).setBooleanValue(true)));
    return new OwnershipTransferAuthority.Frame(
        RpcActorFacts.newBuilder()
            .setActorUserId(OWNER.toString())
            .setActorGeneration("3")
            .setSessionId(owner.getSessionId())
            .addScopes("read")
            .addScopes("write")
            .setExpiresAt(now.plusSeconds(60).toString())
            .build(),
        TenantCapabilitiesIntegrationTest.entitlement(A, "1", false),
        CapabilityFacts.PlanKind.PAID,
        new Lease(flags.build(), now, now.plusSeconds(30)),
        now.plusSeconds(30));
  }

  TransferTenantOwnershipRpcRequest request(UUID target, String key, long expected) {
    return TransferTenantOwnershipRpcRequest.newBuilder()
        .setTenantId(A.toString())
        .setPayload(
            TransferOwnershipRequest.newBuilder()
                .setNewOwnerUserId(target.toString())
                .setConfirmed(true))
        .setMutation(
            MutationOptions.newBuilder()
                .setIdempotencyKey(key)
                .setExpectedVersion(Long.toString(expected)))
        .build();
  }

  Tenant run(TransferTenantOwnershipRpcRequest q) {
    return RegistryTestContext.withClaims(owner, transfers.transfer(q)).block(WAIT);
  }

  long count(String table) {
    return TenantMembershipIntegrationTest.operator
        .sql("SELECT count(*) AS n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void ownerAndRolesChangeExactlyOnce() {
    var q = request(SHARED, UUID.randomUUID().toString(), 1);
    Tenant result = run(q);
    assertEquals(SHARED.toString(), result.getOwnerId());
    assertEquals("2", result.getVersion());
    assertEquals(result, run(q));
    assertEquals(1, count("tenant_ownership_audit"));
    assertEquals(
        2L,
        TenantMembershipIntegrationTest.operator
            .sql(
                "SELECT count(*) AS n FROM taska.tenant_memberships WHERE tenant_id=:t AND user_id"
                    + " IN (:old,:new) AND role='TENANT_ADMIN' AND status='ACTIVE'")
            .bind("t", A)
            .bind("old", OWNER)
            .bind("new", SHARED)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertThrows(
        DomainException.class,
        () -> run(request(TenantMembershipIntegrationTest.ADMIN, UUID.randomUUID().toString(), 2)));
  }

  @Test
  void lostFinishReplyPreservesReceiptAndReplay() {
    var q = request(SHARED, UUID.randomUUID().toString(), 1);
    loseFinish.set(true);
    assertThrows(IllegalStateException.class, () -> run(q));
    assertEquals(1, count("tenant_ownership_audit"));
    loseFinish.set(false);
    assertEquals(SHARED.toString(), run(q).getOwnerId());
    assertEquals(1, count("tenant_ownership_audit"));
  }

  @Test
  void inactiveForeignStaleAndConflictingRequestsHaveNoEffect() {
    assertThrows(
        DomainException.class,
        () ->
            run(
                request(
                    TenantMembershipIntegrationTest.SUSPENDED, UUID.randomUUID().toString(), 1)));
    assertThrows(
        DomainException.class,
        () ->
            run(request(TenantMembershipIntegrationTest.FOREIGN, UUID.randomUUID().toString(), 1)));
    assertThrows(
        DomainException.class, () -> run(request(SHARED, UUID.randomUUID().toString(), 9)));
    assertEquals(0, count("tenant_ownership_audit"));
    String key = UUID.randomUUID().toString();
    run(request(SHARED, key, 1));
    assertThrows(
        DomainException.class, () -> run(request(TenantMembershipIntegrationTest.ADMIN, key, 1)));
    assertEquals(1, count("tenant_ownership_audit"));
  }

  @Test
  void targetBecomingInactiveDuringBillingDoesNotChangeOwner() {
    doAnswer(
            invocation -> {
              return TenantMembershipIntegrationTest.operator
                  .sql(
                      "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1"
                          + " WHERE tenant_id=:t AND user_id=:u")
                  .bind("t", A)
                  .bind("u", SHARED)
                  .then()
                  .thenReturn(
                      OwnershipTransferFence.newBuilder()
                          .setTenantId(A.toString())
                          .setOperationId(invocation.<UUID>getArgument(1).toString())
                          .setOldOwnerId(OWNER.toString())
                          .setOldOwnerVersion("1")
                          .setEntitlementVersion("1")
                          .setValidUntil(Instant.now().plusSeconds(20).toString())
                          .build());
            })
        .when(billing)
        .prepare(any(), any());
    assertThrows(
        DomainException.class, () -> run(request(SHARED, UUID.randomUUID().toString(), 1)));
    assertEquals(0, count("tenant_ownership_audit"));
  }

  @Test
  void pauseIsAllowedButPlatformBlockAndDisputeDeny() {
    for (String kind : java.util.List.of("PLATFORM_BLOCK", "FINANCIAL_DISPUTE")) {
      TenantMembershipIntegrationTest.operator
          .sql(
              "INSERT INTO"
                  + " taska.tenant_restrictions(tenant_id,kind,source_id,active,version,reason_code,created_at)"
                  + " VALUES(:t,:kind,:source,true,1,'OWNED',now())")
          .bind("t", A)
          .bind("kind", kind)
          .bind("source", UUID.randomUUID())
          .then()
          .block(WAIT);
      assertThrows(
          DomainException.class, () -> run(request(SHARED, UUID.randomUUID().toString(), 1)));
      TenantMembershipIntegrationTest.operator
          .sql("DELETE FROM taska.tenant_restrictions WHERE tenant_id=:t")
          .bind("t", A)
          .then()
          .block(WAIT);
    }
    assertEquals(0, count("tenant_ownership_audit"));
    TenantMembershipIntegrationTest.operator
        .sql(
            "INSERT INTO"
                + " taska.tenant_restrictions(tenant_id,kind,source_id,active,version,reason_code,created_at)"
                + " VALUES(:t,'OWNER_PAUSE',:source,true,1,'OWNED',now())")
        .bind("t", A)
        .bind("source", UUID.randomUUID())
        .then()
        .block(WAIT);
    assertEquals(
        SHARED.toString(), run(request(SHARED, UUID.randomUUID().toString(), 1)).getOwnerId());
  }

  @Test
  void auditFailureRollsBackOwnerRolesAndOutbox() {
    TenantMembershipIntegrationTest.operator
        .sql(
            "CREATE FUNCTION taska.owned_transfer_fail() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'OWNED_AUDIT_FAILURE'; END $$")
        .then()
        .block(WAIT);
    TenantMembershipIntegrationTest.operator
        .sql(
            "CREATE TRIGGER owned_transfer_fail BEFORE INSERT ON taska.tenant_ownership_audit FOR"
                + " EACH ROW EXECUTE FUNCTION taska.owned_transfer_fail()")
        .then()
        .block(WAIT);
    try {
      assertThrows(
          RuntimeException.class, () -> run(request(SHARED, UUID.randomUUID().toString(), 1)));
      assertEquals(
          OWNER,
          TenantMembershipIntegrationTest.operator
              .sql("SELECT owner_id FROM taska.tenants WHERE id=:t")
              .bind("t", A)
              .map((r, m) -> r.get("owner_id", UUID.class))
              .one()
              .block(WAIT));
      assertEquals(0, count("tenant_ownership_audit"));
      assertEquals(
          "MEMBER",
          TenantMembershipIntegrationTest.operator
              .sql("SELECT role FROM taska.tenant_memberships WHERE tenant_id=:t AND user_id=:u")
              .bind("t", A)
              .bind("u", SHARED)
              .map((r, m) -> r.get("role", String.class))
              .one()
              .block(WAIT));
    } finally {
      TenantMembershipIntegrationTest.operator
          .sql("DROP TRIGGER owned_transfer_fail ON taska.tenant_ownership_audit")
          .then()
          .block(WAIT);
      TenantMembershipIntegrationTest.operator
          .sql("DROP FUNCTION taska.owned_transfer_fail()")
          .then()
          .block(WAIT);
    }
  }
}
