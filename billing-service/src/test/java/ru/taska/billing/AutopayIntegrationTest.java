package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static ru.taska.billing.SubscriptionIntegrationTest.A;
import static ru.taska.billing.SubscriptionIntegrationTest.B;
import static ru.taska.billing.SubscriptionIntegrationTest.OTHER;
import static ru.taska.billing.SubscriptionIntegrationTest.OWNER_A;
import static ru.taska.billing.SubscriptionIntegrationTest.WAIT;
import static ru.taska.billing.SubscriptionIntegrationTest.operator;
import static ru.taska.billing.SubscriptionIntegrationTest.sql;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.AutopayConsentRequest;
import ru.taska.api.b2b.v1.AutopayMandate;
import ru.taska.api.b2b.v1.CreateInvoiceRequest;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.SetAutopayConsentRpcRequest;
import ru.taska.billing.AutopayDependencies.Grant;
import ru.taska.billing.AutopayDependencies.MethodBinding;
import ru.taska.billing.payment.BillingOperationStore;
import ru.taska.billing.payment.FakePaymentProvider;
import ru.taska.billing.payment.PaymentAttemptStore;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.billing.payment.PaymentProviderRegistry;
import ru.taska.billing.payment.ProviderException;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class AutopayIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  static final String CONSENT_TEXT =
      "Разрешаю автоматическое списание полной стоимости следующего периода с выбранного метода.";
  static final String CONSENT_SHA =
      java.util.HexFormat.of().formatHex(AutopayStore.digest(CONSENT_TEXT));
  final InvoiceIntegrationTest fixture = new InvoiceIntegrationTest();
  final UUID method = UUID.randomUUID();
  final MethodBinding binding =
      new MethodBinding("OLD", "merchant-A", PaymentProvider.Environment.TEST, "v1");
  AtomicBoolean enabled;
  AtomicLong ownerVersion;
  AtomicReference<String> barrier;
  FakePaymentProvider provider;
  PaymentProviderRegistry registry;
  AutopayStore store;

  @BeforeAll
  static void setup() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    SubscriptionIntegrationTest.close();
  }

  @AfterEach
  void noLeak() {
    fixture.f.scopeDoesNotLeak();
  }

  @BeforeEach
  void reset() {
    fixture.resetInvoices();
    enabled = new AtomicBoolean(true);
    ownerVersion = new AtomicLong(1);
    barrier = new AtomicReference<>("initial");
    provider = spy(new FakePaymentProvider(new byte[32], fixture.f.clock));
    doReturn(Mono.just(new PaymentProvider.Capabilities(true, true, true, false, true, true)))
        .when(provider)
        .capabilities(any());
    registry =
        new PaymentProviderRegistry(
            Map.of(
                new PaymentProviderRegistry.Identity("OLD", binding.merchant()),
                new PaymentProviderRegistry.Registration(provider, Set.of("v1"))));
    store =
        new AutopayStore(
            fixture.f.store,
            (context, use, id) ->
                Mono.just(
                    new Grant(
                        UUID.fromString(fixture.f.owners.get(context.tenantId())),
                        ownerVersion.get(),
                        barrier.get(),
                        enabled.get(),
                        "terms-v1",
                        CONSENT_SHA,
                        binding,
                        CONSENT_TEXT,
                        "config-v1")),
            registry,
            fixture.f.clock);
    seedMethod(A, OWNER_A);
  }

  void seedMethod(UUID tenant, UUID owner) {
    sql("DELETE FROM taska.payment_methods WHERE id='" + method + "'");
    sql(
        "INSERT INTO"
            + " taska.payment_methods(id,tenant_id,owner_id,provider,provider_customer_ref,provider_method_ref,status,created_at)"
            + " VALUES('"
            + method
            + "','"
            + tenant
            + "','"
            + owner
            + "','OLD','customer','saved-method','ACTIVE',now())");
  }

  SetAutopayConsentRpcRequest request(boolean consent, String key, long version) {
    var body = AutopayConsentRequest.newBuilder().setConsent(consent).setConfirmed(true);
    if (consent) body.setPaymentMethodId(method.toString()).setTermsVersion("terms-v1");
    return SetAutopayConsentRpcRequest.newBuilder()
        .setTenantId(A.toString())
        .setPayload(body)
        .setMutation(
            MutationOptions.newBuilder()
                .setIdempotencyKey(key)
                .setExpectedVersion(Long.toString(version)))
        .build();
  }

  <T> Mono<T> as(Mono<T> work) {
    return fixture.as("setAutopayConsent", work);
  }

  AutopayMandate consent(boolean enabled, String key, long version) {
    return as(store.consent(request(enabled, key, version))).block(WAIT);
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  long version() {
    return operator
        .sql("SELECT version FROM taska.subscriptions WHERE tenant_id='" + A + "'")
        .map((r, m) -> r.get("version", Long.class))
        .one()
        .block(WAIT);
  }

  DomainException denied(Mono<?> work, DomainStatus status) {
    var error = assertThrows(DomainException.class, () -> as(work).block(WAIT));
    assertEquals(status, error.getStatus());
    return error;
  }

  @Test
  void firstConsentUsesSubscriptionVersionAndReplayIsFrozen() {
    assertEquals(1, version());
    var first = consent(true, "enable", 1);
    assertEquals(AutopayMandate.StateValue.STATE_CONSENTED, first.getState());
    assertEquals(2, version());
    assertEquals(CONSENT_SHA, first.getConsentSnapshot().getConsentTermsSha256());
    assertEquals(first.getConsentedAt(), first.getConsentSnapshot().getConsentedAt());
    assertEquals(
        CONSENT_TEXT,
        operator
            .sql("SELECT consent_text FROM taska.autopay_mandates WHERE tenant_id='" + A + "'")
            .map((r, m) -> r.get("consent_text", String.class))
            .one()
            .block(WAIT));
    assertEquals(OWNER_A.toString(), first.getConsentSnapshot().getActorUserId());
    assertEquals(method.toString(), first.getConsentSnapshot().getPaymentMethodId());
    var disabled = consent(false, "disable", 2);
    assertEquals(AutopayMandate.StateValue.STATE_DISABLED, disabled.getState());
    assertEquals(3, version());
    assertEquals(first, consent(true, "enable", 1));
    assertEquals(3, version());
    assertEquals(1, count("autopay_mandates"));
    assertEquals(2, count("idempotency_records"));
    assertEquals(
        2,
        operator
            .sql(
                "SELECT count(*) n FROM taska.outbox_events WHERE"
                    + " event_type='AUTOPAY_CONSENT_CHANGED'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void concurrentIdenticalConsentCreatesOneMandateAndOneVersion() {
    var result =
        as(Flux.range(0, 6)
                .flatMap(
                    i -> store.consent(request(true, "same", 1)).subscribeOn(Schedulers.parallel()),
                    6)
                .collectList())
            .block(WAIT);
    assertEquals(1, result.stream().distinct().count());
    assertEquals(1, count("autopay_mandates"));
    assertEquals(2, version());
  }

  @Test
  void concurrentDifferentCommandsCannotOverwriteConsent() {
    var result =
        as(Flux.range(0, 6)
                .flatMap(
                    i ->
                        store
                            .consent(request(true, "different-" + i, 1))
                            .materialize()
                            .subscribeOn(Schedulers.parallel()),
                    6)
                .collectList())
            .block(WAIT);
    assertEquals(1, result.stream().filter(s -> s.hasValue()).count());
    assertEquals(5, result.stream().filter(s -> s.hasError()).count());
    assertEquals(1, count("autopay_mandates"));
    assertEquals(2, version());
  }

  @Test
  void staleVersionAndChangedIntentLeaveNoSideEffects() {
    consent(true, "original", 1);
    denied(store.consent(request(false, "stale", 1)), DomainStatus.ABORTED);
    denied(store.consent(request(false, "original", 1)), DomainStatus.ALREADY_EXISTS);
    assertEquals(2, version());
    assertEquals(1, count("autopay_mandates"));
    assertEquals(1, count("idempotency_records"));
  }

  @Test
  void foreignMethodCannotBecomeConsent() {
    seedMethod(B, OWNER_A);
    denied(store.consent(request(true, "foreign", 1)), DomainStatus.NOT_FOUND);
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
  }

  @Test
  void methodOfAnotherOwnerCannotBecomeConsent() {
    seedMethod(A, OTHER);
    denied(store.consent(request(true, "foreign-owner", 1)), DomainStatus.PERMISSION_DENIED);
    assertEquals(1, version());
    assertEquals(0, count("idempotency_records"));
  }

  @Test
  void ordinaryNonOwnerAndAnotherTenantContextAreDenied() {
    var context =
        fixture.f.human(OTHER, A, "setAutopayConsent").toBuilder().addScopes("write").build();
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    RegistryTestContext.withClaims(
                            context, store.consent(request(true, "other", 1)))
                        .block(WAIT))
            .getStatus());
    denied(
        store.consent(
            request(true, "wrong-tenant", 1).toBuilder().setTenantId(B.toString()).build()),
        DomainStatus.PERMISSION_DENIED);
    assertEquals(0, count("autopay_mandates"));
  }

  @Test
  void flagsUnsupportedProviderAndChangedTermsDoNotChangeVersion() {
    enabled.set(false);
    denied(store.consent(request(true, "flags", 1)), DomainStatus.FAILED_PRECONDITION);
    enabled.set(true);
    doReturn(Mono.just(new PaymentProvider.Capabilities(true, true, true, false, false, true)))
        .when(provider)
        .capabilities(any());
    denied(store.consent(request(true, "unsupported", 1)), DomainStatus.FAILED_PRECONDITION);
    var q = request(true, "terms", 1);
    denied(
        store.consent(
            q.toBuilder().setPayload(q.getPayload().toBuilder().setTermsVersion("old")).build()),
        DomainStatus.FAILED_PRECONDITION);
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
  }

  @Test
  void explicitConfirmationAndExpectedSubscriptionVersionAreRequired() {
    var q = request(true, "confirmation", 1);
    denied(
        store.consent(
            q.toBuilder().setPayload(q.getPayload().toBuilder().setConfirmed(false)).build()),
        DomainStatus.FAILED_PRECONDITION);
    denied(
        store.consent(
            q.toBuilder().setMutation(q.getMutation().toBuilder().clearExpectedVersion()).build()),
        DomainStatus.INVALID_ARGUMENT);
    assertEquals(1, version());
  }

  @Test
  void outboxFailureRollsBackConsentVersionAndIdempotency() {
    sql(
        "CREATE FUNCTION taska.reject_autopay_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.event_type='AUTOPAY_CONSENT_CHANGED' THEN RAISE EXCEPTION 'fixture'; END IF;"
            + " RETURN NEW; END $$; CREATE TRIGGER reject_autopay_test BEFORE INSERT ON"
            + " taska.outbox_events FOR EACH ROW EXECUTE FUNCTION taska.reject_autopay_test()");
    try {
      assertThrows(RuntimeException.class, () -> consent(true, "failed", 1));
    } finally {
      sql(
          "DROP TRIGGER reject_autopay_test ON taska.outbox_events; DROP FUNCTION"
              + " taska.reject_autopay_test()");
    }
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
    assertEquals(0, count("idempotency_records"));
  }

  @Test
  void revokedConsentCannotBeReactivatedOrRewritten() {
    var first = consent(true, "first", 1);
    consent(false, "off", 2);
    assertThrows(
        RuntimeException.class,
        () ->
            sql(
                "UPDATE taska.autopay_mandates SET"
                    + " state='ACTIVE',revoked_at=NULL,revocation_reason=NULL WHERE id='"
                    + first.getId()
                    + "'"));
    assertThrows(
        RuntimeException.class,
        () ->
            sql(
                "UPDATE taska.autopay_mandates SET consent_terms_version='rewritten' WHERE id='"
                    + first.getId()
                    + "'"));
    var next = consent(true, "new", 3);
    assertNotEquals(first.getId(), next.getId());
    assertEquals(2, count("autopay_mandates"));
  }

  @Test
  void financialSubscriptionRevocationPermanentlyRevokesMandate() {
    consent(true, "first", 1);
    sql("UPDATE taska.subscriptions SET autopay_state='DISABLED' WHERE tenant_id='" + A + "'");
    assertEquals(
        1,
        operator
            .sql("SELECT count(*) n FROM taska.autopay_mandates WHERE state='REVOKED'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    sql("UPDATE taska.subscriptions SET autopay_state='CONSENTED' WHERE tenant_id='" + A + "'");
    assertEquals(
        0,
        operator
            .sql("SELECT count(*) n FROM taska.autopay_mandates WHERE state='ACTIVE'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  UUID renewalInvoice;
  UUID sourcePeriod;
  PaymentAttemptStore attempts;
  BillingOperationStore operations;
  AutopayCoordinator coordinator;
  AtomicInteger mapped;

  void renewal() {
    UUID proof =
        fixture.f.proof(A, SubscriptionIntegrationTest.NOW, Instant.parse("2026-02-28T12:34:56Z"));
    fixture.f.grant(A, proof, "paid").block(WAIT);
    sourcePeriod = fixture.f.confirmed.get(proof).activationOperationId();
    consent(true, "consent-for-renewal", version());
    var invoices =
        new InvoiceStore(
            fixture.f.transactions,
            fixture.f.readers,
            new RenewalCalendar(),
            (db, plan, p, now) -> Mono.just(fixture.quote.get()),
            fixture.catalog,
            fixture.f.clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), fixture.f.clock),
            (db, c, source, p) -> Mono.just(true));
    var payload =
        fixture.payload().toBuilder()
            .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
            .build();
    var invoice =
        fixture
            .f
            .asWorker(
                A,
                "issueRenewalInvoice",
                "billing-service",
                invoices.issueScheduledRenewal(A, sourcePeriod, payload))
            .block(WAIT);
    renewalInvoice = UUID.fromString(invoice.getId());
    attempts = new PaymentAttemptStore(fixture.f.transactions, fixture.f.clock);
    operations = new BillingOperationStore(fixture.f.transactions, fixture.f.clock);
    mapped = new AtomicInteger();
    coordinator =
        new AutopayCoordinator(
            store, attempts, operations, a -> Mono.fromRunnable(mapped::incrementAndGet));
    doAnswer(
            i ->
                provider.createCheckout(
                    i.getArgument(0), i.getArgument(1), i.getArgument(3), i.getArgument(4)))
        .when(provider)
        .chargeRecurring(any(), any(), any(), any(), any());
  }

  <T> Mono<T> worker(Mono<T> work) {
    return RegistryTestContext.withClaims(
        fixture.f.worker(A, "dispatchAutopay", "billing-service").toBuilder()
            .addScopes("payment:recurring")
            .build(),
        work);
  }

  PaymentAttemptStore.Attempt dispatch() {
    return worker(coordinator.dispatch(A, renewalInvoice)).block(WAIT);
  }

  void refusesDispatch() {
    assertEquals(
        DomainStatus.FAILED_PRECONDITION,
        assertThrows(DomainException.class, this::dispatch).getStatus());
    assertEquals(0, count("payment_attempts"));
    assertEquals(0, count("autopay_dispatches"));
  }

  @Test
  void recurringAttemptCommitsOnceAndDoesNotInventCoverage() {
    renewal();
    var first = dispatch();
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, first.state());
    assertEquals(first.id(), dispatch().id());
    assertEquals(1, count("payment_attempts"));
    assertEquals(1, count("autopay_dispatches"));
    assertEquals(1, count("subscription_periods"));
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
    assertTrue(mapped.get() > 0);
  }

  @Test
  void concurrentSchedulerAndRestartCannotCreateSecondAttempt() {
    renewal();
    var results =
        worker(
                Flux.range(0, 6)
                    .flatMap(
                        i ->
                            coordinator
                                .dispatch(A, renewalInvoice)
                                .subscribeOn(Schedulers.parallel()),
                        6)
                    .collectList())
            .block(WAIT);
    assertEquals(1, results.stream().map(PaymentAttemptStore.Attempt::id).distinct().count());
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    assertEquals(results.getFirst().id(), dispatch().id());
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void revocationCancelPauseAndDisputeBeforeAdmissionStopNewAttempts() {
    renewal();
    enabled.set(false);
    refusesDispatch();
    enabled.set(true);
    sql("UPDATE taska.subscriptions SET renewal_cancelled=true WHERE tenant_id='" + A + "'");
    refusesDispatch();
    sql("UPDATE taska.subscriptions SET renewal_cancelled=false WHERE tenant_id='" + A + "'");
    barrier.set("dispute-ever-observed");
    refusesDispatch();
    consent(false, "withdraw", version());
    refusesDispatch();
  }

  @Test
  void ownerVersionAndTransferBackCannotRevivePreviousConsent() {
    renewal();
    ownerVersion.incrementAndGet();
    refusesDispatch();
    fixture.f.owners.put(A.toString(), OTHER.toString());
    refusesDispatch();
    fixture.f.owners.put(A.toString(), OWNER_A.toString());
    refusesDispatch();
    consent(true, "fresh-owner-consent", version());
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, dispatch().state());
  }

  @Test
  void resolvedDisputeRequiresFreshConsentAndDoesNotCreateCoverage() {
    renewal();
    barrier.set("resolved-dispute");
    refusesDispatch();
    consent(true, "after-dispute", version());
    assertEquals(1, count("subscription_periods"));
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, dispatch().state());
  }

  @Test
  void expiredPeriodCannotBecomeAutomaticDebtAfterResumeOrNewConsent() {
    renewal();
    fixture.f.time.set(Instant.parse("2026-02-28T12:34:56Z"));
    refusesDispatch();
    consent(true, "after-expiry", version());
    refusesDispatch();
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void changedSelectionOrRevokedMethodStopsAdmission() {
    renewal();
    sql(
        "UPDATE taska.payment_methods SET status='REVOKED',revoked_at=now() WHERE id='"
            + method
            + "'");
    refusesDispatch();
    assertThrows(
        RuntimeException.class,
        () ->
            sql(
                "UPDATE taska.payment_methods SET status='ACTIVE',revoked_at=NULL WHERE id='"
                    + method
                    + "'"));
  }

  @Test
  void previousSelectionInvoiceCannotBeCharged() {
    renewal();
    sql(
        "UPDATE taska.subscriptions SET selection_generation=selection_generation+1 WHERE"
            + " tenant_id='"
            + A
            + "'");
    refusesDispatch();
  }

  @Test
  void unknownOutcomeIsReconciledWithoutResubmissionAfterRestart() {
    renewal();
    doReturn(Mono.error(new ProviderException(ProviderException.Reason.UNKNOWN_OUTCOME)))
        .when(provider)
        .chargeRecurring(any(), any(), any(), any(), any());
    var first = dispatch();
    assertEquals(PaymentAttemptStore.State.UNKNOWN, first.state());
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    assertEquals(first.id(), dispatch().id());
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
    assertEquals(1, count("payment_attempts"));
  }

  @Test
  void acceptedAttemptIsReconciledAfterConsentWithdrawal() throws Exception {
    renewal();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i ->
                Mono.defer(
                        () -> {
                          assertEquals(1, count("autopay_dispatches"));
                          entered.countDown();
                          return Mono.fromCallable(
                                  () -> {
                                    assertTrue(release.await(10, TimeUnit.SECONDS));
                                    return true;
                                  })
                              .subscribeOn(Schedulers.boundedElastic())
                              .flatMap(
                                  ignored ->
                                      provider.createCheckout(
                                          i.getArgument(0),
                                          i.getArgument(1),
                                          i.getArgument(3),
                                          i.getArgument(4)));
                        })
                    .subscribeOn(Schedulers.boundedElastic()))
        .when(provider)
        .chargeRecurring(any(), any(), any(), any(), any());
    var running = worker(coordinator.dispatch(A, renewalInvoice)).toFuture();
    assertTrue(entered.await(10, TimeUnit.SECONDS));
    consent(false, "withdraw-after-accept", version());
    release.countDown();
    var accepted = running.get(15, TimeUnit.SECONDS);
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, accepted.state());
    assertEquals(accepted.id(), dispatch().id());
    assertEquals(
        "DISABLED",
        operator
            .sql("SELECT autopay_state FROM taska.subscriptions WHERE tenant_id='" + A + "'")
            .map((r, m) -> r.get("autopay_state", String.class))
            .one()
            .block(WAIT));
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void userOwnerAndServiceWithoutRecurringScopeCannotDispatch() {
    renewal();
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () -> as(coordinator.dispatch(A, renewalInvoice)).block(WAIT))
            .getStatus());
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    fixture
                        .f
                        .asWorker(
                            A,
                            "dispatchAutopay",
                            "billing-service",
                            coordinator.dispatch(A, renewalInvoice))
                        .block(WAIT))
            .getStatus());
    assertEquals(0, count("payment_attempts"));
  }

  @Test
  void deterministicAdapterFailureRetainsDurableFence() {
    renewal();
    doReturn(Mono.error(new IllegalStateException("fixture-crash")))
        .when(provider)
        .chargeRecurring(any(), any(), any(), any(), any());
    assertThrows(IllegalStateException.class, this::dispatch);
    assertEquals(PaymentAttemptStore.State.UNKNOWN, dispatch().state());
    assertEquals(1, count("autopay_dispatches"));
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
    fixture.f.time.set(fixture.f.time.get().plusSeconds(61));
    var jobs =
        new ru.taska.billing.reconciliation.PaymentReconciliationStore(
            SubscriptionIntegrationTest.db, fixture.f.clock, 3);
    var job = jobs.claim(1).block(WAIT).getFirst();
    var recovery =
        new ru.taska.billing.reconciliation.PaymentReconciliation(
            jobs,
            attempts,
            operations,
            registry,
            org.mockito.Mockito.mock(ru.taska.billing.ingress.ProviderWebhookRegistry.class),
            org.mockito.Mockito.mock(ru.taska.billing.ingress.ProviderInboxStore.class),
            (tenant, evidence) ->
                Mono.error(new AssertionError("No settlement without original PSP identity")));
    var context =
        fixture.f.worker(A, "reconcilePaymentAttempt", "billing-service").toBuilder()
            .addScopes("payment:reconcile")
            .build();
    var status =
        RegistryTestContext.withClaims(
                context, recovery.process(job.tenantId(), job.attemptId(), job.token()))
            .block(WAIT);
    assertEquals("MANUAL", status.state());
    assertEquals("ORIGINAL_OBJECT_ID_REQUIRED", status.reason());
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void preparedFreeSelectionBlocksRecurringBeforeAnyAttempt() {
    renewal();
    sql(
        "INSERT INTO"
            + " taska.subscription_plan_changes(id,tenant_id,actor_id,request_key,request_hash,owner_id,owner_version,requested_version,selection_generation,previous_plan_version_id,target_plan_version_id,target_terms_snapshot,effect,state,created_at)"
            + " SELECT gen_random_uuid(),tenant_id,'"
            + OWNER_A
            + "','fixture-free',decode(repeat('00',32),'hex'),'"
            + OWNER_A
            + "',1,version,selection_generation,selected_plan_version_id,'00000000-0000-8000-8000-000000000001','{}','IMMEDIATE','PREPARED',now()"
            + " FROM taska.subscriptions WHERE tenant_id='"
            + A
            + "'");
    refusesDispatch();
  }

  @Test
  void currentAuthorityIsRecheckedAfterAttemptPreparationAndRollsBackEverything() {
    renewal();
    var original = store;
    var checks = new AtomicInteger();
    store =
        new AutopayStore(
            fixture.f.store,
            (c, use, id) -> {
              if (use == AutopayDependencies.Use.DISPATCH && checks.incrementAndGet() == 2)
                enabled.set(false);
              return original.policy.require(c, use, id);
            },
            registry,
            fixture.f.clock);
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    refusesDispatch();
    assertEquals(0, count("billing_operations"));
    assertEquals(2, checks.get());
    verify(provider, times(0)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void dispatchReceiptFailureRollsBackOperationAndNeverContactsProvider() {
    renewal();
    sql(
        "CREATE FUNCTION taska.fail_autopay_dispatch_test() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'fixture'; END $$; CREATE TRIGGER fail_autopay_dispatch_test"
            + " BEFORE INSERT ON taska.autopay_dispatches FOR EACH ROW EXECUTE FUNCTION"
            + " taska.fail_autopay_dispatch_test()");
    try {
      assertThrows(RuntimeException.class, this::dispatch);
    } finally {
      sql(
          "DROP TRIGGER fail_autopay_dispatch_test ON taska.autopay_dispatches; DROP FUNCTION"
              + " taska.fail_autopay_dispatch_test()");
    }
    assertEquals(0, count("payment_attempts"));
    assertEquals(0, count("billing_operations"));
    verify(provider, times(0)).chargeRecurring(any(), any(), any(), any(), any());
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, dispatch().state());
  }

  @Test
  void cancellationWinningSubscriptionLockPreventsConcurrentDispatch() throws Exception {
    renewal();
    var original = store;
    var entered = reactor.core.publisher.Sinks.<Void>one();
    var release = reactor.core.publisher.Sinks.<Void>one();
    store =
        new AutopayStore(
            fixture.f.store,
            (c, use, id) -> {
              if (use == AutopayDependencies.Use.REVOKE)
                return Mono.defer(
                    () -> {
                      entered.tryEmitEmpty();
                      return release.asMono().then(original.policy.require(c, use, id));
                    });
              return original.policy.require(c, use, id);
            },
            registry,
            fixture.f.clock);
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    var cancelling = as(store.consent(request(false, "cancel-winning", version()))).toFuture();
    entered.asMono().block(WAIT);
    var dispatching = worker(coordinator.dispatch(A, renewalInvoice)).toFuture();
    release.tryEmitEmpty();
    assertEquals(
        AutopayMandate.StateValue.STATE_DISABLED, cancelling.get(10, TimeUnit.SECONDS).getState());
    var error =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> dispatching.get(10, TimeUnit.SECONDS));
    assertEquals(
        DomainStatus.FAILED_PRECONDITION, ((DomainException) error.getCause()).getStatus());
    assertEquals(0, count("payment_attempts"));
    verify(provider, times(0)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void declineStaysObservableAndDoesNotStartAnAutomaticRetry() {
    renewal();
    provider.nextPayment(FakePaymentProvider.Scenario.DECLINE);
    var failed = dispatch();
    assertEquals(PaymentAttemptStore.State.FAILED, failed.state());
    assertEquals(failed.id(), dispatch().id());
    assertEquals(1, count("subscription_periods"));
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void asynchronousActionCanFinishAfterWithdrawalWithoutRestoringConsent() {
    renewal();
    var phase = new AtomicReference<>(PaymentProvider.State.PENDING);
    doAnswer(
            i ->
                Mono.just(
                    new PaymentProvider.Checkout(
                        "requires-action",
                        "https://fake.invalid/3ds",
                        phase.get(),
                        i.getArgument(4))))
        .when(provider)
        .chargeRecurring(any(), any(), any(), any(), any());
    doAnswer(
            i ->
                Mono.just(
                    new PaymentProvider.Payment(
                        "requires-action",
                        renewalInvoice,
                        new PaymentProvider.Money(2000, "EUR"),
                        phase.get())))
        .when(provider)
        .fetchPayment(any(), any());
    var pending = dispatch();
    assertEquals(PaymentAttemptStore.State.PENDING, pending.state());
    consent(false, "stop-while-pending", version());
    phase.set(PaymentProvider.State.SUCCEEDED);
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, dispatch().state());
    assertEquals(1, count("subscription_periods"));
    assertEquals(
        "DISABLED",
        operator
            .sql("SELECT autopay_state FROM taska.subscriptions WHERE tenant_id='" + A + "'")
            .map((r, m) -> r.get("autopay_state", String.class))
            .one()
            .block(WAIT));
    verify(provider, times(1)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void detectedOwnerOrFinancialRevocationIsDurableAndDoesNotInvalidateReplacement() {
    renewal();
    var original =
        operator
            .sql("SELECT id FROM taska.autopay_mandates WHERE state='ACTIVE'")
            .map((r, m) -> r.get("id", UUID.class))
            .one()
            .block(WAIT);
    long previous = version();
    barrier.set("financial-revocation");
    refusesDispatch();
    assertEquals(previous + 1, version());
    assertEquals(
        version(),
        operator
            .sql(
                "SELECT aggregate_version FROM taska.outbox_events WHERE"
                    + " event_type='AUTOPAY_INVALIDATED'")
            .map((r, m) -> r.get("aggregate_version", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0,
        operator
            .sql("SELECT count(*) n FROM taska.autopay_mandates WHERE state='ACTIVE'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        "SUSPENDED",
        operator
            .sql("SELECT autopay_state FROM taska.subscriptions WHERE tenant_id='" + A + "'")
            .map((r, m) -> r.get("autopay_state", String.class))
            .one()
            .block(WAIT));
    consent(true, "replacement", version());
    long replaced = version();
    worker(store.invalidate(A, original)).block(WAIT);
    assertEquals(replaced, version());
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, dispatch().state());
  }

  @Test
  void ownerChangingDuringPreparationRollsBackAttemptAndRevokesOnlyItsOldMandate() {
    renewal();
    var original = store;
    var checks = new AtomicInteger();
    store =
        new AutopayStore(
            fixture.f.store,
            (c, use, id) -> {
              if (use == AutopayDependencies.Use.DISPATCH && checks.incrementAndGet() == 2)
                ownerVersion.incrementAndGet();
              return original.policy.require(c, use, id);
            },
            registry,
            fixture.f.clock);
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    refusesDispatch();
    assertEquals(0, count("billing_operations"));
    assertEquals(
        0,
        operator
            .sql("SELECT count(*) n FROM taska.autopay_mandates WHERE state='ACTIVE'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    verify(provider, times(0)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void paidTenantCannotDispatchAnotherTenantsInvoiceEvenWithActiveConsent() {
    renewal();
    UUID proof =
        fixture.f.proof(B, SubscriptionIntegrationTest.NOW, Instant.parse("2026-02-28T12:34:56Z"));
    fixture.f.grant(B, proof, "paid-b").block(WAIT);
    UUID methodB = UUID.randomUUID();
    sql(
        "INSERT INTO"
            + " taska.payment_methods(id,tenant_id,owner_id,provider,provider_customer_ref,provider_method_ref,status,created_at)"
            + " VALUES('"
            + methodB
            + "','"
            + B
            + "','"
            + SubscriptionIntegrationTest.OWNER_B
            + "','OLD','customer-b','method-b','ACTIVE',now())");
    long vb =
        operator
            .sql("SELECT version FROM taska.subscriptions WHERE tenant_id='" + B + "'")
            .map((r, m) -> r.get("version", Long.class))
            .one()
            .block(WAIT);
    var q = request(true, "consent-b", vb).toBuilder().setTenantId(B.toString()).build();
    q =
        q.toBuilder()
            .setPayload(q.getPayload().toBuilder().setPaymentMethodId(methodB.toString()))
            .build();
    RegistryTestContext.withClaims(
            fixture.f.human(SubscriptionIntegrationTest.OWNER_B, B, "setAutopayConsent").toBuilder()
                .addScopes("write")
                .build(),
            store.consent(q))
        .block(WAIT);
    var context =
        fixture.f.worker(B, "dispatchAutopay", "billing-service").toBuilder()
            .addScopes("payment:recurring")
            .build();
    var error =
        assertThrows(
            DomainException.class,
            () ->
                RegistryTestContext.withClaims(context, coordinator.dispatch(B, renewalInvoice))
                    .block(WAIT));
    assertEquals(DomainStatus.FAILED_PRECONDITION, error.getStatus());
    assertEquals(0, count("payment_attempts"));
  }

  @Test
  void ownerChangingWhileProviderCapabilitiesReturnCannotCrossFinalClaim() {
    renewal();
    var capabilityCalls = new AtomicInteger();
    doAnswer(
            i -> {
              if (capabilityCalls.incrementAndGet() == 2) ownerVersion.incrementAndGet();
              return Mono.just(
                  new PaymentProvider.Capabilities(true, true, true, false, true, true));
            })
        .when(provider)
        .capabilities(any());
    refusesDispatch();
    assertEquals(0, count("billing_operations"));
    assertEquals(2, capabilityCalls.get());
    verify(provider, times(0)).chargeRecurring(any(), any(), any(), any(), any());
  }

  @Test
  void receiptKeysAreActorScopedAndOldOwnerCannotReadWarmReceipt() {
    var original = request(true, "same-owner-key", 1);
    var first = as(store.consent(original)).block(WAIT);
    fixture.f.owners.put(A.toString(), OTHER.toString());
    fixture.f.active.add(OTHER.toString());
    ownerVersion.incrementAndGet();
    UUID newMethod = UUID.randomUUID();
    sql(
        "INSERT INTO"
            + " taska.payment_methods(id,tenant_id,owner_id,provider,provider_customer_ref,provider_method_ref,status,created_at)"
            + " VALUES('"
            + newMethod
            + "','"
            + A
            + "','"
            + OTHER
            + "','OLD','new-owner-customer','new-owner-method','ACTIVE',now())");
    var q = request(true, "same-owner-key", version());
    q =
        q.toBuilder()
            .setPayload(q.getPayload().toBuilder().setPaymentMethodId(newMethod.toString()))
            .build();
    var next =
        RegistryTestContext.withClaims(
                fixture.f.human(OTHER, A, "setAutopayConsent").toBuilder()
                    .addScopes("write")
                    .build(),
                store.consent(q))
            .block(WAIT);
    assertNotEquals(first.getId(), next.getId());
    assertEquals(2, count("idempotency_records"));
    denied(store.consent(original), DomainStatus.PERMISSION_DENIED);
  }

  @Test
  void unregisteredProviderFailsBeforeConsentWithoutInternalError() {
    var closed =
        new AutopayStore(
            fixture.f.store, store.policy, new PaymentProviderRegistry(Map.of()), fixture.f.clock);
    denied(closed.consent(request(true, "no-provider", 1)), DomainStatus.FAILED_PRECONDITION);
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
    assertEquals(0, count("idempotency_records"));
  }

  AutopayStore administrativeStore(boolean currentProof) {
    var original = store;
    return new AutopayStore(
        fixture.f.store,
        (c, use, id) ->
            original
                .policy
                .require(c, use, id)
                .map(
                    g ->
                        new Grant(
                            g.owner(),
                            g.ownerVersion(),
                            g.revocationBarrier(),
                            g.admissionAllowed(),
                            g.termsVersion(),
                            g.termsSha256(),
                            g.method(),
                            g.termsText(),
                            g.configurationVersion(),
                            currentProof)),
        registry,
        fixture.f.clock);
  }

  ru.taska.api.b2b.v1.TrustedTenantContext administrator(
      UUID scopedTenant, ru.taska.api.b2b.v1.AdminGrant.LevelValue level) {
    return fixture.f.human(SubscriptionIntegrationTest.ADMIN, A, "setAutopayConsent").toBuilder()
        .setCredentialKind(ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
        .clearSessionId()
        .clearMembershipGeneration()
        .setCredentialId(UUID.randomUUID().toString())
        .setGrantId(UUID.randomUUID().toString())
        .setGrantGeneration("1")
        .setAdministrativeLevel(level)
        .setAdministrativeScope(
            ru.taska.api.b2b.v1.AdminScope.newBuilder()
                .setKind(ru.taska.api.b2b.v1.AdminScope.KindValue.KIND_TENANT_SET)
                .addTenantIds(scopedTenant.toString()))
        .addScopes("write")
        .build();
  }

  @Test
  void scopedAdminPreservesRealActorAndWritesPrivateAuditWithoutGrantingWorkerBypass() {
    renewal();
    enabled.set(false);
    store = administrativeStore(true);
    var q = request(true, "admin-consent", version());
    var claims = administrator(A, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY);
    var result = RegistryTestContext.withClaims(claims, store.consent(q)).block(WAIT);
    assertEquals(OWNER_A.toString(), result.getOwnerId());
    assertEquals(
        SubscriptionIntegrationTest.ADMIN.toString(), result.getConsentSnapshot().getActorUserId());
    assertEquals(
        result,
        RegistryTestContext.withClaims(
                administrator(A, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY),
                store.consent(q))
            .block(WAIT));
    assertEquals(2, count("autopay_authorization_audit"));
    assertEquals(
        SubscriptionIntegrationTest.ADMIN,
        operator
            .sql("SELECT actor_id FROM taska.autopay_authorization_audit LIMIT 1")
            .map((r, m) -> r.get("actor_id", UUID.class))
            .one()
            .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            as(fixture.f.transactions.execute(
                    db ->
                        db.sql("SELECT actor_id FROM taska.autopay_authorization_audit")
                            .map((r, m) -> r.get("actor_id", UUID.class))
                            .all()
                            .collectList()))
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            as(fixture.f.transactions.execute(
                    db ->
                        db.sql("DELETE FROM taska.autopay_authorization_audit")
                            .fetch()
                            .rowsUpdated()))
                .block(WAIT));
    coordinator = new AutopayCoordinator(store, attempts, operations, a -> Mono.empty());
    refusesDispatch();
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void adminNeedsCurrentPrimaryProofModifyLevelAndMatchingExplicitScope() {
    var q = request(true, "bad-admin", 1);
    var admin = administrativeStore(true);
    for (var claims :
        java.util.List.of(
            administrator(B, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY),
            administrator(A, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_READ))) {
      assertEquals(
          DomainStatus.PERMISSION_DENIED,
          assertThrows(
                  DomainException.class,
                  () -> RegistryTestContext.withClaims(claims, admin.consent(q)).block(WAIT))
              .getStatus());
    }
    var noProof = administrativeStore(false);
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    RegistryTestContext.withClaims(
                            administrator(
                                A, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY),
                            noProof.consent(q))
                        .block(WAIT))
            .getStatus());
    denied(admin.consent(q), DomainStatus.PERMISSION_DENIED);
    assertEquals(0, count("autopay_mandates"));
    assertEquals(0, count("autopay_authorization_audit"));
    assertEquals(1, version());
  }

  @Test
  void administrativeAuditFailureRollsBackMandateVersionReceiptAndOutbox() {
    var admin = administrativeStore(true);
    sql(
        "CREATE FUNCTION taska.fail_admin_autopay_test() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'fixture'; END $$; CREATE TRIGGER fail_admin_autopay_test"
            + " BEFORE INSERT ON taska.autopay_authorization_audit FOR EACH ROW EXECUTE FUNCTION"
            + " taska.fail_admin_autopay_test()");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              RegistryTestContext.withClaims(
                      administrator(A, ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY),
                      admin.consent(request(true, "admin-failed", 1)))
                  .block(WAIT));
    } finally {
      sql(
          "DROP TRIGGER fail_admin_autopay_test ON taska.autopay_authorization_audit; DROP FUNCTION"
              + " taska.fail_admin_autopay_test()");
    }
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
    assertEquals(0, count("idempotency_records"));
    assertEquals(0, count("autopay_authorization_audit"));
  }

  @Test
  void consentCannotUseOwnerProofThatChangedDuringProviderReadiness() {
    doAnswer(
            i -> {
              ownerVersion.incrementAndGet();
              return Mono.just(
                  new PaymentProvider.Capabilities(true, true, true, false, true, true));
            })
        .when(provider)
        .capabilities(any());
    denied(store.consent(request(true, "changed-consent-owner", 1)), DomainStatus.ABORTED);
    assertEquals(1, version());
    assertEquals(0, count("autopay_mandates"));
    assertEquals(0, count("idempotency_records"));
  }
}
