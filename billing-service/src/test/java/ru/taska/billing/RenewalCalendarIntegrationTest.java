package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

@Testcontainers
@Timeout(90)
class RenewalCalendarIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final InvoiceIntegrationTest f = new InvoiceIntegrationTest();
  UUID period;
  AtomicInteger authority;
  InvoiceStore invoices;

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
    UUID proof = f.f.proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    f.f.grant(A, proof, "paid").block(WAIT);
    period = f.f.confirmed.get(proof).activationOperationId();
    sql("UPDATE taska.subscriptions SET renewal_enabled=true WHERE tenant_id='" + A + "'");
    authority = new AtomicInteger(0);
    invoices =
        new InvoiceStore(
            f.f.transactions,
            f.f.readers,
            new RenewalCalendar(),
            (db, plan, request, now) -> Mono.just(f.quote.get()),
            f.catalog,
            f.f.clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), f.f.clock),
            (db, ctx, source, payload) ->
                authority.get() == 2
                    ? Mono.error(
                        CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))
                    : Mono.just(authority.get() == 0));
  }

  @AfterEach
  void noLeak() {
    f.f.scopeDoesNotLeak();
  }

  CreateInvoiceRequest payload() {
    return f.payload().toBuilder()
        .setPurpose(CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
        .build();
  }

  Mono<Invoice> issue(UUID tenant, UUID source) {
    return f.f.asWorker(
        tenant,
        "issueRenewalInvoice",
        "billing-service",
        invoices.issueScheduledRenewal(tenant, source, payload()));
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  void rejects(DomainStatus status, Mono<?> work) {
    assertEquals(status, assertThrows(DomainException.class, () -> work.block(WAIT)).getStatus());
  }

  @Test
  void concurrentSchedulerStepsAndRetriesIssueOneInvoiceForTheSameCalendarTarget() {
    long before = count("invoices");
    var results =
        Flux.range(0, 12)
            .flatMap(i -> issue(A, period).subscribeOn(Schedulers.parallel()), 12)
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().distinct().count());
    assertEquals(before + 1, count("invoices"));
    assertEquals(1, count("renewal_calendar_assignments"));
    var row =
        operator
            .sql("SELECT period_starts_at,period_ends_at FROM taska.invoices WHERE id=:id")
            .bind("id", UUID.fromString(results.getFirst().getId()))
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(
        Instant.parse("2026-02-28T12:34:56Z"),
        ((OffsetDateTime) row.get("period_starts_at")).toInstant());
    assertEquals(
        Instant.parse("2026-03-31T12:34:56Z"),
        ((OffsetDateTime) row.get("period_ends_at")).toInstant());
    assertEquals(results.getFirst(), issue(A, period).block(WAIT));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void restartAfterSuccessKeepsOriginalInvoiceEvenAfterExpiryOrCancellation() {
    var first = issue(A, period).block(WAIT);
    f.f.time.set(Instant.parse("2026-03-03T12:00:00Z"));
    sql(
        "UPDATE taska.subscriptions SET renewal_cancelled=true,renewal_enabled=false WHERE"
            + " tenant_id='"
            + A
            + "'");
    assertEquals(first, issue(A, period).block(WAIT));
    assertEquals(1, count("renewal_calendar_assignments"));
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void expiryDoesNotCreateDebtOrAFreeFallbackAndDisabledRenewalCannotAdmitANewInvoice() {
    long before = count("invoices");
    f.f.time.set(Instant.parse("2026-02-28T12:34:56Z"));
    rejects(DomainStatus.FAILED_PRECONDITION, issue(A, period));
    assertEquals(before, count("invoices"));
    assertEquals(0, count("renewal_calendar_assignments"));
    f.f.time.set(NOW);
    sql("UPDATE taska.subscriptions SET renewal_cancelled=true WHERE tenant_id='" + A + "'");
    rejects(DomainStatus.FAILED_PRECONDITION, issue(A, period));
    assertEquals(
        "PAID",
        operator
            .sql("SELECT mode FROM taska.subscriptions WHERE tenant_id=:id")
            .bind("id", A)
            .map((r, m) -> r.get("mode", String.class))
            .one()
            .block(WAIT));
    assertEquals("2026-02-28T12:34:56Z", f.f.get(OWNER_A, A).getPaidThrough());
  }

  @Test
  void outboxFailureRollsBackAssignmentInvoiceAndLinesAndRetryIsSafe() {
    long before = count("invoices"), events = count("outbox_events");
    sql(
        "CREATE FUNCTION taska.fixture_renew_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'fixture'; END $$");
    sql(
        "CREATE TRIGGER fixture_renew_fail BEFORE INSERT ON taska.outbox_events FOR EACH ROW"
            + " EXECUTE FUNCTION taska.fixture_renew_fail()");
    try {
      assertThrows(RuntimeException.class, () -> issue(A, period).block(WAIT));
      assertEquals(before, count("invoices"));
      assertEquals(events, count("outbox_events"));
      assertEquals(0, count("renewal_calendar_assignments"));
    } finally {
      sql("DROP TRIGGER fixture_renew_fail ON taska.outbox_events");
      sql("DROP FUNCTION taska.fixture_renew_fail()");
    }
    issue(A, period).block(WAIT);
    assertEquals(before + 1, count("invoices"));
    assertEquals(1, count("renewal_calendar_assignments"));
  }

  @Test
  void schedulerAuthorityMissingContextAndCrossTenantSourceFailBeforeWrites() {
    rejects(DomainStatus.UNAUTHENTICATED, invoices.issueScheduledRenewal(A, period, payload()));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        f.f.asWorker(
            A, "other", "billing-service", invoices.issueScheduledRenewal(A, period, payload())));
    authority.set(1);
    rejects(DomainStatus.PERMISSION_DENIED, issue(A, period));
    authority.set(2);
    rejects(DomainStatus.UNAVAILABLE, issue(A, period));
    authority.set(0);
    rejects(DomainStatus.NOT_FOUND, issue(B, period));
    assertEquals(0, count("renewal_calendar_assignments"));
  }

  @Test
  void immutableAssignmentsAreRlsIsolatedAndConflictingPlanCannotCreateAnotherInvoice() {
    var first = issue(A, period).block(WAIT);
    var visible =
        f.f.transactions.execute(
            client ->
                client
                    .sql("SELECT tenant_id FROM taska.renewal_calendar_assignments")
                    .map((r, m) -> r.get("tenant_id", UUID.class))
                    .all()
                    .collectList());
    assertEquals(List.of(A), f.f.asWorker(A, "fixture", "billing-service", visible).block(WAIT));
    assertTrue(f.f.asWorker(B, "fixture", "billing-service", visible).block(WAIT).isEmpty());
    for (String stmt :
        List.of(
            "UPDATE taska.renewal_calendar_assignments SET anchor_day=1",
            "DELETE FROM taska.renewal_calendar_assignments"))
      assertThrows(RuntimeException.class, () -> sql(stmt));
    var wrong = payload().toBuilder().setPlanVersionId(FREE.toString()).build();
    rejects(
        DomainStatus.ALREADY_EXISTS,
        f.f.asWorker(
            A,
            "issueRenewalInvoice",
            "billing-service",
            invoices.issueScheduledRenewal(A, period, wrong)));
    assertEquals(first, issue(A, period).block(WAIT));
    assertEquals(1, count("renewal_calendar_assignments"));
  }

  @Test
  void scheduledTicksReacquireAuthorityAndRepeatedWorkDoesNotIssueAnotherInvoice() {
    var work = new RenewalScheduler.Work(A, period, payload());
    var scheduler =
        new RenewalScheduler(
            invoices,
            now -> Flux.just(work, work),
            (tenant, action, operation) ->
                f.f.asWorker(tenant, action, "billing-service", operation),
            f.f.clock);
    long before = count("invoices");
    scheduler.tick().block(WAIT);
    scheduler.tick().block(WAIT);
    assertEquals(before + 1, count("invoices"));
    assertEquals(1, count("renewal_calendar_assignments"));
    authority.set(1);
    var failure = assertThrows(RuntimeException.class, () -> scheduler.tick().block(WAIT));
    assertEquals(
        2,
        reactor.core.Exceptions.unwrapMultiple(failure).stream()
            .filter(
                t ->
                    t instanceof DomainException d
                        && d.getStatus() == DomainStatus.PERMISSION_DENIED)
            .count());
    assertEquals(before + 1, count("invoices"));
  }
}
