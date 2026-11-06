package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.FinancialLedgerDependencies.*;
import ru.taska.billing.FinancialLedgerDependencies.Currency;
import ru.taska.billing.PaymentSettlementDependencies.VerifiedPaymentFact;
import ru.taska.exception.*;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class PaymentSettlementIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final InvoiceIntegrationTest f = new InvoiceIntegrationTest();
  PaymentSettlement settlement;
  Map<UUID, VerifiedPaymentFact> facts;
  AtomicReference<TenantPaymentState> root;
  AtomicInteger reads;
  UUID invoice;
  java.util.concurrent.atomic.AtomicBoolean loseDelivery;

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
    prepare(Method.PSP);
  }

  void prepare(Method method) {
    f.resetInvoices();
    var payload = f.payload();
    if (method == Method.WIRE) {
      var q = f.quote.get();
      f.quote.set(
          new InvoiceDependencies.CommercialQuote(
              q.profileVersion(),
              q.seller(),
              q.lines(),
              null,
              WireInstructions.newBuilder()
                  .setBeneficiaryName("Test seller")
                  .setIban("ES0000000000000000000000")
                  .setBic("TESTESMM")
                  .setBankName("Test bank")
                  .setReference("SERVER-REFERENCE")
                  .build(),
              q.validity()));
      payload =
          payload.toBuilder()
              .setPaymentMethod(CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE)
              .build();
    }
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("settlement-invoice", payload)))
                .block(WAIT)
                .getId());
    root =
        new AtomicReference<>(
            TenantPaymentState.newBuilder()
                .setTenantId(A.toString())
                .setLifecycle(Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT)
                .setCreationOperationId(f.obligation.get().operationId().toString())
                .setPlanVersionId(PAID.toString())
                .setCreationDeadlineAt(f.obligation.get().creationDeadline().toString())
                .setVersion("1")
                .build());
    loseDelivery = new java.util.concurrent.atomic.AtomicBoolean();
    facts = new ConcurrentHashMap<>();
    reads = new AtomicInteger();
    var ledger =
        new FinancialLedger(
            f.f.transactions,
            f.f.readers,
            (d, t, e) -> Mono.empty(),
            (d, t, i, m) -> Mono.empty(),
            f.f.clock);
    settlement =
        new PaymentSettlement(
            f.f.store,
            ledger,
            (d, t, e) -> {
              reads.incrementAndGet();
              return Mono.justOrEmpty(facts.get(e));
            },
            t -> Mono.just(root.get()),
            (t, d) ->
                loseDelivery.get()
                    ? Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "ROOT_UNAVAILABLE"))
                    : Mono.just(
                        TenantInitialPaymentResult.newBuilder()
                            .setOutcome(TenantInitialPaymentResult.Outcome.OUTCOME_APPLIED)
                            .build()),
            f.f.clock);
  }

  @AfterEach
  void cleanScope() {
    f.f.scopeDoesNotLeak();
  }

  Movement movement(
      long amount, String external, Method method, Instant occurred, Instant confirmed) {
    return new Movement(
        A,
        new Source("TEST_PSP", "test-account", Environment.SANDBOX, external, method),
        Kind.PAYMENT,
        null,
        null,
        amount,
        Currency.EUR,
        new Occurrence(Precision.EXACT, occurred, null, null),
        confirmed,
        confirmed,
        method == Method.WIRE ? ADMIN : null,
        method == Method.WIRE ? "opaque-payer" : null,
        "receipt/test-evidence",
        "ab".repeat(32));
  }

  UUID proof(Movement m) {
    UUID id = UUID.randomUUID();
    facts.put(id, new VerifiedPaymentFact(A, invoice, f.obligation.get().operationId(), "v1", m));
    return id;
  }

  TrustedTenantContext worker() {
    return f.f.worker(A, "applyVerifiedPayment", "billing-service").toBuilder()
        .addScopes("read")
        .addScopes("ledger:write")
        .build();
  }

  Mono<PaymentSettlementResult> apply(UUID id) {
    return RegistryTestContext.withClaims(worker(), settlement.apply(A, id));
  }

  PaymentSettlementResult paid() {
    return apply(proof(movement(2000, "payment", Method.PSP, NOW, NOW))).block(WAIT);
  }

  long count(String table) {
    return f.count(table);
  }

  String column(String table, String column) {
    return operator
        .sql(
            "SELECT "
                + column
                + "::text value FROM taska."
                + table
                + " WHERE tenant_id='"
                + A
                + "' LIMIT 1")
        .map((r, m) -> r.get("value", String.class))
        .one()
        .block(WAIT);
  }

  Instant instantColumn(String column) {
    return operator
        .sql(
            "SELECT "
                + column
                + " FROM taska.subscription_periods WHERE tenant_id='"
                + A
                + "' ORDER BY ends_at DESC LIMIT 1")
        .map((r, m) -> r.get(column, OffsetDateTime.class).toInstant())
        .one()
        .block(WAIT);
  }

  void review(PaymentSettlementResult r, String reason) {
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_REVIEW_REQUIRED, r.getOutcome());
    assertEquals(reason, r.getReasonCode());
    assertEquals(0, count("subscription_periods"));
    assertEquals(0, count("payment_allocations"));
    assertEquals("OPEN", column("invoices", "state"));
  }

  @Test
  void fullPaymentAtomicallyPublishesRightsAndCurrentInvoiceWithoutRewritingTheSealedDocument() {
    String snapshot = column("invoices", "snapshot");
    var r = paid();
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, r.getOutcome());
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(1, count("payment_settlements"));
    assertEquals("PAID", column("invoices", "state"));
    assertEquals(snapshot, column("invoices", "snapshot"));
    var current = f.get(invoice.toString());
    assertEquals(Invoice.StateValue.STATE_PAID, current.getState());
    assertEquals("2000", current.getAllocated().getAmountMinor());
    assertEquals("0", current.getRemaining().getAmountMinor());
    assertEquals(current, f.list(10, null).getItems(0));
    assertEquals(Instant.parse("2026-02-28T12:34:56Z"), instantColumn("ends_at"));
  }

  @Test
  void differentEventIdsParallelWebhookAndReconcileGrantExactlyOnce() {
    var movement = movement(2000, "same-economic-payment", Method.PSP, NOW, NOW);
    var ids = List.of(proof(movement), proof(movement));
    var results =
        Flux.range(0, 12)
            .flatMap(n -> apply(ids.get(n % 2)).subscribeOn(Schedulers.parallel()), 6)
            .collectList()
            .block(WAIT);
    assertTrue(results.stream().allMatch(results.getFirst()::equals));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_settlements"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(1, count("payment_allocations"));
  }

  @Test
  void outboxFailureRollsBackEveryFinancialAndSubscriptionMutation() {
    sql(
        "CREATE FUNCTION taska.fail_settlement_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.event_type='TenantInitialPaymentDecisionReady' THEN RAISE EXCEPTION 'fixture"
            + " failure'; END IF; RETURN NEW;END $$");
    sql(
        "CREATE TRIGGER fail_settlement_test BEFORE INSERT ON taska.outbox_events FOR EACH ROW"
            + " EXECUTE FUNCTION taska.fail_settlement_test()");
    try {
      assertThrows(RuntimeException.class, this::paid);
    } finally {
      sql("DROP TRIGGER fail_settlement_test ON taska.outbox_events");
      sql("DROP FUNCTION taska.fail_settlement_test()");
    }
    for (String table :
        List.of(
            "payment_facts", "payment_allocations", "subscription_periods", "payment_settlements"))
      assertEquals(0, count(table), table);
    assertEquals("OPEN", column("invoices", "state"));
    assertEquals("AWAITING_PAYMENT", column("subscriptions", "mode"));
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, paid().getOutcome());
  }

  @Test
  void wrongAmountRetainsRealMoneyForReviewWithoutInventingPaid() {
    review(
        apply(proof(movement(1999, "partial", Method.PSP, NOW, NOW))).block(WAIT),
        "FULL_PAYMENT_REQUIRED");
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_settlements"));
  }

  @Test
  void wrongInvoiceOperationProfileOrCurrencyCannotGrantOrRecordForgedBinding() {
    for (int n = 0; n < 4; n++) {
      UUID id = proof(movement(2000, "bad-" + n, Method.PSP, NOW, NOW));
      var p = facts.get(id);
      var m = p.movement();
      if (n == 3)
        m =
            new Movement(
                A,
                m.source(),
                Kind.PAYMENT,
                null,
                null,
                2000,
                Currency.USD,
                m.occurrence(),
                NOW,
                NOW,
                null,
                null,
                m.evidenceRef(),
                m.evidenceDigest());
      facts.put(
          id,
          new VerifiedPaymentFact(
              A,
              n == 0 ? UUID.randomUUID() : invoice,
              n == 1 ? UUID.randomUUID() : p.operationId(),
              n == 2 ? "other" : p.merchantProfileVersion(),
              m));
      assertThrows(DomainException.class, () -> apply(id).block(WAIT));
    }
    assertEquals(0, count("payment_facts"));
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void userForeignTenantWrongPurposeAndWrongServiceAreRejectedBeforeReadingFinancialEvidence() {
    UUID id = proof(movement(2000, "guard", Method.PSP, NOW, NOW));
    for (var claims :
        List.of(
            f.human("applyVerifiedPayment"),
            worker().toBuilder().setTenantId(B.toString()).build(),
            worker().toBuilder().setAction("other").build(),
            worker().toBuilder().setIssuerService("api-gateway").build(),
            worker().toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
                .build())) {
      assertThrows(
          DomainException.class,
          () -> RegistryTestContext.withClaims(claims, settlement.apply(A, id)).block(WAIT));
    }
    assertEquals(0, reads.get());
    assertEquals(0, count("payment_facts"));
  }

  @Test
  void timelyLateConfirmationCanRecoverAutoExpiredCreationUsingActualPspSuccessTime() {
    root.set(
        root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED).build());
    f.f.time.set(NOW.plusSeconds(40 * 86400));
    var r =
        apply(
                proof(
                    movement(
                        2000,
                        "late-confirmation",
                        Method.PSP,
                        NOW.plusSeconds(10),
                        f.f.time.get())))
            .block(WAIT);
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, r.getOutcome());
    assertEquals(Instant.parse("2026-01-31T12:35:06Z"), instantColumn("starts_at"));
  }

  @Test
  void explicitOwnerCancellationPreservesMoneyButNeverRestoresRights() {
    root.set(
        root.get().toBuilder()
            .setLifecycle(Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED)
            .build());
    review(paid(), "TENANT_NOT_ELIGIBLE");
    assertEquals(1, count("payment_facts"));
  }

  @Test
  void successAfterInvoiceDeadlineNeedsFinancialReviewEvenWithLateConfirmation() {
    f.f.time.set(NOW.plusSeconds(40 * 86400));
    review(
        apply(proof(movement(2000, "late-payment", Method.PSP, f.f.time.get(), f.f.time.get())))
            .block(WAIT),
        "PAYMENT_AFTER_DEADLINE");
  }

  @Test
  void changedSelectionRejectsAnOldInvoiceWithoutLosingTheReceivedPayment() {
    sql(
        "UPDATE taska.subscriptions SET selection_generation=selection_generation+1 WHERE"
            + " tenant_id='"
            + A
            + "'");
    review(paid(), "PLAN_SELECTION_CHANGED");
    assertEquals(1, count("payment_facts"));
  }

  @Test
  void replayOfOriginalPaymentCannotRestoreRevokedCoverage() {
    UUID id = proof(movement(2000, "original", Method.PSP, NOW, NOW));
    var first = apply(id).block(WAIT);
    sql("UPDATE taska.subscription_periods SET status='REVOKED' WHERE tenant_id='" + A + "'");
    assertEquals(first, apply(id).block(WAIT));
    assertEquals("REVOKED", column("subscription_periods", "status"));
    assertEquals(1, count("subscription_periods"));
    var claims =
        f.f.worker(A, "getTenantInitialPaymentDecision", "tenant-service").toBuilder()
            .addScopes("read")
            .build();
    assertThrows(
        DomainException.class,
        () ->
            RegistryTestContext.withClaims(
                    claims, settlement.initialDecision(A, UUID.fromString(first.getDecisionId())))
                .block(WAIT));
  }

  @Test
  void secondEconomicPaymentForPaidInvoiceRemainsUnallocated() {
    paid();
    var second = apply(proof(movement(2000, "second-payment", Method.PSP, NOW, NOW))).block(WAIT);
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_REVIEW_REQUIRED, second.getOutcome());
    assertEquals("INVOICE_ALREADY_CLOSED", second.getReasonCode());
    assertEquals(2, count("payment_facts"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void lostRootAckKeepsCommittedMoneyAndRetryReusesTheSameDecision() {
    UUID proof = proof(movement(2000, "lost-root-ack", Method.PSP, NOW, NOW));
    loseDelivery.set(true);
    assertThrows(DomainException.class, () -> apply(proof).block(WAIT));
    assertEquals("PAID", column("invoices", "state"));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
    String decision = column("payment_settlements", "id");
    loseDelivery.set(false);
    assertEquals(decision, apply(proof).block(WAIT).getDecisionId());
    assertEquals(1, count("payment_allocations"));
  }

  @Test
  void newFullPaymentAfterRevocationStartsANewMonthAndDoesNotReuseOldAnchor() {
    paid();
    sql("UPDATE taska.subscription_periods SET status='REVOKED' WHERE tenant_id='" + A + "'");
    Instant now = NOW.plusSeconds(10 * 86400);
    f.f.time.set(now);
    root.set(root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE).build());
    var period = SubscriptionCalendar.initial(now);
    f.obligation.set(
        new InvoiceDependencies.Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_RENEWAL,
            "resume-after-revocation",
            period.startsAt(),
            period.endsAt(),
            null,
            null));
    var request =
        f.payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .build();
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("resume", request)))
                .block(WAIT)
                .getId());
    var r = apply(proof(movement(2000, "new-payment", Method.PSP, now, now))).block(WAIT);
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, r.getOutcome());
    assertEquals(now, instantColumn("starts_at"));
    assertEquals(period.endsAt(), instantColumn("ends_at"));
    assertEquals(2, count("subscription_periods"));
  }

  @Test
  void cheaperPaidPlanUsesTheCurrentIntentAndAtomicallyStartsAFullMonth() {
    paid();
    root.set(root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE).build());
    f.f.planCommands();
    UUID target = f.f.plan(1000);
    var change = f.f.select(target, "cheaper-paid");
    UUID operation = UUID.fromString(change.getOperationId());
    f.obligation.set(
        new InvoiceDependencies.Obligation(
            A,
            operation,
            target,
            Invoice.PurposeValue.PURPOSE_PLAN_CHANGE,
            "plan-change:" + operation,
            null,
            null,
            null,
            null));
    f.quote.set(InvoiceIntegrationTest.commercial("v1", 1000, Money.CurrencyValue.CURRENCY_EUR));
    var request =
        f.payload().toBuilder()
            .setPlanVersionId(target.toString())
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_PLAN_CHANGE)
            .build();
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("cheaper", request)))
                .block(WAIT)
                .getId());
    Instant now = NOW.plusSeconds(2 * 86400);
    f.f.time.set(now);
    var result = apply(proof(movement(1000, "cheap-payment", Method.PSP, now, now))).block(WAIT);
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, result.getOutcome());
    assertEquals(now, instantColumn("starts_at"));
    assertEquals(SubscriptionCalendar.initial(now).endsAt(), instantColumn("ends_at"));
    assertEquals(target.toString(), column("subscriptions", "selected_plan_version_id"));
    assertEquals("APPLIED", column("subscription_plan_changes", "state"));
    assertEquals(
        1L,
        operator
            .sql(
                "SELECT count(*) n FROM taska.subscription_periods WHERE tenant_id=:t AND"
                    + " status='SUPERSEDED'")
            .bind("t", A)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        "PAID",
        operator
            .sql("SELECT state FROM taska.invoices WHERE id=:id")
            .bind("id", invoice)
            .map((r, m) -> r.get("state", String.class))
            .one()
            .block(WAIT));
  }

  @Test
  void oneEconomicPaymentCannotBeReboundToAnotherInvoice() {
    var movement = movement(2000, "one-payment", Method.PSP, NOW, NOW);
    apply(proof(movement)).block(WAIT);
    Instant now = NOW.plusSeconds(1);
    f.f.time.set(now);
    root.set(root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE).build());
    var period = SubscriptionCalendar.initial(now);
    f.obligation.set(
        new InvoiceDependencies.Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_RENEWAL,
            "another-invoice",
            period.startsAt(),
            period.endsAt(),
            null,
            null));
    var request =
        f.payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .build();
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("another-invoice", request)))
                .block(WAIT)
                .getId());
    assertThrows(DomainException.class, () -> apply(proof(movement)).block(WAIT));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(
        "OPEN",
        operator
            .sql("SELECT state FROM taska.invoices WHERE id=:id")
            .bind("id", invoice)
            .map((r, m) -> r.get("state", String.class))
            .one()
            .block(WAIT));
  }

  @Test
  void pendingFreeTransitionKeepsAReceivedSuccessRetryableWithoutAFinalDenial() {
    paid();
    root.set(root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE).build());
    var period = SubscriptionCalendar.initial(NOW.plusSeconds(1));
    f.obligation.set(
        new InvoiceDependencies.Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_RENEWAL,
            "pending-free-renewal",
            period.startsAt(),
            period.endsAt(),
            null,
            null));
    var request =
        f.payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .build();
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("pending-free", request)))
                .block(WAIT)
                .getId());
    f.f.planCommands();
    f.f.loseFreeReply.set(true);
    assertThrows(DomainException.class, () -> f.f.select(FREE, "free-response-lost"));
    var error =
        assertThrows(
            DomainException.class,
            () -> apply(proof(movement(2000, "pending-free", Method.PSP, NOW, NOW))).block(WAIT));
    assertEquals("FREE_TRANSITION_PENDING", error.getDetails().get("code"));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_settlements"));
  }

  @Test
  void timelyWireReceiptConfirmedAfterInvoiceDeadlineStartsItsMonthAtConfirmation() {
    prepare(Method.WIRE);
    Instant confirmed = NOW.plusSeconds(10 * 86400);
    f.f.time.set(confirmed);
    var result =
        apply(proof(movement(2000, "wire-payment", Method.WIRE, NOW, confirmed))).block(WAIT);
    assertEquals(PaymentSettlementResult.Outcome.OUTCOME_APPLIED, result.getOutcome());
    assertEquals(confirmed, instantColumn("starts_at"));
    assertEquals(SubscriptionCalendar.initial(confirmed).endsAt(), instantColumn("ends_at"));
  }

  <T> T ledgerWorker(String action, Mono<T> operation) {
    return RegistryTestContext.withClaims(worker().toBuilder().setAction(action).build(), operation)
        .block(WAIT);
  }

  @Test
  void invoiceProjectionTracksPendingPrincipalAndItsConfirmedReturn() {
    var proof =
        new java.util.concurrent.atomic.AtomicReference<Movement>(
            movement(1200, "pending-receipt", Method.PSP, NOW, NOW));
    var ledger =
        new FinancialLedger(
            f.f.transactions,
            f.f.readers,
            (d, t, e) -> Mono.just(proof.get()),
            (d, t, i, ids) -> Mono.empty(),
            f.f.clock);
    var payment =
        ledgerWorker("recordFinancialFact", ledger.record(A, UUID.randomUUID(), "pending-receipt"));
    ledgerWorker("matchInvoicePayment", ledger.match(A, payment.id(), invoice, "pending-match"));
    var current = f.get(invoice.toString());
    assertEquals("0", current.getAllocated().getAmountMinor());
    assertEquals("1200", current.getMatchedUnallocated().getAmountMinor());
    assertEquals("800", current.getRemaining().getAmountMinor());
    assertEquals(current, f.list(10, null).getItems(0));
    var original = proof.get();
    proof.set(
        new Movement(
            A,
            new Source(
                "TEST_PSP", "test-account", Environment.SANDBOX, "pending-return", Method.PSP),
            Kind.REFUND,
            payment.id(),
            null,
            500,
            Currency.EUR,
            original.occurrence(),
            NOW,
            NOW,
            null,
            null,
            original.evidenceRef(),
            original.evidenceDigest()));
    ledgerWorker("recordFinancialFact", ledger.record(A, UUID.randomUUID(), "pending-return"));
    current = f.get(invoice.toString());
    assertEquals("700", current.getMatchedUnallocated().getAmountMinor());
    assertEquals("1300", current.getRemaining().getAmountMinor());
    assertEquals(Invoice.StateValue.STATE_OPEN, current.getState());
    assertEquals(current, f.list(10, null).getItems(0));
  }

  @Test
  void reviewReceiptStaysHistoricalAfterANewEconomicPaymentSettlesTheInvoice() {
    UUID partial = proof(movement(1200, "reviewed-partial", Method.PSP, NOW, NOW));
    var previous = apply(partial).block(WAIT);
    review(previous, "FULL_PAYMENT_REQUIRED");
    paid();
    assertEquals(previous, apply(partial).block(WAIT));
    assertEquals(2, count("payment_facts"));
    assertEquals(2, count("payment_settlements"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(1, count("subscription_periods"));
    assertEquals(Invoice.StateValue.STATE_PAID, f.get(invoice.toString()).getState());
  }

  @Test
  void concurrentOwnerChoiceAndPaymentNeverApplyAnUnpaidTargetPlan() {
    paid();
    root.set(root.get().toBuilder().setLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE).build());
    f.f.planCommands();
    var period = SubscriptionCalendar.initial(NOW.plusSeconds(1));
    f.obligation.set(
        new InvoiceDependencies.Obligation(
            A,
            UUID.randomUUID(),
            PAID,
            Invoice.PurposeValue.PURPOSE_RENEWAL,
            "race-invoice",
            period.startsAt(),
            period.endsAt(),
            null,
            null));
    var request =
        f.payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .build();
    invoice =
        UUID.fromString(
            f.as("createInvoice", f.invoices.create(f.request("race-invoice", request)))
                .block(WAIT)
                .getId());
    UUID target = f.f.plan(1000),
        evidence = proof(movement(2000, "concurrent-renewal", Method.PSP, NOW, NOW));
    var choice = f.f.selection(target, "race-choice", f.f.get(OWNER_A, A).getVersion());
    var results =
        Mono.zip(
                apply(evidence).subscribeOn(Schedulers.parallel()),
                f.f.change(choice).materialize().subscribeOn(Schedulers.parallel()))
            .block(WAIT);
    var result = results.getT1();
    if (result.getOutcome() == PaymentSettlementResult.Outcome.OUTCOME_APPLIED)
      assertEquals(2, count("subscription_periods"));
    else {
      assertEquals("PLAN_SELECTION_CHANGED", result.getReasonCode());
      assertEquals(1, count("subscription_periods"));
    }
    if (results.getT2().isOnError())
      assertInstanceOf(DomainException.class, results.getT2().getThrowable());
    assertEquals(2, count("payment_facts"));
    assertEquals(
        0L,
        operator
            .sql(
                "SELECT count(*) n FROM taska.subscription_periods WHERE tenant_id=:t AND"
                    + " plan_version_id=:plan")
            .bind("t", A)
            .bind("plan", target)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void invoiceWithoutItsOwningSubscriptionIsRejectedBeforeAnyGenerationCanBeGuessed() {
    UUID missing = UUID.randomUUID(), id = UUID.randomUUID();
    var error =
        assertThrows(
            RuntimeException.class,
            () ->
                operator
                    .sql("SELECT 1")
                    .then()
                    .then(
                        operator
                            .sql(
                                """
                                INSERT INTO taska.invoices SELECT (jsonb_populate_record(NULL::taska.invoices,to_jsonb(i)||jsonb_build_object(
                                  'id',CAST(:id AS text),'tenant_id',CAST(:tenant AS text),'number',:number,'state','DRAFT',
                                  'operation_id',CAST(:op AS text),'target_key',:target))).* FROM taska.invoices i WHERE i.id=:source
                                """)
                            .bind("id", id)
                            .bind("tenant", missing)
                            .bind("number", "NO-SUB-" + id)
                            .bind("op", UUID.randomUUID())
                            .bind("target", id.toString())
                            .bind("source", invoice)
                            .then())
                    .block(WAIT));
    Throwable cause = error;
    while (!(cause instanceof io.r2dbc.spi.R2dbcException) && cause.getCause() != null)
      cause = cause.getCause();
    assertInstanceOf(io.r2dbc.spi.R2dbcException.class, cause);
    assertEquals("P0002", ((io.r2dbc.spi.R2dbcException) cause).getSqlState());
    assertEquals(1, count("invoices"));
  }
}
