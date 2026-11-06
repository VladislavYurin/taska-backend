package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.ingress.*;
import ru.taska.billing.payment.FakePaymentProvider;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class ProviderInboxIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final PaymentSettlementIntegrationTest base = new PaymentSettlementIntegrationTest();
  ProviderInboxStore store;
  ProviderInbox inbox;
  ProviderWebhookRegistry registry;
  FakePaymentProvider fake;
  ProviderWebhookRegistry.Identity identity;
  PaymentProvider.Checkout payment;
  FakePaymentProvider.SignedWebhook webhook;
  AtomicInteger applied;

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
    base.reset();
    applied = new AtomicInteger();
    fake = new FakePaymentProvider(new byte[32], base.f.f.clock);
    identity =
        new ProviderWebhookRegistry.Identity(
            "TEST_PSP", "test-account", PaymentProvider.Environment.TEST);
    registry =
        new ProviderWebhookRegistry(
            List.of(
                new ProviderWebhookRegistry.Registration(
                    identity, Set.of("v1"), List.of(ProviderWebhookRegistry.adapter(fake)))));
    store =
        new ProviderInboxStore(
            db,
            tx,
            base.f.f.transactions,
            new InboxCipher("k1", Map.of("k1", new byte[32]), new SecureRandom()),
            base.f.f.clock);
    var ledger =
        new FinancialLedger(
            base.f.f.transactions,
            base.f.f.readers,
            (d, t, e) -> Mono.empty(),
            (d, t, i, m) -> Mono.empty(),
            base.f.f.clock);
    base.settlement =
        new PaymentSettlement(
            base.f.f.store,
            ledger,
            new ProviderPaymentEvidence(),
            t -> Mono.just(base.root.get()),
            (t, d) -> {
              base.deliveries.incrementAndGet();
              return base.loseDelivery.get()
                  ? Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "ROOT_UNAVAILABLE"))
                  : Mono.just(
                      TenantInitialPaymentResult.newBuilder()
                          .setTenant(Tenant.newBuilder().setId(t.toString()).setVersion("1"))
                          .setOutcome(TenantInitialPaymentResult.Outcome.OUTCOME_APPLIED)
                          .build());
            },
            base.f.f.clock);
    inbox =
        new ProviderInbox(
            store,
            registry,
            (t, e) -> {
              applied.incrementAndGet();
              return RegistryTestContext.withClaims(
                      worker(t, "applyVerifiedPayment", "billing-service", "ledger:write"),
                      base.settlement.apply(t, e))
                  .then();
            },
            1024,
            120);
    payment =
        fake.createCheckout(
                identity.merchant(),
                new PaymentProvider.Invoice(base.invoice, new PaymentProvider.Money(2000, "EUR")),
                UUID.randomUUID(),
                "checkout")
            .block(WAIT);
    webhook =
        fake.confirmPayment(
            identity.merchant(),
            payment.providerPaymentId(),
            PaymentProvider.State.SUCCEEDED,
            "evt-1");
  }

  @AfterEach
  void clean() {
    base.cleanScope();
  }

  TrustedTenantContext worker(UUID tenant, String action, String issuer, String scope) {
    var c =
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setActorGeneration("service-1")
            .setIssuerService(issuer)
            .setAudienceService("billing-service")
            .setAction(action)
            .addScopes("write")
            .addScopes(scope);
    if (tenant != null) c.setTenantId(tenant.toString());
    return c.build();
  }

  Mono<ProviderInboxStore.Receipt> receive(FakePaymentProvider.SignedWebhook signed) {
    return RegistryTestContext.withClaims(
        worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest"),
        inbox.receive(identity, signed.rawBody(), signed.signature()));
  }

  void bind() {
    RegistryTestContext.withClaims(
            worker(A, "registerProviderPayment", "billing-service", "inbox:map"),
            inbox.bind(A, base.invoice, identity, payment.providerPaymentId()))
        .block(WAIT);
  }

  Mono<ProviderInboxStore.Receipt> process(UUID id) {
    return RegistryTestContext.withClaims(
        worker(A, "processProviderWebhook", "billing-service", "inbox:process"),
        inbox.process(A, id));
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  void rejects(DomainStatus status, String code, Mono<?> work) {
    StepVerifier.create(work)
        .expectErrorMatches(
            e ->
                e instanceof DomainException d
                    && d.getStatus() == status
                    && d.getMessage().equals(code))
        .verify(WAIT);
  }

  @Test
  void durableAckPrecedesProcessingAndDifferentEventsShareOneFinancialFact() {
    var first = receive(webhook).block(WAIT);
    assertEquals("RECEIVED", first.state());
    assertEquals(0, applied.get());
    assertEquals(0, count("payment_facts"));
    assertEquals(first, receive(webhook).block(WAIT));
    assertEquals(1, count("provider_inbox"));
    assertTrue(store.pending(50).block(WAIT).isEmpty());
    bind();
    assertEquals(
        List.of(new ProviderInboxStore.Pending(first.id(), A)), store.pending(50).block(WAIT));
    var result = process(first.id()).block(WAIT);
    assertEquals("APPLIED", result.state());
    assertEquals(Invoice.StateValue.STATE_PAID, base.f.get(base.invoice.toString()).getState());
    var second =
        fake.confirmPayment(
            identity.merchant(),
            payment.providerPaymentId(),
            PaymentProvider.State.SUCCEEDED,
            "evt-2");
    var receipt = receive(second).block(WAIT);
    assertNotEquals(first.id(), receipt.id());
    assertEquals(result.evidenceId(), process(receipt.id()).block(WAIT).evidenceId());
    assertEquals(1, count("provider_payment_proofs"));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_settlements"));
    assertEquals(1, count("subscription_periods"));
    assertTrue(store.pending(50).block(WAIT).isEmpty());
    assertEquals("APPLIED", receive(webhook).block(WAIT).state());
  }

  @Test
  void forgedCrossMerchantAndWrongEnvironmentNeverEnterInboxOrSettlement() {
    byte[] forged = webhook.rawBody();
    forged[0] ^= 1;
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "INVALID_WEBHOOK",
        RegistryTestContext.withClaims(
            worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest"),
            inbox.receive(identity, forged, webhook.signature())));
    for (var i :
        List.of(
            new ProviderWebhookRegistry.Identity(
                "TEST_PSP", "foreign", PaymentProvider.Environment.TEST),
            new ProviderWebhookRegistry.Identity(
                "TEST_PSP", "test-account", PaymentProvider.Environment.LIVE))) {
      rejects(
          DomainStatus.PERMISSION_DENIED,
          "PROVIDER_NOT_CONFIGURED",
          RegistryTestContext.withClaims(
              worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest"),
              inbox.receive(i, webhook.rawBody(), webhook.signature())));
    }
    assertEquals(0, count("provider_inbox"));
    assertEquals(0, count("payment_facts"));
    assertEquals(0, applied.get());
  }

  @Test
  void contextCannotSupplyTenantOrBypassServiceScope() {
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "AUTHENTICATION_REQUIRED",
        inbox.receive(identity, webhook.rawBody(), webhook.signature()));
    var global = worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest");
    for (var c :
        List.of(
            global.toBuilder().setTenantId(B.toString()).build(),
            global.toBuilder().setActorUserId(OWNER_A.toString()).build(),
            global.toBuilder().setIssuerService("api-gateway").build(),
            global.toBuilder().clearScopes().build()))
      rejects(
          DomainStatus.PERMISSION_DENIED,
          "SCOPE_DENIED",
          RegistryTestContext.withClaims(
              c, inbox.receive(identity, webhook.rawBody(), webhook.signature())));
    assertEquals(0, count("provider_inbox"));
  }

  @Test
  void foreignTenantCannotBindInvoiceOrReadItsReceipt() {
    var r = receive(webhook).block(WAIT);
    bind();
    rejects(
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        RegistryTestContext.withClaims(
            worker(B, "registerProviderPayment", "billing-service", "inbox:map"),
            inbox.bind(B, base.invoice, identity, payment.providerPaymentId())));
    rejects(
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        RegistryTestContext.withClaims(
            worker(B, "processProviderWebhook", "billing-service", "inbox:process"),
            inbox.process(B, r.id())));
    assertEquals(1, count("provider_pay_routes"));
    assertEquals(0, count("provider_payment_proofs"));
    assertEquals(0, applied.get());
  }

  @Test
  void aDeferredCommitFailureDoesNotEmitAnAckAndProviderRetryRecovers() {
    sql(
        "CREATE FUNCTION taska.reject_inbox_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'fixture commit failure'; END $$");
    sql(
        "CREATE CONSTRAINT TRIGGER reject_inbox_commit AFTER INSERT ON taska.provider_inbox"
            + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION"
            + " taska.reject_inbox_commit()");
    var acks = new AtomicInteger();
    try {
      assertThrows(
          RuntimeException.class,
          () -> receive(webhook).doOnNext(r -> acks.incrementAndGet()).block(WAIT));
    } finally {
      sql("DROP TRIGGER reject_inbox_commit ON taska.provider_inbox");
      sql("DROP FUNCTION taska.reject_inbox_commit()");
    }
    assertEquals(0, acks.get());
    assertEquals(0, count("provider_inbox"));
    assertEquals(0, applied.get());
    assertNotNull(receive(webhook).block(WAIT));
    assertEquals(1, count("provider_inbox"));
  }

  @Test
  void rootResponseLossKeepsInboxRetryableWithoutAnotherFinancialEffect() {
    var r = receive(webhook).block(WAIT);
    bind();
    base.loseDelivery.set(true);
    rejects(DomainStatus.UNAVAILABLE, "ROOT_UNAVAILABLE", process(r.id()));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("payment_settlements"));
    assertEquals(1, store.pending(50).block(WAIT).size());
    base.loseDelivery.set(false);
    assertEquals("APPLIED", process(r.id()).block(WAIT).state());
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void parallelEventsAndRetriesCannotCreateDuplicateProofOrCoverage() {
    bind();
    var other =
        fake.confirmPayment(
            identity.merchant(),
            payment.providerPaymentId(),
            PaymentProvider.State.SUCCEEDED,
            "evt-parallel");
    var received =
        Flux.merge(
                receive(webhook).subscribeOn(Schedulers.parallel()),
                receive(other).subscribeOn(Schedulers.parallel()),
                receive(webhook).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    Flux.fromIterable(received)
        .flatMap(r -> process(r.id()).subscribeOn(Schedulers.parallel()), 3)
        .collectList()
        .block(WAIT);
    assertEquals(2, count("provider_inbox"));
    assertEquals(1, count("provider_payment_proofs"));
    assertEquals(1, count("payment_facts"));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void rawPayloadIsEncryptedAndOnlyTerminalOldPayloadsAreErased() {
    var r = receive(webhook).block(WAIT);
    bind();
    process(r.id()).block(WAIT);
    var pending =
        fake.confirmPayment(
            identity.merchant(),
            payment.providerPaymentId(),
            PaymentProvider.State.SUCCEEDED,
            "evt-retain");
    receive(pending).block(WAIT);
    assertEquals(2, count("provider_inbox"));
    var raw =
        operator
            .sql("SELECT payload_ciphertext FROM taska.provider_inbox WHERE id=:id")
            .bind("id", r.id())
            .map((row, m) -> row.get("payload_ciphertext", java.nio.ByteBuffer.class))
            .one()
            .block(WAIT);
    byte[] bytes = new byte[raw.remaining()];
    raw.get(bytes);
    assertFalse(Arrays.equals(webhook.rawBody(), bytes));
    store.verifyPayloadKeys().block(WAIT);
    byte[] replacement = new byte[32];
    replacement[0] = 1;
    var missingKey =
        new ProviderInboxStore(
            db,
            tx,
            base.f.f.transactions,
            new InboxCipher("k2", Map.of("k2", replacement), new SecureRandom()),
            base.f.f.clock);
    assertThrows(IllegalStateException.class, () -> missingKey.verifyPayloadKeys().block(WAIT));
    base.f.f.time.set(NOW.plusSeconds(31L * 86400));
    store.purge().block(WAIT);
    assertEquals(
        1L,
        operator
            .sql("SELECT count(*) n FROM taska.provider_inbox WHERE payload_ciphertext IS NOT NULL")
            .map((row, m) -> row.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(1, count("provider_payment_proofs"));
    assertEquals(1, count("payment_facts"));
    receive(webhook).block(WAIT);
    assertEquals(
        1L,
        operator
            .sql("SELECT count(*) n FROM taska.provider_inbox WHERE payload_ciphertext IS NOT NULL")
            .map((row, m) -> row.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void boundedBodyAndDurableRateApplyBeforeProviderWork() {
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "WEBHOOK_LIMIT_EXCEEDED",
        RegistryTestContext.withClaims(
            worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest"),
            inbox.receive(identity, new byte[1025], "signature")));
    var limited = new ProviderInbox(store, registry, (t, e) -> Mono.empty(), 1024, 1);
    var claims = worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest");
    RegistryTestContext.withClaims(
            claims, limited.receive(identity, webhook.rawBody(), webhook.signature()))
        .block(WAIT);
    rejects(
        DomainStatus.RESOURCE_EXHAUSTED,
        "WEBHOOK_RATE_LIMIT",
        RegistryTestContext.withClaims(
            claims, limited.receive(identity, webhook.rawBody(), webhook.signature())));
    assertEquals(1, count("provider_inbox"));
  }

  String signed(byte[] body, long at, byte[] key) {
    try {
      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
      mac.update((at + ":").getBytes(StandardCharsets.US_ASCII));
      return at + ":" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void authenticatedUnsupportedEventsAreIgnoredAndExpiredOrConflictingPayloadsAreRejected() {
    byte[] key = new byte[32];
    key[0] = 7;
    ProviderWebhookRegistry.Verifier verifier =
        (merchant, body, signature) ->
            Mono.defer(
                () -> {
                  try {
                    long at = Long.parseLong(signature.substring(0, signature.indexOf(':')));
                    if (Math.abs(base.f.f.clock.instant().getEpochSecond() - at) > 300
                        || !java.security.MessageDigest.isEqual(
                            signed(body, at, key).getBytes(StandardCharsets.US_ASCII),
                            signature.getBytes(StandardCharsets.US_ASCII)))
                      throw new IllegalArgumentException();
                    return Mono.just(
                        new ProviderWebhookRegistry.Observation(
                            "unknown-event", "provider.unsupported", null));
                  } catch (RuntimeException e) {
                    return Mono.error(
                        new ru.taska.billing.payment.ProviderException(
                            ru.taska.billing.payment.ProviderException.Reason.INVALID_WEBHOOK));
                  }
                });
    var unknownRegistry =
        new ProviderWebhookRegistry(
            List.of(
                new ProviderWebhookRegistry.Registration(
                    identity, Set.of("v1"), List.of(verifier))));
    var unknownInbox =
        new ProviderInbox(
            store,
            unknownRegistry,
            (t, e) -> Mono.error(new AssertionError("Unsupported event cannot settle")),
            1024,
            120);
    byte[] body = "authenticated unsupported fixture".getBytes(StandardCharsets.UTF_8);
    long at = base.f.f.clock.instant().getEpochSecond();
    var caller = worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest");
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "INVALID_WEBHOOK",
        RegistryTestContext.withClaims(
            caller, unknownInbox.receive(identity, body, signed(body, at - 301, key))));
    assertEquals(0, count("provider_inbox"));
    var first =
        RegistryTestContext.withClaims(
                caller, unknownInbox.receive(identity, body, signed(body, at, key)))
            .block(WAIT);
    assertEquals("IGNORED", first.state());
    assertEquals("UNSUPPORTED_EVENT", first.reason());
    assertEquals(
        first,
        RegistryTestContext.withClaims(
                caller, unknownInbox.receive(identity, body, signed(body, at, key)))
            .block(WAIT));
    byte[] changed = "changed authenticated fixture".getBytes(StandardCharsets.UTF_8);
    rejects(
        DomainStatus.ABORTED,
        "WEBHOOK_EVENT_CONFLICT",
        RegistryTestContext.withClaims(
            caller, unknownInbox.receive(identity, changed, signed(changed, at, key))));
    assertEquals(1, count("provider_inbox"));
    assertEquals(0, count("provider_payment_proofs"));
    assertEquals(0, count("payment_facts"));
  }

  @Test
  void rotatingVerificationKeysDoesNotInvalidateAlreadyAcceptedWork() {
    byte[] newKey = new byte[32];
    newKey[0] = 9;
    var next = new FakePaymentProvider(newKey, base.f.f.clock);
    var mixed =
        new ProviderWebhookRegistry(
            List.of(
                new ProviderWebhookRegistry.Registration(
                    identity,
                    Set.of("v1"),
                    List.of(
                        ProviderWebhookRegistry.adapter(next),
                        ProviderWebhookRegistry.adapter(fake)))));
    var mixedInbox = new ProviderInbox(store, mixed, (t, e) -> Mono.empty(), 1024, 120);
    var c = worker(null, "observeProviderWebhook", "payment-adapter", "inbox:ingest");
    var receipt =
        RegistryTestContext.withClaims(
                c, mixedInbox.receive(identity, webhook.rawBody(), webhook.signature()))
            .block(WAIT);
    var retired =
        new ProviderWebhookRegistry(
            List.of(
                new ProviderWebhookRegistry.Registration(
                    identity, Set.of("v1"), List.of(ProviderWebhookRegistry.adapter(next)))));
    var retiredInbox = new ProviderInbox(store, retired, (t, e) -> Mono.empty(), 1024, 120);
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "INVALID_WEBHOOK",
        RegistryTestContext.withClaims(
            c, retiredInbox.receive(identity, webhook.rawBody(), webhook.signature())));
    bind();
    assertEquals("APPLIED", process(receipt.id()).block(WAIT).state());
    assertEquals(1, count("payment_facts"));
  }

  @Test
  void wrongSignedInvoiceOrCurrencyIsQuarantinedBeforeSettlement() {
    for (int n = 0; n < 2; n++) {
      var checkout =
          fake.createCheckout(
                  identity.merchant(),
                  new PaymentProvider.Invoice(
                      n == 0 ? UUID.randomUUID() : base.invoice,
                      new PaymentProvider.Money(2000, n == 0 ? "EUR" : "USD")),
                  UUID.randomUUID(),
                  "mismatch-" + n)
              .block(WAIT);
      var event =
          fake.confirmPayment(
              identity.merchant(),
              checkout.providerPaymentId(),
              PaymentProvider.State.SUCCEEDED,
              "mismatch-event-" + n);
      var r = receive(event).block(WAIT);
      RegistryTestContext.withClaims(
              worker(A, "registerProviderPayment", "billing-service", "inbox:map"),
              inbox.bind(A, base.invoice, identity, checkout.providerPaymentId()))
          .block(WAIT);
      var result = process(r.id()).block(WAIT);
      assertEquals("QUARANTINED", result.state());
      assertNull(result.evidenceId());
    }
    assertEquals(0, count("provider_payment_proofs"));
    assertEquals(0, count("payment_facts"));
    assertEquals(0, applied.get());
  }

  @Test
  void runtimeCannotRewriteAuthenticatedPayloadOrImmutableSourceBinding() {
    var r = receive(webhook).block(WAIT);
    bind();
    var result = process(r.id()).block(WAIT);
    assertThrows(
        RuntimeException.class,
        () ->
            db.sql("UPDATE taska.provider_inbox SET observation='{}'::jsonb WHERE id=:id")
                .bind("id", r.id())
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            operator
                .sql("UPDATE taska.provider_pay_routes SET tenant_id=:tenant")
                .bind("tenant", B)
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            operator
                .sql("UPDATE taska.provider_payment_proofs SET amount_minor=1")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    var other =
        fake.createCheckout(
                identity.merchant(),
                new PaymentProvider.Invoice(base.invoice, new PaymentProvider.Money(2000, "EUR")),
                UUID.randomUUID(),
                "other-binding")
            .block(WAIT);
    RegistryTestContext.withClaims(
            worker(A, "registerProviderPayment", "billing-service", "inbox:map"),
            inbox.bind(A, base.invoice, identity, other.providerPaymentId()))
        .block(WAIT);
    assertThrows(
        RuntimeException.class,
        () ->
            operator
                .sql(
                    "INSERT INTO taska.provider_payment_proofs SELECT"
                        + " (jsonb_populate_record(NULL::taska.provider_payment_proofs,"
                        + " to_jsonb(p)||jsonb_build_object('id',CAST(:id AS"
                        + " text),'provider_payment_id',CAST(:payment AS"
                        + " text),'merchant_profile_version','wrong-profile'))).* FROM"
                        + " taska.provider_payment_proofs p LIMIT 1")
                .bind("id", UUID.randomUUID())
                .bind("payment", other.providerPaymentId())
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            operator
                .sql(
                    "INSERT INTO taska.provider_inbox SELECT"
                        + " (jsonb_populate_record(NULL::taska.provider_inbox,"
                        + " to_jsonb(p)||jsonb_build_object('id',CAST(:id AS"
                        + " text),'event_id','wrong-tenant-evidence','assigned_tenant_id',CAST(:tenant"
                        + " AS text)))).* FROM taska.provider_inbox p WHERE p.id=:source")
                .bind("id", UUID.randomUUID())
                .bind("tenant", B)
                .bind("source", r.id())
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(result.evidenceId(), process(r.id()).block(WAIT).evidenceId());
    assertEquals(1, count("payment_facts"));
  }

  @Test
  void unmappedBacklogNeverOccupiesTheMappedWorkBatch() {
    for (int n = 0; n < 60; n++) {
      var unknown =
          fake.createCheckout(
                  identity.merchant(),
                  new PaymentProvider.Invoice(base.invoice, new PaymentProvider.Money(2000, "EUR")),
                  UUID.randomUUID(),
                  "unmapped-" + n)
              .block(WAIT);
      receive(
              fake.confirmPayment(
                  identity.merchant(),
                  unknown.providerPaymentId(),
                  PaymentProvider.State.SUCCEEDED,
                  "unmapped-event-" + n))
          .block(WAIT);
    }
    base.f.f.time.set(NOW.plusSeconds(1));
    var mapped = receive(webhook).block(WAIT);
    bind();
    assertEquals(
        List.of(new ProviderInboxStore.Pending(mapped.id(), A)), store.pending(50).block(WAIT));
    assertEquals(61, count("provider_inbox"));
  }

  @Test
  void retryBackoffLetsOtherMappedPaymentsProgressAndReturnsDueWork() {
    bind();
    receive(webhook).block(WAIT);
    receive(
            fake.confirmPayment(
                identity.merchant(),
                payment.providerPaymentId(),
                PaymentProvider.State.SUCCEEDED,
                "evt-retry-2"))
        .block(WAIT);
    receive(
            fake.confirmPayment(
                identity.merchant(),
                payment.providerPaymentId(),
                PaymentProvider.State.SUCCEEDED,
                "evt-retry-3"))
        .block(WAIT);
    var first = store.pending(1).block(WAIT).get(0);
    store.retry(first.receiptId()).block(WAIT);
    assertNotEquals(first.receiptId(), store.pending(1).block(WAIT).get(0).receiptId());
    assertEquals(2, store.pending(50).block(WAIT).size());
    base.f.f.time.set(NOW.plusSeconds(6));
    assertTrue(store.pending(50).block(WAIT).contains(first));
  }
}
