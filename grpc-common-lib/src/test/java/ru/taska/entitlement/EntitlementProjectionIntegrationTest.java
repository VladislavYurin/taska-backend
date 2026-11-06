package ru.taska.entitlement;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.security.tenant.TenantIsolationFixture.*;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.event.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.TenantIsolationFixture;
import tools.jackson.databind.json.JsonMapper;

class EntitlementProjectionIntegrationTest {
  static TenantIsolationFixture fixture;
  static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
  final AtomicReference<Instant> now = new AtomicReference<>(START);
  final AtomicInteger calls = new AtomicInteger();
  final AtomicBoolean member = new AtomicBoolean(true),
      online = new AtomicBoolean(true),
      broker = new AtomicBoolean(true);
  final Map<UUID, Entitlement> billing = new HashMap<>();
  Set<RestrictionCode> independent = Set.of();
  EntitlementProjection projection;
  Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return now.get();
        }
      };

  @BeforeAll
  static void database() throws Exception {
    fixture = new TenantIsolationFixture(3);
    String sql =
        "SET ROLE taska_project_migrator;" + resource("db/tenant/entitlement-projection.sql");
    for (String table : List.of("entitlement_projections", "entitlement_projection_events")) {
      sql +=
          "SELECT"
              + " set_config('taska.guard_service','project',false),set_config('taska.guard_schema','taska',false),set_config('taska.guard_table','"
              + table
              + "',false),set_config('taska.guard_column','tenant_id',false),set_config('taska.guard_app_access','"
              + (table.endsWith("events") ? "append_only" : "read_write")
              + "',false);";
      sql += resource("db/tenant/protect-table.sql");
    }
    var result =
        fixture.postgres.execInContainer(
            "psql",
            "-X",
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            fixture.postgres.getUsername(),
            "-d",
            fixture.postgres.getDatabaseName(),
            "-c",
            sql);
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  static String resource(String name) throws Exception {
    try (var in = EntitlementProjection.class.getClassLoader().getResourceAsStream(name)) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @AfterAll
  static void close() {
    if (fixture != null) fixture.close();
  }

  @BeforeEach
  void setup() {
    fixture
        .operator
        .sql("TRUNCATE taska.entitlement_projection_events,taska.entitlement_projections")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    billing.put(A, snapshot(A, 1));
    billing.put(B, snapshot(B, 8));
    projection = newProjection();
  }

  EntitlementProjection newProjection() {
    return new EntitlementProjection(
        fixture.executor,
        (tenant, minimum) ->
            Mono.defer(
                () -> {
                  calls.incrementAndGet();
                  return online.get()
                      ? Mono.justOrEmpty(billing.get(tenant))
                      : Mono.error(new IllegalStateException("billing unavailable"));
                }),
        (context, use) ->
            member.get()
                ? Mono.just(independent)
                : Mono.error(
                    new DomainException(
                        ru.taska.exception.DomainStatus.PERMISSION_DENIED, "membership revoked")),
        (context, event) -> Mono.just(broker.get()),
        clock,
        Duration.ofSeconds(10),
        2);
  }

  Entitlement snapshot(UUID tenant, long version) {
    var e =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(Long.toString(version))
            .setPlanVersionId("30000000-0000-0000-0000-000000000001")
            .setCoveragePeriodId("40000000-0000-0000-0000-000000000001")
            .setValidFrom(START.minusSeconds(100).toString())
            .setIssuedAt(START.toString())
            .setValidUntil(START.plusSeconds(100).toString())
            .setPaidThrough(START.plusSeconds(100).toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      e.addLimits(QuotaLimit.newBuilder().setKind(kind).setLimit("50"));
    return e.build();
  }

  TaskaEvent event(UUID id, Entitlement e) throws Exception {
    UUID tenant = UUID.fromString(e.getTenantId());
    return TaskaEvent.builder()
        .id(id)
        .scopeKind(EventScopeKind.TENANT)
        .tenantId(tenant)
        .scopeId(tenant)
        .aggregateType("Entitlement")
        .aggregateId(tenant)
        .eventType("EntitlementSnapshotChanged")
        .producerService("billing-service")
        .schemaVersion("2")
        .occurredAt(START)
        .payload(JsonMapper.builder().build().readTree(JsonFormat.printer().print(e)))
        .build();
  }

  EntitlementProjection.View read(UUID tenant) {
    return as(tenant, projection.resolve(EntitlementProjection.Use.READ)).block(WAIT);
  }

  void apply(TaskaEvent event) {
    as(event.tenantId(), projection.apply(event)).block(WAIT);
  }

  @Test
  void tenantQualifiedBoundedCacheAndCurrentMembership() {
    assertEquals("1", read(A).snapshot().getVersion());
    assertEquals("8", read(B).snapshot().getVersion());
    int initial = calls.get();
    read(A);
    assertEquals(initial, calls.get());
    member.set(false);
    assertThrows(DomainException.class, () -> read(A));
    member.set(true);
    independent = Set.of(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE);
    assertTrue(read(A).restrictions().contains(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE));
    assertThrows(DomainException.class, () -> read(A).requireNewResource());
  }

  @Test
  void missingOutageAndAdmissionNeverFallBackToWarmCache() {
    read(A);
    read(A);
    online.set(false);
    assertThrows(
        RuntimeException.class,
        () -> as(A, projection.resolve(EntitlementProjection.Use.NEW_RESOURCE)).block(WAIT));
    now.set(START.plusSeconds(10));
    assertThrows(RuntimeException.class, () -> read(A));
    online.set(true);
    billing.remove(B);
    assertThrows(DomainException.class, () -> read(B));
    assertEquals(
        1L,
        fixture
            .operator
            .sql("SELECT count(*) n FROM taska.entitlement_projections")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void expiryReevaluatedWithoutWebhook() {
    read(A);
    now.set(START.plusSeconds(100));
    var expired = read(A);
    assertFalse(expired.validNow());
    assertTrue(expired.restrictions().contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED));
    assertThrows(DomainException.class, expired::requireNewResource);
    assertEquals(2, calls.get());
  }

  @Test
  void futureValidityBoundsTheLeaseAndDoesNotAdmitBeforeActivation() {
    billing.put(A, snapshot(A, 1).toBuilder().setValidFrom(START.plusSeconds(5).toString()).build());
    var pending = as(A, projection.resolveLease(EntitlementProjection.Use.READ)).block(WAIT);
    assertFalse(pending.view().validNow());
    assertEquals(START.plusSeconds(5), pending.validUntil());
    assertThrows(DomainException.class, pending.view()::requireNewResource);
    now.set(START.plusSeconds(5));
    var active = as(A, projection.resolveLease(EntitlementProjection.Use.READ)).block(WAIT);
    assertTrue(active.view().validNow());
    assertEquals(START.plusSeconds(10), active.validUntil());
    assertDoesNotThrow(active.view()::requireNewResource);
  }

  @Test
  void duplicateOldAndReorderedRevokePreserveIndependentCoverageAndRestrictions() throws Exception {
    read(A);
    var revoke =
        snapshot(A, 2).toBuilder()
            .addRestrictions(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE)
            .build();
    var paid =
        snapshot(A, 3).toBuilder()
            .setCoveragePeriodId("40000000-0000-0000-0000-000000000002")
            .setPaidThrough(START.atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toString())
            .setValidUntil(START.atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toString())
            .addRestrictions(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE)
            .build();
    var latest = event(UUID.randomUUID(), paid);
    apply(latest);
    apply(event(UUID.randomUUID(), revoke));
    apply(latest);
    independent =
        Set.of(
            RestrictionCode.RESTRICTION_CODE_PLATFORM_BLOCK,
            RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE);
    var view = read(A);
    assertEquals("3", view.snapshot().getVersion());
    assertEquals(paid.getCoveragePeriodId(), view.snapshot().getCoveragePeriodId());
    assertEquals(3, view.restrictions().size());
    assertEquals(
        2L,
        fixture
            .operator
            .sql("SELECT count(*) n FROM taska.entitlement_projection_events")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    // Durable restart, without a warm process cache or a fresh billing call.
    projection = newProjection();
    online.set(false);
    assertEquals("3", read(A).snapshot().getVersion());
  }

  @Test
  void duplicateDoesNotExtendTtlAndSameVersionConflictRollsBack() throws Exception {
    var original = event(UUID.randomUUID(), snapshot(A, 1));
    apply(original);
    now.set(START.plusSeconds(9));
    apply(original);
    now.set(START.plusSeconds(10));
    online.set(false);
    assertThrows(RuntimeException.class, () -> read(A));
    var conflict =
        event(UUID.randomUUID(), snapshot(A, 1).toBuilder().addFeatures("extra").build());
    assertThrows(DomainException.class, () -> apply(conflict));
    assertEquals(
        1L,
        fixture
            .operator
            .sql("SELECT count(*) n FROM taska.entitlement_projection_events")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertThrows(DomainException.class, () -> apply(event(original.id(), snapshot(A, 2))));
  }

  @Test
  void staleReconciliationAndUntrustedEventCannotAdvanceState() throws Exception {
    apply(event(UUID.randomUUID(), snapshot(A, 2)));
    assertThrows(
        DomainException.class,
        () -> as(A, projection.resolve(EntitlementProjection.Use.NEW_RESOURCE)).block(WAIT));
    broker.set(false);
    assertThrows(DomainException.class, () -> apply(event(UUID.randomUUID(), snapshot(A, 3))));
    assertThrows(
        DomainException.class,
        () -> as(B, projection.apply(event(UUID.randomUUID(), snapshot(A, 3)))).block(WAIT));
    assertEquals("2", read(A).snapshot().getVersion());
    assertEquals(
        0L,
        fixture
            .raw
            .sql("SELECT count(*) n FROM taska.entitlement_projections")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void simultaneousVersionsOnlyAdvance() throws Exception {
    var first = event(UUID.randomUUID(), snapshot(A, 5));
    var second = event(UUID.randomUUID(), snapshot(A, 6));
    Mono.when(as(A, projection.apply(first)), as(A, projection.apply(second))).block(WAIT);
    assertEquals("6", read(A).snapshot().getVersion());
  }

  @Test
  void malformedSnapshotCannotBecomeUnlimited() {
    billing.put(A, snapshot(A, 1).toBuilder().clearLimits().build());
    assertThrows(DomainException.class, () -> read(A));
    assertEquals(
        0L,
        fixture
            .operator
            .sql("SELECT count(*) n FROM taska.entitlement_projections")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void historicalExpiredCoverageAndExplicitFreeAreBothValidContracts() {
    billing.put(
        A,
        snapshot(A, 2).toBuilder()
            .clearValidUntil()
            .setValidFrom(START.toString())
            .setPaidThrough(START.minusSeconds(1).toString())
            .addRestrictions(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED)
            .build());
    assertFalse(read(A).validNow());
    billing.put(
        A,
        snapshot(A, 3).toBuilder()
            .clearCoveragePeriodId()
            .clearPaidThrough()
            .clearValidUntil()
            .build());
    var free = as(A, projection.resolve(EntitlementProjection.Use.NEW_RESOURCE)).block(WAIT);
    assertTrue(free.validNow());
    free.requireNewResource();
  }

  @Test
  void identicalAuthoritativeLookupRefreshesTrustButEventsCannotRefreshIt() throws Exception {
    read(A);
    now.set(START.plusSeconds(10));
    read(A); // New authoritative lookup, identical immutable version.
    assertEquals(2, calls.get());
    now.set(START.plusSeconds(19));
    apply(event(UUID.randomUUID(), snapshot(A, 1))); // Same version, new envelope ID.
    read(A);
    assertEquals(2, calls.get());
    now.set(START.plusSeconds(20));
    read(A);
    assertEquals(3, calls.get());
  }

  @Test
  void reconciliationLosingRaceReturnsNewerCommittedEventWithoutFalseConflict() throws Exception {
    read(A);
    var response = reactor.core.publisher.Sinks.<Entitlement>one();
    projection =
        new EntitlementProjection(
            fixture.executor,
            (t, min) -> {
              assertEquals(1, min);
              return response.asMono();
            },
            (c, u) -> Mono.just(Set.of()),
            (c, e) -> Mono.just(true),
            clock,
            Duration.ofSeconds(10),
            2);
    var pending = as(A, projection.resolve(EntitlementProjection.Use.NEW_RESOURCE)).toFuture();
    // Wait for the source subscription rather than racing its initial database read.
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (response.currentSubscriberCount() == 0 && System.nanoTime() < deadline)
      Thread.onSpinWait();
    assertEquals(1, response.currentSubscriberCount());
    apply(event(UUID.randomUUID(), snapshot(A, 3)));
    assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, response.tryEmitValue(snapshot(A, 2)));
    assertEquals(
        "3", pending.get(5, java.util.concurrent.TimeUnit.SECONDS).snapshot().getVersion());
  }

  @Test
  void exactRequiredLimitKindsRejectDuplicatesMissingAndUnknownKinds() {
    var complete = snapshot(A, 1);
    assertEquals(4, EntitlementProjection.validate(complete).getLimitsCount());
    assertThrows(
        DomainException.class,
        () ->
            EntitlementProjection.validate(
                complete.toBuilder().setLimits(0, complete.getLimits(1)).build()));
    assertThrows(
        DomainException.class,
        () ->
            EntitlementProjection.validate(
                complete.toBuilder()
                    .setLimits(0, complete.getLimits(0).toBuilder().setKindValue(99))
                    .build()));
    assertThrows(
        DomainException.class,
        () -> EntitlementProjection.validate(complete.toBuilder().removeLimits(0).build()));
  }
}
