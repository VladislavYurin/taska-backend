package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.ingress.*;
import ru.taska.billing.payment.*;
import ru.taska.billing.reconciliation.*;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class PaymentReconciliationIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final ProviderInboxIntegrationTest f = new ProviderInboxIntegrationTest();
  PaymentAttemptStore attempts;
  BillingOperationStore operations;
  PaymentReconciliationStore jobs;
  PaymentReconciliation service;
  PaymentAttemptStore.Attempt attempt;
  PaymentProvider provider;

  @BeforeAll
  static void setup() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    f.reset();
    provider = spy(f.fake);
    attempts = new PaymentAttemptStore(f.base.f.f.transactions, f.base.f.f.clock);
    operations = new BillingOperationStore(f.base.f.f.transactions, f.base.f.f.clock);
    jobs = new PaymentReconciliationStore(db, f.base.f.f.clock, 3);
    var op =
        as(
            operations.accept(
                new BillingOperationStore.Request(
                    A, "PAYMENT", "reconcile", new byte[32], "PSP", null, null)));
    attempt =
        as(
            attempts.accept(
                new PaymentAttemptStore.Request(
                    A,
                    f.base.invoice,
                    f.identity.provider(),
                    "reconcile",
                    f.identity.account(),
                    f.identity.environment(),
                    "{}"),
                op.id(),
                new PaymentProvider.Invoice(
                    f.base.invoice, new PaymentProvider.Money(2000, "EUR"))));
    attempt = as(attempts.claim(attempt));
    attempt =
        as(
            attempts.mark(
                attempt, PaymentAttemptStore.State.PENDING, f.payment.providerPaymentId()));
    restart();
    advance(65);
  }

  void restart() {
    service =
        new PaymentReconciliation(
            jobs,
            attempts,
            operations,
            new PaymentProviderRegistry(
                Map.of(
                    new PaymentProviderRegistry.Identity(
                        f.identity.provider(), f.identity.merchant()),
                    new PaymentProviderRegistry.Registration(provider, Set.of("v1")))),
            f.registry,
            f.store,
            (t, e) ->
                RegistryTestContext.withClaims(
                        f.worker(t, "applyVerifiedPayment", "billing-service", "ledger:write"),
                        f.base.settlement.apply(t, e))
                    .then());
  }

  @AfterEach
  void clean() {
    f.clean();
  }

  void advance(long seconds) {
    f.base.f.f.time.updateAndGet(t -> t.plusSeconds(seconds));
  }

  <T> T as(Mono<T> m) {
    return f.base.f.as("createCheckout", m).block(WAIT);
  }

  PaymentReconciliationStore.Job claim() {
    return jobs.claim(1).block(WAIT).getFirst();
  }

  Mono<PaymentReconciliationStore.Status> run(PaymentReconciliationStore.Job j) {
    return RegistryTestContext.withClaims(
        f.worker(j.tenantId(), "reconcilePaymentAttempt", "billing-service", "payment:reconcile"),
        service.process(j.tenantId(), j.attemptId(), j.token()));
  }

  String process() {
    return run(claim()).block(WAIT).state();
  }

  long count(String table) {
    return f.count(table);
  }

  Mono<Void> replay(UUID tenant) {
    return RegistryTestContext.withClaims(
        f.worker(
            tenant, "replayPaymentReconciliation", "billing-service", "payment:reconcile:replay"),
        jobs.replay(tenant, attempt.id()));
  }

  @Test
  void lostWebhookAndLateCallbacksShareOneLedgerCoverageAndProof() {
    assertEquals("DONE", process());
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(1, count("provider_payment_proofs"));
    var receipt = f.receive(f.webhook).block(WAIT);
    assertEquals("APPLIED", f.process(receipt.id()).block(WAIT).state());
    var again =
        f.fake.confirmPayment(
            f.identity.merchant(),
            f.payment.providerPaymentId(),
            PaymentProvider.State.SUCCEEDED,
            "late-2");
    f.process(f.receive(again).block(WAIT).id()).block(WAIT);
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, as(attempts.find(A, attempt.id())).state());
    verify(provider, never()).createCheckout(any(), any(), any(), any());
  }

  @RepeatedTest(3)
  void twoWorkersAndDuplicateRpcConvergeWithOneEffect() {
    var claimed =
        Flux.range(0, 8)
            .flatMap(i -> jobs.claim(1).subscribeOn(Schedulers.parallel()), 8)
            .flatMapIterable(x -> x)
            .collectList()
            .block(WAIT);
    assertEquals(1, claimed.size());
    Flux.merge(
            run(claimed.getFirst()).onErrorResume(IllegalStateException.class, e -> Mono.empty()),
            run(claimed.getFirst()).onErrorResume(IllegalStateException.class, e -> Mono.empty()))
        .collectList()
        .block(WAIT);
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(0, jobs.summary().block(WAIT).pending());
  }

  @Test
  void lostRootAckRestartsFromDurableProofWithoutProviderAvailability() {
    f.base.loseDelivery.set(true);
    assertEquals("PENDING", process());
    assertEquals(1, count("payment_facts"));
    doReturn(Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .when(provider)
        .fetchPayment(any(), any());
    f.base.loseDelivery.set(false);
    advance(31);
    restart();
    assertEquals("DONE", process());
    assertEquals(1, count("subscription_periods"));
    assertEquals(1, count("payment_facts"));
    verify(provider, times(1)).fetchPayment(any(), any());
  }

  @Test
  void crashLeaseExpiryFencesOldWorkerAndPreservesOriginOnReplay() {
    var old = claim();
    assertTrue(jobs.claim(1).block(WAIT).isEmpty());
    advance(121);
    var fresh = claim();
    assertEquals(old.tenantId(), fresh.tenantId());
    assertEquals(old.correlationId(), fresh.correlationId());
    assertNotEquals(old.token(), fresh.token());
    assertThrows(IllegalStateException.class, () -> run(old).block(WAIT));
    assertNull(jobs.done(old).block(WAIT));
    assertEquals("DONE", run(fresh).block(WAIT).state());
  }

  @Test
  void outageHasBoundedBackoffThenManualAndNoPaidRights() {
    doReturn(Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .when(provider)
        .fetchPayment(any(), any());
    assertEquals("PENDING", process());
    assertTrue(jobs.claim(1).block(WAIT).isEmpty());
    advance(31);
    assertEquals("PENDING", process());
    advance(61);
    assertEquals("MANUAL", process());
    assertEquals(0, count("payment_facts"));
    assertEquals(0, count("subscription_periods"));
    var review = jobs.manual(10).block(WAIT).getFirst();
    assertEquals(A, review.tenantId());
    assertEquals(attempt.operationId(), review.correlationId());
    assertEquals(3, review.attempts());
    assertEquals("DEPENDENCY_UNAVAILABLE", review.reason());
    assertTrue(jobs.summary().block(WAIT).oldestSeconds() >= 157);
    replay(B).block(WAIT);
    assertTrue(jobs.claim(1).block(WAIT).isEmpty());
    replay(A).block(WAIT);
    var replay = claim();
    assertEquals(review.correlationId(), replay.correlationId());
    assertEquals(4, replay.attempts());
    verify(provider, never()).createCheckout(any(), any(), any(), any());
  }

  @Test
  void missingIdAfterExpiredProviderTtlGoesToManualWithoutSubmission() {
    // Independent invoice fixture: crash after committed SUBMITTED and before object-ID commit.
    sql("ALTER TABLE taska.payment_attempts DISABLE TRIGGER immutable_payment_attempt");
    sql("UPDATE taska.payment_attempts SET provider_payment_id=NULL,state='UNKNOWN'");
    sql("ALTER TABLE taska.payment_attempts ENABLE TRIGGER immutable_payment_attempt");
    advance(Duration.ofDays(100).toSeconds());
    assertEquals("MANUAL", process());
    assertEquals("ORIGINAL_OBJECT_ID_REQUIRED", jobs.manual(10).block(WAIT).getFirst().reason());
    verify(provider, never()).fetchPayment(any(), any());
    verify(provider, never()).createCheckout(any(), any(), any(), any());
    assertEquals(0, count("payment_facts"));
  }

  @Test
  void identityAndOccurrenceFailuresNeverBecomeEvidence() {
    doReturn(
            Mono.just(
                new PaymentProvider.Payment(
                    attempt.providerPaymentId(),
                    attempt.invoiceId(),
                    new PaymentProvider.Money(2000, "EUR"),
                    PaymentProvider.State.SUCCEEDED)))
        .when(provider)
        .fetchPayment(any(), any());
    assertEquals("MANUAL", process());
    assertEquals("PAYMENT_OCCURRENCE_REQUIRED", jobs.manual(10).block(WAIT).getFirst().reason());
    assertEquals(0, count("provider_payment_proofs"));
    replay(A).block(WAIT);
    doReturn(
            Mono.just(
                new PaymentProvider.Payment(
                    attempt.providerPaymentId(),
                    UUID.randomUUID(),
                    new PaymentProvider.Money(2000, "EUR"),
                    PaymentProvider.State.SUCCEEDED,
                    NOW)))
        .when(provider)
        .fetchPayment(any(), any());
    assertEquals("MANUAL", process());
    assertEquals("PAYMENT_BINDING_MISMATCH", jobs.manual(10).block(WAIT).getFirst().reason());
    assertEquals(0, count("payment_facts"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = Tenant.LifecycleValue.class,
      names = {"LIFECYCLE_DELETED", "LIFECYCLE_DELETING", "LIFECYCLE_OWNER_CANCELLED"})
  void closedCompanyKeepsMoneyButDoesNotRestoreCompany(Tenant.LifecycleValue lifecycle) {
    f.base.root.set(f.base.root.get().toBuilder().setLifecycle(lifecycle).build());
    assertEquals("DONE", process());
    assertEquals(1, count("payment_facts"));
    assertEquals(0, count("subscription_periods"));
    assertEquals(0, f.base.deliveries.get());
    assertEquals(lifecycle, f.base.root.get().getLifecycle());
  }

  @Test
  void foreignTenantOrUserCannotUseAValidLease() {
    var j = claim();
    assertThrows(
        Exception.class,
        () ->
            RegistryTestContext.withClaims(
                    f.worker(B, "reconcilePaymentAttempt", "billing-service", "payment:reconcile"),
                    service.process(A, attempt.id(), j.token()))
                .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            f.base
                .f
                .as("reconcilePaymentAttempt", service.process(A, attempt.id(), j.token()))
                .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            RegistryTestContext.withClaims(
                    f.worker(B, "reconcilePaymentAttempt", "billing-service", "payment:reconcile"),
                    service.process(B, attempt.id(), j.token()))
                .block(WAIT));
    assertEquals(0, count("payment_facts"));
    assertEquals("DONE", run(j).block(WAIT).state());
  }

  @Test
  void pumpUsesBoundedClaimsAndPublishesManualAgeWithoutHighCardinalityLabels() {
    doReturn(Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .when(provider)
        .fetchPayment(any(), any());
    var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var pump =
        new PaymentReconciliationPump(
            jobs,
            j ->
                run(j)
                    .map(
                        s ->
                            PaymentReconciliationResult.newBuilder()
                                .setState(s.state())
                                .setReasonCode(s.reason())
                                .build()),
            metrics,
            1);
    pump.process().block(WAIT);
    assertEquals(1, metrics.get("taska.billing.reconciliation.pending").gauge().value());
    advance(31);
    pump.process().block(WAIT);
    advance(61);
    pump.process().block(WAIT);
    assertEquals(1, metrics.get("taska.billing.reconciliation.manual").gauge().value());
    assertTrue(metrics.get("taska.billing.reconciliation.oldest.seconds").gauge().value() >= 157);
    assertEquals(0, count("payment_facts"));
    assertEquals(3, jobs.manual(1).block(WAIT).getFirst().attempts());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PaymentReconciliationPump(jobs, j -> Mono.empty(), metrics, 101));
  }

  @Test
  void pendingObservationRetriesWithoutCoverageAndConfirmedDeclineClosesOnlyThatAttempt() {
    doReturn(
            Mono.just(
                new PaymentProvider.Payment(
                    attempt.providerPaymentId(),
                    attempt.invoiceId(),
                    new PaymentProvider.Money(2000, "EUR"),
                    PaymentProvider.State.PENDING)))
        .when(provider)
        .fetchPayment(any(), any());
    assertEquals("PENDING", process());
    assertEquals(0, count("payment_facts"));
    advance(31);
    doReturn(
            Mono.just(
                new PaymentProvider.Payment(
                    attempt.providerPaymentId(),
                    attempt.invoiceId(),
                    new PaymentProvider.Money(2000, "EUR"),
                    PaymentProvider.State.DECLINED)))
        .when(provider)
        .fetchPayment(any(), any());
    assertEquals("DONE", process());
    assertEquals(PaymentAttemptStore.State.FAILED, as(attempts.find(A, attempt.id())).state());
    assertEquals(
        BillingOperationStore.State.FAILED, as(operations.get(A, attempt.operationId())).state());
    assertEquals(0, count("subscription_periods"));
    verify(provider, never()).createCheckout(any(), any(), any(), any());
  }

  @Test
  void retainedOriginalAdapterIsRequiredEvenWhenAnotherProviderIsConfigured() {
    service =
        new PaymentReconciliation(
            jobs,
            attempts,
            operations,
            new PaymentProviderRegistry(
                Map.of(
                    new PaymentProviderRegistry.Identity("NEW", f.identity.merchant()),
                    new PaymentProviderRegistry.Registration(provider, Set.of("v2")))),
            f.registry,
            f.store,
            (t, e) -> Mono.empty());
    assertEquals("MANUAL", process());
    assertEquals("PROVIDER_UNSUPPORTED_CAPABILITY", jobs.manual(1).block(WAIT).getFirst().reason());
    verify(provider, never()).fetchPayment(any(), any());
    assertEquals(0, count("payment_facts"));
  }

  @Test
  void queueOriginCannotBeOverwrittenOrDeleted() {
    assertThrows(
        Exception.class,
        () ->
            db.sql("UPDATE taska.payment_reconciliation SET tenant_id=:other")
                .bind("other", B)
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertFalse(
        db.sql(
                "SELECT has_table_privilege(current_user,'taska.payment_reconciliation','INSERT')"
                    + " OR has_table_privilege(current_user,'taska.payment_reconciliation','DELETE')"
                    + " AS unsafe")
            .map((r, m) -> r.get("unsafe", Boolean.class))
            .one()
            .block(WAIT));
    assertThrows(
        Exception.class,
        () -> db.sql("DELETE FROM taska.payment_reconciliation").fetch().rowsUpdated().block(WAIT));
    assertThrows(Exception.class, () -> as(attempts.find(B, attempt.id())));
    assertEquals("DONE", process());
  }
}
