package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.taska.billing.SubscriptionIntegrationTest.A;
import static ru.taska.billing.SubscriptionIntegrationTest.OWNER_A;
import static ru.taska.billing.SubscriptionIntegrationTest.WAIT;
import static ru.taska.billing.SubscriptionIntegrationTest.operator;
import static ru.taska.billing.SubscriptionIntegrationTest.sql;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.OwnershipTransferReceipt;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RegistryTestContext;

/** Реальные финансовые таблицы, trigger fence и отзыв сохранённого согласия. */
@Testcontainers
class OwnershipTransferBillingIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final AutopayIntegrationTest fixture = new AutopayIntegrationTest();
  final UUID operation = UUID.randomUUID();
  final AtomicReference<OwnershipTransferReceipt> root = new AtomicReference<>();
  OwnershipTransferBilling transfers;

  @BeforeAll
  static void setup() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void seed() {
    sql("TRUNCATE taska.ownership_transfer_fences");
    fixture.reset();
    var f = fixture.fixture.f;
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    f.time.set(now);
    UUID proof = f.proof(A, now.minusSeconds(10), now.plusSeconds(86400));
    f.grant(A, proof, "owned-paid").block(WAIT);
    root.set(
        OwnershipTransferReceipt.newBuilder()
            .setTenantId(A.toString())
            .setOperationId(operation.toString())
            .setState(OwnershipTransferReceipt.State.PENDING)
            .setOldOwnerId(OWNER_A.toString())
            .setOldOwnerVersion("1")
            .setNewOwnerId(UUID.randomUUID().toString())
            .setExpectedTenantVersion("1")
            .setExpiresAt(now.plusSeconds(30).toString())
            .build());
    transfers =
        new OwnershipTransferBilling(
            f.transactions,
            f.store,
            new EntitlementStore(f.transactions, f.readers::allowed, f.clock),
            (t, id) -> Mono.just(root.get()),
            f.clock);
  }

  OwnershipTransferRequest request() {
    return OwnershipTransferRequest.newBuilder()
        .setTenantId(A.toString())
        .setOperationId(operation.toString())
        .build();
  }

  <T> T call(String action, String scope, Mono<T> work) {
    return RegistryTestContext.withClaims(
            TrustedTenantContext.newBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                .setTenantId(A.toString())
                .setIssuerService("tenant-service")
                .setServiceIdentity("tenant-service")
                .setServicePurpose("billing")
                .setActorGeneration("1")
                .setAudienceService("billing-service")
                .setAction(action)
                .addScopes("write")
                .addScopes(scope)
                .build(),
            work)
        .block(WAIT);
  }

  OwnershipTransferFence prepare() {
    return call("prepareOwnershipTransfer", "ownership:prepare", transfers.prepare(request()));
  }

  OwnershipTransferFence finish() {
    return call("finishOwnershipTransfer", "ownership:finish", transfers.finish(request()));
  }

  void committed(OwnershipTransferFence fence) {
    root.set(
        root.get().toBuilder()
            .setState(OwnershipTransferReceipt.State.COMMITTED)
            .setCommittedAt(Instant.now().toString())
            .setBillingFenceUntil(fence.getValidUntil())
            .build());
  }

  String mandateState() {
    return operator
        .sql("SELECT state FROM taska.autopay_mandates WHERE tenant_id=:t")
        .bind("t", A)
        .map((r, m) -> r.get("state", String.class))
        .one()
        .block(WAIT);
  }

  @Test
  void fenceStopsCoverageMutationUntilCommittedOutcomeAndRevokesOnce() {
    fixture.consent(true, "owned-consent", fixture.version());
    var fence = prepare();
    assertEquals(fence, prepare());
    assertThrows(
        RuntimeException.class,
        () -> sql("UPDATE taska.subscriptions SET version=version+1 WHERE tenant_id='" + A + "'"));
    assertThrows(
        RuntimeException.class,
        () ->
            sql(
                "UPDATE taska.subscription_periods SET status='REVOKED' WHERE tenant_id='"
                    + A
                    + "'"));
    committed(fence);
    finish();
    assertEquals("REVOKED", mandateState());
    long version = fixture.version();
    finish();
    assertEquals(version, fixture.version());
    sql("UPDATE taska.subscriptions SET version=version+1 WHERE tenant_id='" + A + "'");
  }

  @Test
  void cancellationPreservesAutopayAndHistory() {
    fixture.consent(true, "owned-consent", fixture.version());
    prepare();
    long before = fixture.version();
    root.set(root.get().toBuilder().setState(OwnershipTransferReceipt.State.CANCELLED).build());
    finish();
    assertEquals("ACTIVE", mandateState());
    assertEquals(before, fixture.version());
  }

  @Test
  void unknownOrPendingOutcomeCannotRevokeConsent() {
    fixture.consent(true, "owned-consent", fixture.version());
    prepare();
    assertThrows(DomainException.class, this::finish);
    assertEquals("ACTIVE", mandateState());
    root.set(
        root.get().toBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .setState(OwnershipTransferReceipt.State.COMMITTED)
            .build());
    assertThrows(DomainException.class, this::finish);
    assertEquals("ACTIVE", mandateState());
  }

  @Test
  void expiredCoverageCannotAdmitOrdinaryTransfer() {
    fixture.fixture.f.time.set(Instant.now().plusSeconds(172800));
    assertThrows(DomainException.class, this::prepare);
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) AS n FROM taska.ownership_transfer_fences")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }
}
