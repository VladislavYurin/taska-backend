package ru.taska.tenant;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class SeatQuotaIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      OWNER = UUID.randomUUID(),
      MEMBER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static final Duration WAIT = Duration.ofSeconds(20);
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static DatabaseClient operator, raw;
  static ConnectionPool pool;
  static ConnectionFactory operatorFactory;
  static TenantTransactionExecutor tx;
  final AtomicReference<Instant> time = new AtomicReference<>(NOW);
  final AtomicLong limit = new AtomicLong(3), version = new AtomicLong(1);
  final AtomicBoolean permitted = new AtomicBoolean(true),
      bypass = new AtomicBoolean(),
      online = new AtomicBoolean(true),
      paused = new AtomicBoolean();
  SeatQuotaStore store;
  Clock clock =
      new Clock() {
        public Instant instant() {
          return time.get();
        }

        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }
      };

  static ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, pg.getHost())
            .option(PORT, pg.getMappedPort(5432))
            .option(DATABASE, pg.getDatabaseName())
            .option(USER, user)
            .option(PASSWORD, password)
            .build());
  }

  @BeforeAll
  static void database() throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    var result =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            pg.getDatabaseName(),
            "-v",
            "service=tenant",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, result.getExitCode(), result.getStderr());
    operatorFactory = factory(pg.getUsername(), pg.getPassword());
    operator = DatabaseClient.create(operatorFactory);
    sql("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
    sql("REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_tenant_app PASSWORD '" + APP + "'");
    sql("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY + "'");
    var lb =
        new TenantConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
    lb.afterPropertiesSet();
    lb.afterPropertiesSet();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP))
                .initialSize(1)
                .maxSize(4)
                .build());
    raw = DatabaseClient.create(pool);
    tx = new TenantTransactionExecutor(pool);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  static void sql(String sql) {
    operator.sql(sql).fetch().rowsUpdated().block(WAIT);
  }

  @BeforeEach
  void seed() {
    sql(
        "TRUNCATE"
            + " taska.quota_effects,taska.quota_reservations,taska.quota_counters,taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events");
    Mono<Void> fixtures =
        Flux.fromIterable(List.of(A, B))
            .concatMap(
                tenant ->
                    operator
                        .sql(
                            "INSERT INTO"
                                + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                                + " VALUES(:t,'fixture',:u,'ACTIVE','FREE',:plan,:u,decode(repeat('00',32),'hex'),:op)")
                        .bind("t", tenant)
                        .bind("u", OWNER)
                        .bind("plan", UUID.randomUUID())
                        .bind("op", UUID.randomUUID())
                        .fetch()
                        .rowsUpdated()
                        .then(
                            operator
                                .sql(
                                    "INSERT INTO"
                                        + " taska.tenant_memberships(tenant_id,user_id,role,status)"
                                        + " VALUES(:t,:u,'TENANT_ADMIN','ACTIVE')")
                                .bind("t", tenant)
                                .bind("u", OWNER)
                                .fetch()
                                .rowsUpdated()))
            .then();
    org.springframework.transaction.reactive.TransactionalOperator.create(
            new org.springframework.r2dbc.connection.R2dbcTransactionManager(operatorFactory))
        .transactional(fixtures)
        .block(WAIT);
    store =
        new SeatQuotaStore(
            tx,
            (c, command) ->
                permitted.get() && !paused.get()
                    ? Mono.just(new SeatQuotaStore.Grant(bypass.get()))
                    : Mono.error(
                        new DomainException(DomainStatus.PERMISSION_DENIED, "purpose denied")),
            (tenant, min) ->
                online.get()
                    ? Mono.just(entitlement(tenant))
                    : Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "billing down")),
            clock,
            t -> Mono.just(taskSummary(t, 0, 1)),
            t -> Mono.just(projectUsage()),
            t ->
                Mono.just(
                    projectUsage().toBuilder()
                        .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                        .build()));
  }

  SeatQuotaStore withTasks(ru.taska.entitlement.TenantTaskQuotaSource source) {
    return new SeatQuotaStore(
        tx,
        (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
        (t, min) -> Mono.just(entitlement(t)),
        clock,
        source,
        t -> Mono.just(projectUsage()),
        t ->
            Mono.just(
                projectUsage().toBuilder()
                    .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                    .build()));
  }

  @Test
  void tenantSnapshotExposesMaximumTaskCountAndVersionsAreMonotonic() {
    store = withTasks(t -> Mono.just(taskSummary(t, 51, 7)));
    var first = getSnapshot(A);
    assertEquals(4, first.getQuotasCount());
    var task = first.getQuotas(1);
    assertEquals(QuotaUsage.KindValue.KIND_TASK_LIFETIME, task.getKind());
    assertEquals("51", task.getUsed());
    assertEquals("0", task.getReserved());
    assertEquals("50", task.getLimit());
    assertFalse(task.hasProjectId());
    assertEquals("7", task.getVersion());
    store = withTasks(t -> Mono.just(taskSummary(t, 52, 8)));
    assertTrue(Long.parseLong(getSnapshot(A).getVersion()) > Long.parseLong(first.getVersion()));
    store = withTasks(t -> Mono.just(taskSummary(t, 0, 7)));
    assertEquals(
        DomainStatus.UNAVAILABLE,
        assertThrows(DomainException.class, () -> getSnapshot(A)).getStatus());
    assertEquals(
        8L,
        operator
            .sql(
                "SELECT task_lifetime_version FROM taska.quota_counters WHERE tenant_id=:t AND"
                    + " resource='SEAT'")
            .bind("t", A)
            .map((r, m) -> r.get("task_lifetime_version", Long.class))
            .one()
            .block(WAIT));
  }

  QuotaSnapshot getSnapshot(UUID tenant) {
    return RegistryTestContext.as(
            OWNER,
            tenant,
            "getTenantQuotas",
            CredentialKind.CREDENTIAL_KIND_WEB_TENANT,
            true,
            store.get(
                GetTenantQuotasRpcRequest.newBuilder().setTenantId(tenant.toString()).build()))
        .block(WAIT);
  }

  @Test
  void missingForeignAndMalformedTaskSummariesNeverBecomeZeroUsage() {
    for (var source :
        java.util.List.<ru.taska.entitlement.TenantTaskQuotaSource>of(
            t -> Mono.empty(),
            t -> Mono.just(taskSummary(B, 0, 1)),
            t -> Mono.just(taskSummary(t, 0, 1).toBuilder().setMaximumCreated("-1").build()),
            t -> Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "offline")))) {
      store = withTasks(source);
      assertThrows(DomainException.class, () -> getSnapshot(A));
    }
    store =
        new SeatQuotaStore(
            tx,
            (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
            (t, min) -> Mono.just(entitlement(t)),
            clock);
    assertEquals(
        DomainStatus.UNAVAILABLE,
        assertThrows(DomainException.class, () -> getSnapshot(A)).getStatus());
  }

  SeatQuotaStore withStorage(ru.taska.quota.StorageQuotaProtocol.UsageSource source) {
    return new SeatQuotaStore(
        tx,
        (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
        (t, min) -> Mono.just(entitlement(t)),
        clock,
        t -> Mono.just(taskSummary(t, 0, 1)),
        t -> Mono.just(projectUsage()),
        source);
  }

  @Test
  void tenantSnapshotIncludesStorageInt64AndRejectsStaleOrUnknownSource() {
    var usage =
        QuotaUsage.newBuilder()
            .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
            .setUsed("5000000000")
            .setReserved("7")
            .setVersion("12")
            .build();
    store = withStorage(t -> Mono.just(usage));
    var first = getSnapshot(A);
    assertEquals(4, first.getQuotasCount());
    assertEquals("5000000000", first.getQuotas(3).getUsed());
    assertEquals("7", first.getQuotas(3).getReserved());
    store =
        withStorage(t -> Mono.just(usage.toBuilder().setReserved("8").setVersion("13").build()));
    assertTrue(Long.parseLong(getSnapshot(A).getVersion()) > Long.parseLong(first.getVersion()));
    store = withStorage(t -> Mono.just(usage));
    assertThrows(DomainException.class, () -> getSnapshot(A));
    store = withStorage(t -> Mono.empty());
    assertThrows(DomainException.class, () -> getSnapshot(A));
    store = withStorage(t -> Mono.just(usage.toBuilder().setUsed("9223372036854775808").build()));
    assertThrows(DomainException.class, () -> getSnapshot(A));
  }

  static QuotaUsage projectUsage() {
    return QuotaUsage.newBuilder()
        .setKind(QuotaUsage.KindValue.KIND_PROJECT)
        .setUsed("0")
        .setReserved("0")
        .setVersion("1")
        .build();
  }

  static TenantTaskQuotaSummary taskSummary(UUID tenant, long maximum, long version) {
    return TenantTaskQuotaSummary.newBuilder()
        .setTenantId(tenant.toString())
        .setMaximumCreated(Long.toString(maximum))
        .setVersion(Long.toString(version))
        .setEntitlementVersion("1")
        .build();
  }

  Entitlement entitlement(UUID tenant) {
    var b =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(Long.toString(version.get()))
            .setPlanVersionId("30000000-0000-0000-0000-000000000001")
            .setValidFrom(NOW.minusSeconds(1).toString())
            .setIssuedAt(NOW.toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      b.addLimits(
          QuotaLimit.newBuilder()
              .setKind(kind)
              .setLimit(Long.toString(kind == QuotaLimit.KindValue.KIND_SEAT ? limit.get() : 50)));
    return b.build();
  }

  static void member(UUID tenant, UUID user, String status) {
    operator
        .sql(
            "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                + " VALUES(:t,:u,'MEMBER',:s)")
        .bind("t", tenant)
        .bind("u", user)
        .bind("s", status)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
  }

  <T> Mono<T> as(UUID tenant, Mono<T> work) {
    return RegistryTestContext.as(
        OWNER, tenant, "seatQuota", CredentialKind.CREDENTIAL_KIND_WEB_TENANT, true, work);
  }

  QuotaUsage usage(UUID tenant) {
    return RegistryTestContext.as(
            OWNER,
            tenant,
            "getTenantQuotas",
            CredentialKind.CREDENTIAL_KIND_WEB_TENANT,
            true,
            store.get(
                GetTenantQuotasRpcRequest.newBuilder().setTenantId(tenant.toString()).build()))
        .block(WAIT)
        .getQuotas(0);
  }

  SeatQuotaStore.Reservation reserve(UUID tenant, UUID operation) {
    return as(tenant, store.reserve(operation, operation, NOW.plusSeconds(600))).block(WAIT);
  }

  FunctionMutation mutation(UUID tenant, UUID user) {
    return new FunctionMutation(tenant, user);
  }

  record FunctionMutation(UUID tenant, UUID user)
      implements java.util.function.Function<DatabaseClient, Mono<Void>> {
    public Mono<Void> apply(DatabaseClient db) {
      return db.sql(
              "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                  + " VALUES(:t,:u,'MEMBER','ACTIVE') ON CONFLICT(tenant_id,user_id) DO UPDATE SET"
                  + " status='ACTIVE',version=taska.tenant_memberships.version+1")
          .bind("t", tenant)
          .bind("u", user)
          .fetch()
          .rowsUpdated()
          .then();
    }
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void ownerSuspendedAndReenabledMembersKeepUniqueSeatsAndRemovalFreesOne() {
    member(A, MEMBER, "SUSPENDED");
    assertEquals("2", usage(A).getUsed());
    sql("UPDATE taska.tenant_memberships SET status='ACTIVE' WHERE user_id='" + MEMBER + "'");
    assertEquals("2", usage(A).getUsed());
    sql("UPDATE taska.tenant_memberships SET status='REMOVED' WHERE user_id='" + MEMBER + "'");
    assertEquals("1", usage(A).getUsed());
    assertEquals("1", usage(B).getUsed());
  }

  @Test
  void concurrentLastSlotNeverOverbooksAndQuotaFailureHasSafeCounts() {
    limit.set(2);
    var results =
        Flux.range(0, 12)
            .flatMap(
                i ->
                    as(A, store.reserve(UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(600)))
                        .map(r -> true)
                        .onErrorResume(
                            DomainException.class,
                            e -> {
                              assertEquals(DomainStatus.RESOURCE_EXHAUSTED, e.getStatus());
                              assertEquals("2", e.getDetails().get("current"));
                              assertEquals("2", e.getDetails().get("limit"));
                              return Mono.just(false);
                            }))
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals("1", usage(A).getReserved());
    assertEquals(1, count("quota_reservations"));
  }

  @Test
  void reserveCommitAndAcceptReplayNeverChargeTwice() {
    UUID op = UUID.randomUUID();
    var r = reserve(A, op);
    assertEquals(r, reserve(A, op));
    var committed =
        as(A, store.commit(op, r.id(), r.fence(), MEMBER, mutation(A, MEMBER))).block(WAIT);
    assertEquals("COMMITTED", committed.state());
    assertEquals(
        committed,
        as(
                A,
                store.commit(
                    op,
                    r.id(),
                    r.fence(),
                    MEMBER,
                    db -> Mono.error(new AssertionError("replayed callback"))))
            .block(WAIT));
    assertEquals(committed, reserve(A, op));
    assertEquals("2", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
    assertEquals(1, count("quota_effects"));
    assertThrows(
        DomainException.class,
        () ->
            as(A, store.commit(op, r.id(), r.fence(), UUID.randomUUID(), mutation(A, MEMBER)))
                .block(WAIT));
  }

  @Test
  void existingMemberAcceptanceReleasesSpareReservationWithoutSecondSeat() {
    member(A, MEMBER, "SUSPENDED");
    var r = reserve(A, UUID.randomUUID());
    as(A, store.commit(r.operation(), r.id(), r.fence(), MEMBER, mutation(A, MEMBER))).block(WAIT);
    assertEquals("2", usage(A).getUsed());
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void expiryAndRevokeReleaseExactlyOnceAndLateCommitCannotCreateMembership() {
    var first = reserve(A, UUID.randomUUID());
    var released = as(A, store.release(first.operation(), first.id(), first.fence())).block(WAIT);
    assertEquals(
        released, as(A, store.release(first.operation(), first.id(), first.fence())).block(WAIT));
    var expiring = reserve(A, UUID.randomUUID());
    time.set(NOW.plusSeconds(600));
    assertEquals("0", usage(A).getReserved());
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    store.commit(
                        expiring.operation(),
                        expiring.id(),
                        expiring.fence(),
                        MEMBER,
                        mutation(A, MEMBER)))
                .block(WAIT));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, count("quota_effects"));
    assertEquals("EXPIRED", reserve(A, expiring.operation()).state());
  }

  @Test
  void failedMembershipOrCallbackRollsBackReservationEffectAndMember() {
    var r = reserve(A, UUID.randomUUID());
    assertThrows(
        DomainException.class,
        () ->
            as(A, store.commit(r.operation(), r.id(), r.fence(), MEMBER, db -> Mono.empty()))
                .block(WAIT));
    assertThrows(
        IllegalStateException.class,
        () ->
            as(
                    A,
                    store.commit(
                        r.operation(),
                        r.id(),
                        r.fence(),
                        MEMBER,
                        db ->
                            mutation(A, MEMBER)
                                .apply(db)
                                .then(Mono.error(new IllegalStateException("fault")))))
                .block(WAIT));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, count("quota_effects"));
    assertEquals("1", usage(A).getReserved());
  }

  @Test
  void explicitScopedBypassStillCountsRealUsageAndOrdinaryRetryCannotBypass() {
    limit.set(1);
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    bypass.set(true);
    var r = reserve(A, UUID.randomUUID());
    as(A, store.commit(r.operation(), r.id(), r.fence(), MEMBER, mutation(A, MEMBER))).block(WAIT);
    bypass.set(false);
    assertEquals("2", usage(A).getUsed());
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    assertEquals("1", usage(B).getUsed());
    assertEquals("0", usage(B).getReserved());
  }

  @Test
  void outageBlocksNewReservationButOldInvitationCanBeAcceptedDuringFinancialReadOnly() {
    var r = reserve(A, UUID.randomUUID());
    online.set(false);
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    as(A, store.commit(r.operation(), r.id(), r.fence(), MEMBER, mutation(A, MEMBER))).block(WAIT);
    online.set(true);
    assertEquals("2", usage(A).getUsed());
    var next = reserve(A, UUID.randomUUID());
    paused.set(true);
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    store.commit(
                        next.operation(),
                        next.id(),
                        next.fence(),
                        UUID.randomUUID(),
                        db -> Mono.empty()))
                .block(WAIT));
    assertEquals("1", usage(A).getReserved());
  }

  @Test
  void deniedAuthorityTenantMismatchAndMissingMembershipLeaveStateUnchanged() {
    permitted.set(false);
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    assertEquals(0, count("quota_reservations"));
    assertThrows(
        DomainException.class,
        () ->
            RegistryTestContext.as(
                    OWNER,
                    B,
                    "getTenantQuotas",
                    CredentialKind.CREDENTIAL_KIND_WEB_TENANT,
                    true,
                    store.get(
                        GetTenantQuotasRpcRequest.newBuilder().setTenantId(A.toString()).build()))
                .block(WAIT));
    sql("UPDATE taska.tenant_memberships SET status='SUSPENDED' WHERE tenant_id='" + A + "'");
    assertThrows(DomainException.class, () -> usage(A));
    for (String table : List.of("quota_counters", "quota_reservations", "quota_effects"))
      assertEquals(
          0L,
          raw.sql("SELECT count(*) n FROM taska." + table)
              .map((r, m) -> r.get("n", Long.class))
              .one()
              .block(WAIT));
  }

  @Test
  void reservationReplayBindsOriginalInvitationAndExpiry() {
    UUID op = UUID.randomUUID();
    var r = reserve(A, op);
    assertThrows(
        DomainException.class,
        () -> as(A, store.reserve(op, UUID.randomUUID(), NOW.plusSeconds(600))).block(WAIT));
    assertThrows(
        DomainException.class,
        () -> as(A, store.reserve(op, op, NOW.plusSeconds(601))).block(WAIT));
    assertEquals(r, reserve(A, op));
    assertEquals(1, count("quota_reservations"));
  }

  @Test
  void olderEntitlementCannotReplaceNewerObservedLimit() {
    version.set(3);
    limit.set(2);
    usage(A);
    version.set(2);
    limit.set(20);
    var error = assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    assertEquals(DomainStatus.UNAVAILABLE, error.getStatus());
    assertEquals(0, count("quota_reservations"));
  }

  @Test
  void missingMalformedExpiredAndWrongTenantSnapshotsNeverGrantNewSeats() {
    var expired =
        entitlement(A).toBuilder()
            .setCoveragePeriodId(UUID.randomUUID().toString())
            .setValidUntil(NOW.toString())
            .setPaidThrough(NOW.toString())
            .build();
    for (var snapshot :
        List.of(entitlement(A).toBuilder().clearLimits().build(), expired, entitlement(B))) {
      store =
          new SeatQuotaStore(
              tx,
              (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
              (tenant, minimum) -> Mono.just(snapshot),
              clock,
              t -> Mono.just(taskSummary(t, 0, 1)),
              t -> Mono.just(projectUsage()),
              t ->
                  Mono.just(
                      projectUsage().toBuilder()
                          .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                          .build()));
      assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    }
    store =
        new SeatQuotaStore(
            tx,
            (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
            (tenant, minimum) -> Mono.empty(),
            clock,
            t -> Mono.just(taskSummary(t, 0, 1)),
            t -> Mono.just(projectUsage()),
            t ->
                Mono.just(
                    projectUsage().toBuilder()
                        .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                        .build()));
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    assertEquals(0, count("quota_reservations"));
    assertEquals(0, count("quota_effects"));
  }

  @Test
  void explicitUnlimitedIsAcceptedAndZeroLimitIsNotImplicitUnlimited() {
    limit.set(0);
    assertThrows(DomainException.class, () -> reserve(A, UUID.randomUUID()));
    var explicit = entitlement(A).toBuilder();
    for (int i = 0; i < explicit.getLimitsCount(); i++)
      if (explicit.getLimits(i).getKind() == QuotaLimit.KindValue.KIND_SEAT)
        explicit.setLimits(i, explicit.getLimits(i).toBuilder().clearLimit().setUnlimited(true));
    store =
        new SeatQuotaStore(
            tx,
            (c, command) -> Mono.just(new SeatQuotaStore.Grant(false)),
            (tenant, minimum) -> Mono.just(explicit.build()),
            clock,
            t -> Mono.just(taskSummary(t, 0, 1)),
            t -> Mono.just(projectUsage()),
            t ->
                Mono.just(
                    projectUsage().toBuilder()
                        .setKind(QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                        .build()));
    for (int i = 0; i < 4; i++) reserve(A, UUID.randomUUID());
    assertEquals("4", usage(A).getReserved());
    assertTrue(usage(A).getUnlimited());
    assertFalse(usage(A).hasLimit());
  }

  @Test
  void callbackCannotConsumeMoreThanItsSingleReservationAndOutboxFailureRollsBack() {
    var r = reserve(A, UUID.randomUUID());
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    store.commit(
                        r.operation(),
                        r.id(),
                        r.fence(),
                        MEMBER,
                        db ->
                            mutation(A, MEMBER)
                                .apply(db)
                                .then(mutation(A, UUID.randomUUID()).apply(db))))
                .block(WAIT));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, count("quota_effects"));
    assertThrows(
        IllegalStateException.class,
        () ->
            as(
                    A,
                    store.commit(
                        r.operation(),
                        r.id(),
                        r.fence(),
                        MEMBER,
                        db ->
                            mutation(A, MEMBER)
                                .apply(db)
                                .then(
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                                                + " VALUES(:id,:t,'Membership',:member,1,'MembershipCreated',2,'{}'::jsonb,:op,'NEW',now())")
                                        .bind("id", UUID.randomUUID())
                                        .bind("t", A)
                                        .bind("member", MEMBER)
                                        .bind("op", r.operation())
                                        .fetch()
                                        .rowsUpdated())
                                .then(Mono.error(new IllegalStateException("after outbox")))))
                .block(WAIT));
    assertEquals(0, count("outbox_events"));
    assertEquals(0, count("quota_effects"));
    assertEquals(2, count("tenant_memberships"));
    assertEquals("1", usage(A).getReserved());
  }

  @Test
  void expiryDuringCallbackCannotCommitAnExpiredGeneration() {
    var r = reserve(A, UUID.randomUUID());
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    store.commit(
                        r.operation(),
                        r.id(),
                        r.fence(),
                        MEMBER,
                        db ->
                            mutation(A, MEMBER)
                                .apply(db)
                                .then(Mono.fromRunnable(() -> time.set(NOW.plusSeconds(600))))))
                .block(WAIT));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, count("quota_effects"));
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void expiringOtherReservationCannotHideMultipleMembershipsInCallback() {
    UUID other = UUID.randomUUID();
    as(A, store.reserve(other, other, NOW.plusSeconds(5))).block(WAIT);
    var r = reserve(A, UUID.randomUUID());
    time.set(NOW.plusSeconds(4));
    assertThrows(
        DomainException.class,
        () ->
            as(
                    A,
                    store.commit(
                        r.operation(),
                        r.id(),
                        r.fence(),
                        MEMBER,
                        db ->
                            mutation(A, MEMBER)
                                .apply(db)
                                .then(mutation(A, UUID.randomUUID()).apply(db))
                                .then(Mono.fromRunnable(() -> time.set(NOW.plusSeconds(5))))))
                .block(WAIT));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, count("quota_effects"));
    assertEquals("1", usage(A).getReserved());
    assertEquals("1", usage(A).getUsed());
  }

  @Test
  void terminalReleaseReplaysOnlyOriginalFence() {
    var r = reserve(A, UUID.randomUUID());
    var released = as(A, store.release(r.operation(), r.id(), r.fence())).block(WAIT);
    assertEquals(released, as(A, store.release(r.operation(), r.id(), r.fence())).block(WAIT));
    assertThrows(
        DomainException.class,
        () -> as(A, store.release(r.operation(), r.id(), released.fence() + 10)).block(WAIT));
    assertEquals("0", usage(A).getReserved());
  }

  @Test
  void removalReconcilesPrimaryUsageWithinSameMembershipTransaction() {
    member(A, MEMBER, "ACTIVE");
    assertEquals("2", usage(A).getUsed());
    as(
            A,
            tx.execute(
                db ->
                    db.sql(
                            "UPDATE taska.tenant_memberships SET status='REMOVED',version=version+1"
                                + " WHERE tenant_id=:t AND user_id=:u")
                        .bind("t", A)
                        .bind("u", MEMBER)
                        .fetch()
                        .rowsUpdated()
                        .then(store.reconcileMemberships(db, A))
                        .thenReturn(true)))
        .block(WAIT);
    assertEquals(
        1L,
        operator
            .sql("SELECT used FROM taska.quota_counters WHERE tenant_id=:t AND resource='SEAT'")
            .bind("t", A)
            .map((r, m) -> r.get("used", Long.class))
            .one()
            .block(WAIT));
    assertEquals("1", usage(A).getUsed());
  }
}
