package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.billing.FinancialLedgerDependencies.*;
import ru.taska.billing.FinancialLedgerDependencies.Currency;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class FinancialLedgerIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final InvoiceIntegrationTest f = new InvoiceIntegrationTest();
  FinancialLedger ledger;
  Map<UUID, Movement> proofs;
  AtomicBoolean eligible;
  UUID invoice;

  @BeforeAll
  static void setupLedger() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void closeLedger() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void resetLedger() {
    f.resetInvoices();
    invoice = UUID.fromString(f.create("ledger-invoice").block(WAIT).getId());
    proofs = new ConcurrentHashMap<>();
    eligible = new AtomicBoolean(true);
    ledger =
        new FinancialLedger(
            f.f.transactions,
            f.f.readers,
            (db, t, id) -> Mono.justOrEmpty(proofs.get(id)),
            (db, t, i, ids) ->
                eligible.get()
                    ? Mono.just(new AllocationDecision(ids, "FULL_TIMELY_VERIFIED"))
                    : Mono.empty(),
            f.f.clock);
  }

  @AfterEach
  void noLeak() {
    f.f.scopeDoesNotLeak();
  }

  Movement movement(
      Kind kind,
      UUID parent,
      UUID allocation,
      long amount,
      Currency currency,
      Method method,
      String external) {
    return new Movement(
        A,
        new Source("TEST_PROVIDER", "test-merchant", Environment.SANDBOX, external, method),
        kind,
        parent,
        allocation,
        amount,
        currency,
        new Occurrence(Precision.EXACT, NOW.minusSeconds(120), null, null),
        NOW.minusSeconds(60),
        NOW,
        method == Method.WIRE ? ADMIN : null,
        method == Method.WIRE ? "opaque-payer" : null,
        "receipt/test-evidence",
        "ab".repeat(32));
  }

  UUID proof(Movement movement) {
    UUID id = UUID.randomUUID();
    proofs.put(id, movement);
    return id;
  }

  <T> Mono<T> worker(String action, Mono<T> work) {
    return RegistryTestContext.withClaims(
        f.f.worker(A, action, "billing-service").toBuilder().addScopes("ledger:write").build(),
        work);
  }

  Mono<FinancialLedger.Fact> record(UUID proof, String key) {
    return worker("recordFinancialFact", ledger.record(A, proof, key));
  }

  FinancialLedger.Fact payment(long amount, String external) {
    return record(
            proof(movement(Kind.PAYMENT, null, null, amount, Currency.EUR, Method.PSP, external)),
            external)
        .block(WAIT);
  }

  Mono<FinancialLedger.Match> match(UUID payment, String key) {
    return worker("matchInvoicePayment", ledger.match(A, payment, invoice, key));
  }

  Mono<List<FinancialLedger.Allocation>> allocate(List<UUID> matches, String key) {
    return worker("allocateInvoicePayments", ledger.allocate(A, invoice, matches, key));
  }

  FinancialLedger.Balance balance(UUID id) {
    return RegistryTestContext.withClaims(f.human("getFinancialBalance"), ledger.balance(A, id))
        .block(WAIT);
  }

  long count(String table) {
    return f.count(table);
  }

  void rejects(DomainStatus status, String code, Mono<?> work) {
    var e = assertThrows(DomainException.class, () -> work.block(WAIT));
    assertEquals(status, e.getStatus());
    assertEquals(code, e.getDetails().get("code"));
  }

  void conserves(FinancialLedger.Balance b) {
    assertEquals(
        b.received(),
        Math.addExact(
            Math.addExact(b.netAllocated(), b.unallocatedRemaining()), b.confirmedNetReturned()));
  }

  @Test
  void concurrentFactsAndBothIdempotencyKeysNeverDuplicateMoney() {
    Movement m = movement(Kind.PAYMENT, null, null, 2500, Currency.EUR, Method.PSP, "external-one");
    UUID proof = proof(m);
    var facts =
        Flux.range(0, 12)
            .flatMap(i -> record(proof, "delivery-" + i).subscribeOn(Schedulers.parallel()), 6)
            .collectList()
            .block(WAIT);
    assertTrue(facts.stream().allMatch(facts.getFirst()::equals));
    assertEquals(1, count("payment_facts"));
    assertEquals(facts.getFirst(), record(proof(m), "other-evidence").block(WAIT));
    UUID changed =
        proof(movement(Kind.PAYMENT, null, null, 2600, Currency.EUR, Method.PSP, "external-one"));
    rejects(DomainStatus.ALREADY_EXISTS, "FINANCIAL_FACT_CONFLICT", record(changed, "new-key"));
    rejects(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT", record(changed, "delivery-0"));
    assertEquals(1, count("payment_facts"));
    assertEquals(2500, balance(facts.getFirst().id()).unallocatedRemaining());
  }

  @Test
  void overpaymentAndItsReturnPreserveOriginalAllocationAndSubscription() {
    var source = payment(2500, "overpay");
    var match = match(source.id(), "match").block(WAIT);
    assertEquals(2000, match.amountMinor());
    var pending = balance(source.id());
    assertEquals(0, pending.netAllocated());
    assertEquals(2500, pending.unallocatedRemaining());
    assertEquals(2000, pending.matchedUnallocated());
    conserves(pending);
    var allocations = allocate(List.of(match.id()), "allocate").block(WAIT);
    assertEquals(1, allocations.size());
    assertEquals(2000, allocations.getFirst().amountMinor());
    var covered = balance(source.id());
    assertEquals(2000, covered.netAllocated());
    assertEquals(500, covered.unallocatedRemaining());
    assertEquals(0, covered.matchedUnallocated());
    conserves(covered);
    UUID refund =
        proof(
            movement(
                Kind.REFUND, source.id(), null, 500, Currency.EUR, Method.PSP, "return-excess"));
    var returned = record(refund, "refund").block(WAIT);
    assertEquals(returned, record(refund, "refund-replay").block(WAIT));
    var after = balance(source.id());
    assertEquals(2000, after.netAllocated());
    assertEquals(0, after.unallocatedRemaining());
    assertEquals(500, after.confirmedNetReturned());
    conserves(after);
    assertEquals(1, count("payment_allocations"));
    assertEquals(0, count("subscription_periods"));
    assertEquals("1", f.f.get(OWNER_A, A).getVersion());
    assertEquals(2, count("payment_facts"));
    assertEquals("2000", f.get(invoice.toString()).getTotal().getAmountMinor());
    assertThrows(
        Exception.class,
        () ->
            record(
                    proof(
                        movement(
                            Kind.REFUND,
                            source.id(),
                            null,
                            1,
                            Currency.EUR,
                            Method.PSP,
                            "too-much-return")),
                    "too-much")
                .block(WAIT));
    assertEquals(after, balance(source.id()));
    assertEquals(2, count("payment_facts"));
  }

  @Test
  void partialReceiptsStayUnallocatedUntilFullQualifiedBatch() {
    var a = payment(1200, "part-a");
    var ma = match(a.id(), "match-a").block(WAIT);
    rejects(
        DomainStatus.FAILED_PRECONDITION, "PAYMENT_PENDING", allocate(List.of(ma.id()), "partial"));
    assertEquals(0, count("payment_allocations"));
    assertEquals(1200, balance(a.id()).matchedUnallocated());
    var b = payment(800, "part-b");
    var mb = match(b.id(), "match-b").block(WAIT);
    eligible.set(false);
    rejects(
        DomainStatus.FAILED_PRECONDITION,
        "PAYMENT_PENDING",
        allocate(List.of(ma.id(), mb.id()), "full"));
    assertEquals(0, count("payment_allocations"));
    eligible.set(true);
    var result = allocate(List.of(ma.id(), mb.id()), "full").block(WAIT);
    assertEquals(2, result.size());
    long allocatedEvents = count("outbox_events");
    assertEquals(2000, result.stream().mapToLong(FinancialLedger.Allocation::amountMinor).sum());
    assertEquals(
        new HashSet<>(result),
        new HashSet<>(allocate(List.of(mb.id(), ma.id()), "full").block(WAIT)));
    assertEquals(
        new HashSet<>(result),
        new HashSet<>(allocate(List.of(ma.id(), mb.id()), "duplicate").block(WAIT)));
    assertEquals(allocatedEvents, count("outbox_events"));
    assertEquals(2, count("payment_allocations"));
    conserves(balance(a.id()));
    conserves(balance(b.id()));
  }

  @Test
  void parallelAllocationWorkersCommitOneBatch() {
    var a = payment(1200, "a");
    var b = payment(800, "b");
    var ma = match(a.id(), "a").block(WAIT);
    var mb = match(b.id(), "b").block(WAIT);
    var results =
        Flux.range(0, 8)
            .flatMap(
                i ->
                    allocate(List.of(ma.id(), mb.id()), "allocation-" + i)
                        .subscribeOn(Schedulers.parallel()),
                4)
            .collectList()
            .block(WAIT);
    assertTrue(results.stream().allMatch(results.getFirst()::equals));
    assertEquals(2, count("payment_allocations"));
    assertEquals(1200, balance(a.id()).netAllocated());
    assertEquals(800, balance(b.id()).netAllocated());
  }

  @Test
  void pspAndWireRaceCannotCoverInvoiceTwice() {
    var psp = payment(2000, "psp");
    var wire =
        record(
                proof(movement(Kind.PAYMENT, null, null, 2000, Currency.EUR, Method.WIRE, "wire")),
                "wire")
            .block(WAIT);
    var results =
        Flux.just(psp.id(), wire.id())
            .flatMap(
                id ->
                    match(id, id.toString())
                        .map(Optional::of)
                        .onErrorResume(e -> Mono.just(Optional.empty()))
                        .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    var won = results.stream().flatMap(Optional::stream).toList();
    assertEquals(1, won.size());
    allocate(List.of(won.getFirst().id()), "settled").block(WAIT);
    assertEquals(2, count("payment_facts"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(2000, balance(psp.id()).netAllocated() + balance(wire.id()).netAllocated());
    assertEquals(
        2000, balance(psp.id()).unallocatedRemaining() + balance(wire.id()).unallocatedRemaining());
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void compensatingFactsRetainHistoryAndCannotRestoreTooMuch() {
    var p = payment(2500, "payment");
    var m = match(p.id(), "match").block(WAIT);
    var allocation = allocate(List.of(m.id()), "allocation").block(WAIT).getFirst();
    var lost =
        record(
                proof(
                    movement(
                        Kind.CHARGEBACK,
                        p.id(),
                        allocation.id(),
                        500,
                        Currency.EUR,
                        Method.PSP,
                        "loss")),
                "loss")
            .block(WAIT);
    assertEquals(1500, balance(p.id()).netAllocated());
    record(
            proof(
                movement(
                    Kind.CHARGEBACK_REVERSAL,
                    lost.id(),
                    allocation.id(),
                    200,
                    Currency.EUR,
                    Method.PSP,
                    "reversal")),
            "reversal")
        .block(WAIT);
    var restored = balance(p.id());
    assertEquals(1700, restored.netAllocated());
    assertEquals(500, restored.unallocatedRemaining());
    assertEquals(300, restored.confirmedNetReturned());
    conserves(restored);
    assertThrows(
        Exception.class,
        () ->
            record(
                    proof(
                        movement(
                            Kind.CHARGEBACK_REVERSAL,
                            lost.id(),
                            allocation.id(),
                            301,
                            Currency.EUR,
                            Method.PSP,
                            "too-much")),
                    "too-much")
                .block(WAIT));
    assertEquals(restored, balance(p.id()));
    assertEquals(3, count("payment_facts"));
    assertEquals(1, count("payment_allocations"));
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void failedOutboxOrOuterTransactionLeavesNoPartialBatch() {
    var a = payment(1200, "a");
    var b = payment(800, "b");
    var ma = match(a.id(), "a").block(WAIT);
    var mb = match(b.id(), "b").block(WAIT);
    long events = count("outbox_events"), receipts = count("financial_command_receipts");
    sql(
        "ALTER TABLE taska.outbox_events ADD CONSTRAINT fail_ledger_test"
            + " CHECK(event_type<>'PaymentAllocated')");
    try {
      assertThrows(Exception.class, () -> allocate(List.of(ma.id(), mb.id()), "retry").block(WAIT));
    } finally {
      sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT fail_ledger_test");
    }
    assertEquals(0, count("payment_allocations"));
    assertEquals(events, count("outbox_events"));
    assertEquals(receipts, count("financial_command_receipts"));
    assertThrows(
        Exception.class,
        () ->
            worker(
                    "allocateInvoicePayments",
                    f.f.transactions.execute(
                        db ->
                            ledger
                                .allocate(A, invoice, List.of(ma.id(), mb.id()), "retry")
                                .then(Mono.error(new IllegalStateException("outer rollback")))))
                .block(WAIT));
    assertEquals(0, count("payment_allocations"));
    assertEquals(events, count("outbox_events"));
    assertEquals(receipts, count("financial_command_receipts"));
    assertEquals(2, allocate(List.of(ma.id(), mb.id()), "retry").block(WAIT).size());
  }

  @Test
  void failedRecordDoesNotCommitFactReceiptOrEvent() {
    UUID id = proof(movement(Kind.PAYMENT, null, null, 2000, Currency.EUR, Method.PSP, "source"));
    long events = count("outbox_events");
    sql(
        "ALTER TABLE taska.outbox_events ADD CONSTRAINT fail_fact_test"
            + " CHECK(event_type<>'FinancialFactRecorded')");
    try {
      assertThrows(Exception.class, () -> record(id, "retry").block(WAIT));
    } finally {
      sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT fail_fact_test");
    }
    assertEquals(0, count("payment_facts"));
    assertEquals(0, count("financial_command_receipts"));
    assertEquals(events, count("outbox_events"));
    assertNotNull(record(id, "retry").block(WAIT));
  }

  @Test
  void ownerAuthorityRlsAndImmutableGuardsProtectJournal() {
    var p = payment(2500, "payment");
    var m = match(p.id(), "match").block(WAIT);
    allocate(List.of(m.id()), "allocation").block(WAIT);
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        RegistryTestContext.withClaims(
            f.f.human(OTHER, A, "getFinancialBalance"), ledger.balance(A, p.id())));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        RegistryTestContext.withClaims(
            f.f.human(OWNER_B, B, "getFinancialBalance"), ledger.balance(A, p.id())));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            f.human("recordFinancialFact"), ledger.record(A, UUID.randomUUID(), "human")));
    var claims = f.f.human(OWNER_B, B, "getFinancialBalance");
    assertEquals(
        0L,
        RegistryTestContext.withClaims(
                claims,
                f.f.transactions.execute(
                    db ->
                        db.sql("SELECT count(*) n FROM taska.financial_balances")
                            .map((r, meta) -> r.get("n", Long.class))
                            .one()))
            .block(WAIT));
    for (var table : List.of("payment_facts", "payment_allocations", "invoice_payment_matches")) {
      assertThrows(
          Exception.class,
          () -> sql("DELETE FROM taska." + table + " WHERE tenant_id='" + A + "'"));
    }
    assertThrows(
        Exception.class,
        () -> sql("UPDATE taska.payment_facts SET amount_minor=3000 WHERE id='" + p.id() + "'"));
    var before = balance(p.id());
    f.f.owners.put(A.toString(), OTHER.toString());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        RegistryTestContext.withClaims(f.human("getFinancialBalance"), ledger.balance(A, p.id())));
    f.f.owners.put(A.toString(), OWNER_A.toString());
    assertEquals(before, balance(p.id()));
    assertEquals(
        2500,
        RegistryTestContext.withClaims(f.human("getFinancialFact"), ledger.get(A, p.id()))
            .block(WAIT)
            .movement()
            .amountMinor());
    assertTrue(
        operator
            .sql(
                "SELECT payload::text text FROM taska.outbox_events WHERE"
                    + " aggregate_type='FinancialLedger'")
            .map((r, meta) -> r.get("text", String.class))
            .all()
            .collectList()
            .block(WAIT)
            .stream()
            .noneMatch(
                x ->
                    x.contains("receipt/")
                        || x.contains("test-merchant")
                        || x.contains("opaque-payer")));
  }

  @Test
  void validatesSourceCurrencyTimePrecisionAndUnavailableAuthority() {
    var usd =
        record(
                proof(movement(Kind.PAYMENT, null, null, 2000, Currency.USD, Method.PSP, "usd")),
                "usd")
            .block(WAIT);
    assertThrows(Exception.class, () -> match(usd.id(), "wrong-currency").block(WAIT));
    assertEquals(0, count("invoice_payment_matches"));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        record(
            proof(movement(Kind.PAYMENT, null, null, 0, Currency.EUR, Method.PSP, "zero")),
            "zero"));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        record(
            proof(movement(Kind.PAYMENT, null, null, 2000, Currency.USD, Method.WIRE, "usd-wire")),
            "usd-wire"));
    Movement exact =
        movement(Kind.PAYMENT, null, null, Long.MAX_VALUE, Currency.EUR, Method.WIRE, "day");
    Movement day =
        new Movement(
            exact.tenantId(),
            exact.source(),
            exact.kind(),
            null,
            null,
            exact.amountMinor(),
            exact.currency(),
            new Occurrence(Precision.DAY, null, NOW.minusSeconds(86400), NOW),
            exact.observedAt(),
            exact.confirmedAt(),
            exact.confirmedBy(),
            exact.payerRef(),
            exact.evidenceRef(),
            exact.evidenceDigest());
    var saved = record(proof(day), "day").block(WAIT);
    assertEquals(day, saved.movement());
    assertNull(saved.movement().occurrence().at());
    assertEquals(Long.MAX_VALUE, balance(saved.id()).received());
    conserves(balance(saved.id()));
    var offline =
        new BillingConfiguration()
            .financialLedger(
                pool,
                f.f.readers,
                new BillingConfiguration().financialEvidenceAuthority(),
                new BillingConfiguration().financialEligibilityAuthority());
    rejects(
        DomainStatus.UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        worker("recordFinancialFact", offline.record(A, UUID.randomUUID(), "offline")));
    assertEquals(2, count("payment_facts"));
  }

  @Test
  void sourceIdentityIncludesEnvironmentButCannotBeReusedAcrossTenants() {
    Movement original =
        movement(Kind.PAYMENT, null, null, 2500, Currency.EUR, Method.PSP, "global-external");
    var a = record(proof(original), "source").block(WAIT);
    Movement foreign =
        new Movement(
            B,
            original.source(),
            original.kind(),
            null,
            null,
            original.amountMinor(),
            original.currency(),
            original.occurrence(),
            original.observedAt(),
            original.confirmedAt(),
            null,
            null,
            original.evidenceRef(),
            original.evidenceDigest());
    UUID foreignProof = proof(foreign);
    rejects(
        DomainStatus.ALREADY_EXISTS,
        "FINANCIAL_FACT_CONFLICT",
        RegistryTestContext.withClaims(
            f.f.worker(B, "recordFinancialFact", "billing-service").toBuilder()
                .addScopes("ledger:write")
                .build(),
            ledger.record(B, foreignProof, "foreign")));
    Source live =
        new Source(
            original.source().provider(),
            original.source().accountId(),
            Environment.LIVE,
            original.source().externalId(),
            Method.PSP);
    Movement independent =
        new Movement(
            A,
            live,
            original.kind(),
            null,
            null,
            original.amountMinor(),
            original.currency(),
            original.occurrence(),
            original.observedAt(),
            original.confirmedAt(),
            null,
            null,
            original.evidenceRef(),
            original.evidenceDigest());
    assertNotEquals(a.id(), record(proof(independent), "live").block(WAIT).id());
    assertEquals(2, count("payment_facts"));
  }

  @Test
  void concurrentReturnsCannotOverspendUnallocatedExcess() {
    var source = payment(2500, "source");
    var m = match(source.id(), "match").block(WAIT);
    allocate(List.of(m.id()), "allocation").block(WAIT);
    UUID a =
        proof(movement(Kind.REFUND, source.id(), null, 500, Currency.EUR, Method.PSP, "return-a"));
    UUID b =
        proof(movement(Kind.REFUND, source.id(), null, 500, Currency.EUR, Method.PSP, "return-b"));
    var outcomes =
        Flux.just(a, b)
            .flatMap(
                id ->
                    record(id, id.toString())
                        .map(Optional::of)
                        .onErrorResume(e -> Mono.just(Optional.empty()))
                        .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(1, outcomes.stream().flatMap(Optional::stream).count());
    assertEquals(2, count("payment_facts"));
    var balance = balance(source.id());
    assertEquals(2000, balance.netAllocated());
    assertEquals(0, balance.unallocatedRemaining());
    assertEquals(500, balance.confirmedNetReturned());
    conserves(balance);
  }

  @Test
  void databaseRejectsPartialFinalAllocationAndIncompleteDayEvidence() {
    var source = payment(1200, "source");
    var match = match(source.id(), "match").block(WAIT);
    assertThrows(
        Exception.class,
        () ->
            worker(
                    "allocateInvoicePayments",
                    f.f.transactions.execute(
                        db ->
                            db.sql(
                                    "INSERT INTO"
                                        + " taska.payment_allocations(id,tenant_id,payment_fact_id,invoice_id,match_id,amount_minor,operation_id,eligibility_evidence)"
                                        + " VALUES(:id,:tenant,:source,:invoice,:match,1200,:operation,'{}')")
                                .bind("id", UUID.randomUUID())
                                .bind("tenant", A)
                                .bind("source", source.id())
                                .bind("invoice", invoice)
                                .bind("match", match.id())
                                .bind("operation", UUID.randomUUID())
                                .fetch()
                                .rowsUpdated()))
                .block(WAIT));
    assertEquals(0, count("payment_allocations"));
    assertEquals(1200, balance(source.id()).matchedUnallocated());
    UUID id = UUID.randomUUID();
    assertThrows(
        Exception.class,
        () ->
            sql(
                "INSERT INTO"
                    + " taska.payment_facts(id,tenant_id,provider,provider_account_id,environment,external_fact_id,fact_kind,root_payment_id,amount_minor,currency,currency_exponent,source_method,occurred_precision,occurred_not_before,observed_at,verified_at,evidence_ref,evidence_digest,request_hash)"
                    + " VALUES('"
                    + id
                    + "','"
                    + A
                    + "','TEST_PROVIDER','test-merchant','SANDBOX','invalid-day','PAYMENT','"
                    + id
                    + "',2000,'EUR',2,'PSP','DAY',now(),now(),now(),'receipt/test',decode('"
                    + "ab".repeat(32)
                    + "','hex'),decode('"
                    + "ab".repeat(32)
                    + "','hex'))"));
    assertEquals(1, count("payment_facts"));
  }

  @Test
  void twoHalfAllocationsOnDifferentInvoicesDoNotMakeOneFullCoverage() {
    var a = payment(1200, "invoice-a-source");
    var ma = match(a.id(), "match-a").block(WAIT);
    f.nextTarget("second-invoice");
    UUID second = UUID.fromString(f.create("second-invoice").block(WAIT).getId());
    var b = payment(800, "invoice-b-source");
    var mb = worker("matchInvoicePayment", ledger.match(A, b.id(), second, "match-b")).block(WAIT);
    assertThrows(
        Exception.class,
        () ->
            worker(
                    "allocateInvoicePayments",
                    f.f.transactions.execute(
                        db ->
                            db.sql(
                                    "INSERT INTO"
                                        + " taska.payment_allocations(id,tenant_id,payment_fact_id,invoice_id,match_id,amount_minor,operation_id,eligibility_evidence)"
                                        + " VALUES(:id,:tenant,:source,:invoice,:match,1200,:operation,'{}')")
                                .bind("id", UUID.randomUUID())
                                .bind("tenant", A)
                                .bind("source", a.id())
                                .bind("invoice", invoice)
                                .bind("match", ma.id())
                                .bind("operation", UUID.randomUUID())
                                .fetch()
                                .rowsUpdated()
                                .then(
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.payment_allocations(id,tenant_id,payment_fact_id,invoice_id,match_id,amount_minor,operation_id,eligibility_evidence)"
                                                + " VALUES(:id,:tenant,:source,:invoice,:match,800,:operation,'{}')")
                                        .bind("id", UUID.randomUUID())
                                        .bind("tenant", A)
                                        .bind("source", b.id())
                                        .bind("invoice", second)
                                        .bind("match", mb.id())
                                        .bind("operation", UUID.randomUUID())
                                        .fetch()
                                        .rowsUpdated())))
                .block(WAIT));
    assertEquals(0, count("payment_allocations"));
    assertEquals(1200, balance(a.id()).unallocatedRemaining());
    assertEquals(800, balance(b.id()).unallocatedRemaining());
  }
}
