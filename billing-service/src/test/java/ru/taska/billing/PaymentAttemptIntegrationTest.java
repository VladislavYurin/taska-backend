package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.billing.payment.*;

@Testcontainers
@Timeout(90)
class PaymentAttemptIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final InvoiceIntegrationTest f = new InvoiceIntegrationTest();
  PaymentAttemptStore store;
  BillingOperationStore operations;
  FakePaymentProvider fake;
  PaymentProvider.Invoice invoice;
  PaymentProvider.Merchant merchant =
      new PaymentProvider.Merchant("merchant-A", PaymentProvider.Environment.TEST);
  AtomicInteger mapped;

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
    f.resetInvoices();
    invoice =
        new PaymentProvider.Invoice(
            UUID.fromString(f.create("payment-review").block(WAIT).getId()),
            new PaymentProvider.Money(2000, "EUR"));
    store = new PaymentAttemptStore(f.f.transactions, f.f.clock);
    operations = new BillingOperationStore(f.f.transactions, f.f.clock);
    fake = spy(new FakePaymentProvider(new byte[32], f.f.clock));
    mapped = new AtomicInteger();
  }

  @AfterEach
  void noLeak() {
    f.f.scopeDoesNotLeak();
  }

  <T> T as(Mono<T> work) {
    return f.as("createCheckout", work).block(WAIT);
  }

  PaymentAttemptStore.Request request(String key, String snapshot) {
    return new PaymentAttemptStore.Request(
        A, invoice.invoiceId(), "OLD", key, merchant.accountId(), merchant.environment(), snapshot);
  }

  PaymentProviderRegistry.Registration reg(PaymentProvider provider) {
    return new PaymentProviderRegistry.Registration(provider, Set.of("v1"));
  }

  PaymentAttemptCoordinator coordinator(
      PaymentProvider provider, PaymentAttemptCoordinator.Mapping mapping) {
    return new PaymentAttemptCoordinator(
        store,
        new PaymentProviderRegistry(
            Map.of(new PaymentProviderRegistry.Identity("OLD", merchant), reg(provider))),
        operations,
        mapping);
  }

  PaymentAttemptCoordinator coordinator() {
    return coordinator(fake, a -> Mono.fromRunnable(mapped::incrementAndGet));
  }

  PaymentAttemptStore.Attempt create(String key, String snapshot) {
    return as(coordinator().create(request(key, snapshot), merchant, invoice));
  }

  void reason(ProviderException.Reason reason, org.junit.jupiter.api.function.Executable work) {
    assertEquals(reason, assertThrows(ProviderException.class, work).reason());
  }

  BillingOperationStore.Operation operation(String key) {
    return as(
        operations.accept(
            new BillingOperationStore.Request(A, "PAYMENT", key, new byte[32], "PSP", null, null)));
  }

  PaymentAttemptStore.Attempt accepted(String key) {
    return as(store.accept(request(key, "{}"), operation(key).id(), invoice));
  }

  @Test
  void compactFirstCreateAndEquivalentJsonReplayHaveOneOperationAndObject() {
    var first = create("json", "{\"amountMinor\":2000,\"currency\":\"EUR\"}");
    var replay = create("json", "{ \"currency\": \"EUR\", \"amountMinor\": 2000 }");
    assertEquals(first.id(), replay.id());
    assertTrue(first.invoiceSnapshot().contains("pricing"));
    assertTrue(first.invoiceSnapshot().contains("document"));
    assertTrue(first.invoiceSnapshot().contains(invoice.invoiceId().toString()));
    assertEquals(first.operationId(), replay.operationId());
    assertEquals(first.providerPaymentId(), replay.providerPaymentId());
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
    reason(
        ProviderException.Reason.IDEMPOTENCY_CONFLICT,
        () -> create("json", "{\"amountMinor\":2001,\"currency\":\"EUR\"}"));
  }

  @Test
  void merchantEnvironmentAndInvoiceChangesAreRejectedBeforeExternalEffect() {
    create("bound", "{}");
    reason(
        ProviderException.Reason.IDEMPOTENCY_CONFLICT,
        () ->
            as(
                coordinator()
                    .create(
                        request("bound", "{}"),
                        new PaymentProvider.Merchant(
                            "merchant-B", PaymentProvider.Environment.TEST),
                        invoice)));
    reason(
        ProviderException.Reason.IDEMPOTENCY_CONFLICT,
        () ->
            as(
                coordinator()
                    .create(
                        request("bound", "{}"),
                        new PaymentProvider.Merchant(
                            "merchant-A", PaymentProvider.Environment.LIVE),
                        invoice)));
    reason(
        ProviderException.Reason.IDEMPOTENCY_CONFLICT,
        () ->
            as(
                coordinator()
                    .create(
                        request("bound", "{}"),
                        merchant,
                        new PaymentProvider.Invoice(UUID.randomUUID(), invoice.total()))));
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
  }

  @RepeatedTest(3)
  void concurrentReplayClaimsExactlyOneProviderCall() {
    var all =
        f.as(
                "createCheckout",
                Flux.range(0, 12)
                    .flatMap(
                        i ->
                            coordinator()
                                .create(request("parallel", "{}"), merchant, invoice)
                                .subscribeOn(Schedulers.parallel()),
                        12)
                    .collectList())
            .block(WAIT);
    assertEquals(1, all.stream().map(PaymentAttemptStore.Attempt::id).distinct().count());
    assertEquals(1, all.stream().map(PaymentAttemptStore.Attempt::operationId).distinct().count());
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
    assertEquals(
        PaymentAttemptStore.State.SUCCEEDED, as(store.find(A, all.getFirst().id())).state());
  }

  @Test
  void differentKeysCannotStartTwoActiveChargesForOneInvoice() {
    var outcomes =
        f.as(
                "createCheckout",
                Flux.fromIterable(List.of("one", "two"))
                    .flatMap(
                        key ->
                            coordinator()
                                .create(request(key, "{}"), merchant, invoice)
                                .materialize()
                                .subscribeOn(Schedulers.parallel()),
                        2)
                    .collectList())
            .block(WAIT);
    assertEquals(1, outcomes.stream().filter(s -> s.isOnNext()).count());
    assertEquals(1, outcomes.stream().filter(s -> s.isOnError()).count());
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
  }

  @Test
  void lateUnknownCannotRegressSuccessOrEraseProviderIdentity() {
    var claimed = as(store.claim(accepted("late")));
    var paid = as(store.mark(claimed, PaymentAttemptStore.State.SUCCEEDED, "original-id"));
    assertEquals(paid, as(store.unknown(claimed)));
    assertEquals(paid, as(store.unknown(paid)));
    reason(
        ProviderException.Reason.INVALID_STATE,
        () -> as(store.mark(paid, PaymentAttemptStore.State.SUCCEEDED, "other-id")));
    assertEquals(paid, as(store.find(A, paid.id())));
  }

  @Test
  void unknownAfterProviderKeyExpiryAndProcessRestartNeverCreatesAgain() {
    fake.nextPayment(FakePaymentProvider.Scenario.TIMEOUT_ACCEPTED);
    var unknown = create("expired-key", "{}");
    assertEquals(PaymentAttemptStore.State.UNKNOWN, unknown.state());
    assertNull(unknown.providerPaymentId());
    f.f.time.set(f.f.time.get().plus(Duration.ofDays(100)));
    // A fresh fake models an expired provider cache: another create would allocate a new object.
    var expiredCache = spy(new FakePaymentProvider(new byte[32], f.f.clock));
    var restarted =
        new PaymentAttemptCoordinator(
            new PaymentAttemptStore(f.f.transactions, f.f.clock),
            new PaymentProviderRegistry(
                Map.of(new PaymentProviderRegistry.Identity("OLD", merchant), reg(expiredCache))),
            new BillingOperationStore(f.f.transactions, f.f.clock),
            a -> Mono.empty());
    var replay = as(restarted.create(request("expired-key", "{}"), merchant, invoice));
    assertEquals(unknown.id(), replay.id());
    assertEquals(PaymentAttemptStore.State.UNKNOWN, replay.state());
    verifyNoInteractions(expiredCache);
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
  }

  @Test
  void crashAfterCommittedClaimDoesNotResubmitEvenIfNoResponseWasStored() {
    var provider = mock(PaymentProvider.class);
    var started = reactor.core.publisher.Sinks.<Void>empty();
    when(provider.createCheckout(any(), any(), any(), any()))
        .thenAnswer(
            call ->
                Mono.defer(
                    () -> {
                      started.tryEmitEmpty();
                      return Mono.never();
                    }));
    var subscription =
        f.as(
                "createCheckout",
                coordinator(provider, a -> Mono.empty())
                    .create(request("crash", "{}"), merchant, invoice))
            .subscribe();
    started.asMono().block(WAIT);
    subscription.dispose();
    UUID id =
        as(
            f.f.transactions.execute(
                db ->
                    db.sql(
                            "SELECT id FROM taska.payment_attempts WHERE tenant_id=:tenant AND"
                                + " provider_idempotency_key='crash'")
                        .bind("tenant", A)
                        .map((r, m) -> r.get("id", UUID.class))
                        .one()));
    var claimed = as(store.find(A, id));
    assertEquals(PaymentAttemptStore.State.SUBMITTED, claimed.state());
    var retry = create("crash", "{}");
    assertEquals(claimed.id(), retry.id());
    assertEquals(PaymentAttemptStore.State.SUBMITTED, retry.state());
    verifyNoInteractions(fake);
  }

  @Test
  void storedProviderAndMerchantChooseOriginalAdapterAfterSwitch() {
    var old = mock(PaymentProvider.class);
    var current = mock(PaymentProvider.class);
    when(old.createCheckout(any(), any(), any(), any()))
        .thenReturn(
            Mono.just(
                new PaymentProvider.Checkout(
                    "old-id", "https://fake.invalid", PaymentProvider.State.PENDING, "switch")));
    when(old.fetchPayment(merchant, "old-id"))
        .thenReturn(
            Mono.just(
                new PaymentProvider.Payment(
                    "old-id",
                    invoice.invoiceId(),
                    invoice.total(),
                    PaymentProvider.State.PENDING)));
    var pending =
        as(coordinator(old, a -> Mono.empty()).create(request("switch", "{}"), merchant, invoice));
    var registry =
        new PaymentProviderRegistry(
            Map.of(
                new PaymentProviderRegistry.Identity("OLD", merchant),
                reg(old),
                new PaymentProviderRegistry.Identity(
                    "NEW",
                    new PaymentProvider.Merchant("new-account", PaymentProvider.Environment.LIVE)),
                reg(current)));
    var switched = new PaymentAttemptCoordinator(store, registry, operations, a -> Mono.empty());
    assertEquals(pending.id(), as(switched.get(A, pending.id())).id());
    verify(old).fetchPayment(merchant, "old-id");
    verifyNoInteractions(current);
    var missing =
        new PaymentAttemptCoordinator(
            store,
            new PaymentProviderRegistry(
                Map.of(new PaymentProviderRegistry.Identity("NEW", merchant), reg(current))),
            operations,
            a -> Mono.empty());
    reason(ProviderException.Reason.UNSUPPORTED_CAPABILITY, () -> as(missing.get(A, pending.id())));
    verifyNoInteractions(current);
  }

  @Test
  void lostMappingAckReplaysOriginalIdentityWithoutAnotherCharge() {
    var first = new AtomicBoolean(true);
    var coordinator =
        coordinator(
            fake,
            a ->
                first.getAndSet(false)
                    ? Mono.error(new IllegalStateException("fixture lost ACK"))
                    : Mono.empty());
    assertThrows(
        IllegalStateException.class,
        () -> as(coordinator.create(request("mapping", "{}"), merchant, invoice)));
    var replay = as(coordinator.create(request("mapping", "{}"), merchant, invoice));
    assertNotNull(replay.providerPaymentId());
    assertEquals(PaymentAttemptStore.State.SUCCEEDED, replay.state());
    verify(fake, times(1)).createCheckout(any(), any(), any(), any());
  }

  @Test
  void businessOperationIdentityAndTerminalResultSurviveStaleWrites() {
    var initial = operation("operation");
    var done =
        as(operations.mark(initial, BillingOperationStore.State.SUCCEEDED, "{\"result\":1}"));
    var stale = as(operations.mark(initial, BillingOperationStore.State.UNKNOWN, null));
    assertEquals(done.state(), stale.state());
    assertEquals(done.fence(), stale.fence());
    assertEquals(done.result(), stale.result());
    assertArrayEquals(done.requestHash(), stale.requestHash());
    var hash = new byte[32];
    hash[0] = 1;
    reason(
        ProviderException.Reason.IDEMPOTENCY_CONFLICT,
        () ->
            as(
                operations.accept(
                    new BillingOperationStore.Request(
                        A, "PAYMENT", "operation", hash, "PSP", null, null))));
  }

  @Test
  void registeredWrongMerchantProfileIsRejectedBeforeAnyEffect() {
    var current = mock(PaymentProvider.class);
    var other = new PaymentProvider.Merchant("new-merchant", PaymentProvider.Environment.LIVE);
    var registry =
        new PaymentProviderRegistry(
            Map.of(
                new PaymentProviderRegistry.Identity("NEW", other),
                new PaymentProviderRegistry.Registration(current, Set.of("v2"))));
    var wrong =
        new PaymentAttemptStore.Request(
            A,
            invoice.invoiceId(),
            "NEW",
            "profile-mismatch",
            other.accountId(),
            other.environment(),
            "{}");
    var coordinator = new PaymentAttemptCoordinator(store, registry, operations, a -> Mono.empty());
    reason(
        ProviderException.Reason.UNSUPPORTED_CAPABILITY,
        () -> as(coordinator.create(wrong, other, invoice)));
    verifyNoInteractions(current);
    long count =
        as(
            f.f.transactions.execute(
                db ->
                    db.sql("SELECT count(*) FROM taska.payment_attempts WHERE tenant_id=:tenant")
                        .bind("tenant", A)
                        .map((r, m) -> r.get(0, Long.class))
                        .one()));
    assertEquals(0, count);
  }

  @Test
  void databaseRetainsImmutableIdentityAndUniqueProviderObjects() {
    var paid = create("immutable", "{}");
    assertThrows(
        Exception.class,
        () ->
            SubscriptionIntegrationTest.sql(
                "UPDATE taska.payment_attempts SET provider_payment_id=NULL WHERE id='"
                    + paid.id()
                    + "'"));
    assertThrows(
        Exception.class,
        () ->
            SubscriptionIntegrationTest.sql(
                "UPDATE taska.payment_attempts SET merchant_account='other' WHERE id='"
                    + paid.id()
                    + "'"));
    assertThrows(
        Exception.class,
        () ->
            SubscriptionIntegrationTest.sql(
                "UPDATE taska.payment_attempts SET state='UNKNOWN',version=version+1 WHERE id='"
                    + paid.id()
                    + "'"));
    assertEquals(paid, as(store.find(A, paid.id())));
  }

  @Test
  void foreignTenantCannotReadAttempt() {
    var paid = create("foreign", "{}");
    reason(ProviderException.Reason.NOT_FOUND, () -> as(store.find(B, paid.id())));
    assertEquals(paid, as(store.find(A, paid.id())));
  }
}
