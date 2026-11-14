package ru.taska.issuestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.taska.security.tenant.IssueStorageTestContext.as;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.R2dbcException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.IssueServiceApplication;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.issuebootstrap.TenantStorageConfiguration;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Timeout(90)
class TenantIssueStorageIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      ACTOR = UUID.randomUUID(),
      PROJECT = UUID.randomUUID();
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final List<String> TABLES =
      List.of("issues", "project_counters", "idempotency_keys", "issue_history", "outbox_events");
  static ConnectionPool pool;
  static DatabaseClient raw, operator;
  static TenantTransactionExecutor tx;
  static TenantIssueStore store;
  static final AtomicBoolean missingTaskLimit = new AtomicBoolean();
  static final java.util.concurrent.atomic.AtomicBoolean
      unlimited = new java.util.concurrent.atomic.AtomicBoolean(true),
      bypass = new java.util.concurrent.atomic.AtomicBoolean(),
      online = new java.util.concurrent.atomic.AtomicBoolean(true),
      permitted = new java.util.concurrent.atomic.AtomicBoolean(true),
      retirement = new java.util.concurrent.atomic.AtomicBoolean();
  static final java.util.concurrent.atomic.AtomicLong
      entitlementVersion = new java.util.concurrent.atomic.AtomicLong(1),
      catalogLimit = new java.util.concurrent.atomic.AtomicLong(50),
      billingReads = new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicReference<
          java.util.List<ru.taska.api.b2b.v1.RestrictionCode>>
      restrictions = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());

  static LifetimeTaskQuota quota(Clock clock) {
    return new LifetimeTaskQuota(
        tx,
        (c, use, project) ->
            permitted.get() && (use != LifetimeTaskQuota.Use.RETIRE || retirement.get())
                ? Mono.just(new LifetimeTaskQuota.Grant(bypass.get()))
                : Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "current policy denied")),
        (tenant, min) ->
            Mono.defer(
                () -> {
                  billingReads.incrementAndGet();
                  if (!online.get())
                    return Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "offline"));
                  var e =
                      ru.taska.api.b2b.v1.Entitlement.newBuilder()
                          .setTenantId(tenant.toString())
                          .setVersion(Long.toString(entitlementVersion.get()))
                          .setPlanVersionId("30000000-0000-0000-0000-000000000001")
                          .setValidFrom("2020-01-01T00:00:00Z")
                          .setIssuedAt(clock.instant().toString())
                          .addAllRestrictions(restrictions.get());
                  for (var kind :
                      java.util.List.of(
                          ru.taska.api.b2b.v1.QuotaLimit.KindValue.KIND_SEAT,
                          ru.taska.api.b2b.v1.QuotaLimit.KindValue.KIND_PROJECT,
                          ru.taska.api.b2b.v1.QuotaLimit.KindValue.KIND_STORAGE_BYTES,
                          ru.taska.api.b2b.v1.QuotaLimit.KindValue.KIND_TASK_LIFETIME)) {
                    if (missingTaskLimit.get()
                        && kind == ru.taska.api.b2b.v1.QuotaLimit.KindValue.KIND_TASK_LIFETIME)
                      continue;
                    var limit = ru.taska.api.b2b.v1.QuotaLimit.newBuilder().setKind(kind);
                    if (unlimited.get()) limit.setUnlimited(true);
                    else limit.setLimit(Long.toString(catalogLimit.get()));
                    e.addLimits(limit);
                  }
                  return Mono.just(e.build());
                }),
        clock);
  }

  static final ObjectMapper json = new ObjectMapper();

  static ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, pg.getHost())
            .option(ConnectionFactoryOptions.PORT, pg.getMappedPort(5432))
            .option(ConnectionFactoryOptions.DATABASE, pg.getDatabaseName())
            .option(ConnectionFactoryOptions.USER, user)
            .option(ConnectionFactoryOptions.PASSWORD, password)
            .build());
  }

  @BeforeAll
  static void setup() throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    bootstrap(pg.getDatabaseName());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA issue_liquibase AUTHORIZATION taska_issue_migrator");
    sql("REVOKE ALL ON SCHEMA issue_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_issue_app PASSWORD '" + APP + "'");
    sql("ALTER ROLE taska_issue_deployer PASSWORD '" + DEPLOY + "'");
    sql("CREATE EXTENSION pg_trgm WITH SCHEMA public");
    migrate(pg.getJdbcUrl());
    migrate(pg.getJdbcUrl());
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_issue_app", APP))
                .initialSize(1)
                .maxSize(3)
                .maxAcquireTime(WAIT)
                .build());
    raw = DatabaseClient.create(pool);
    tx = new TenantTransactionExecutor(pool);
    store = new TenantIssueStore(tx, json, Clock.systemUTC(), quota(Clock.systemUTC()));
    new TenantStorageConfiguration().safeIssueRuntime(pool).afterPropertiesSet();
  }

  static void bootstrap(String db) throws Exception {
    var r =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            db,
            "-v",
            "service=issue",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, r.getExitCode(), r.getStderr());
  }

  static void migrate(String url) throws Exception {
    var migration =
        new TenantStorageConfiguration().issueTenantLiquibase(url, "taska_issue_deployer", DEPLOY);
    migration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
    migration.afterPropertiesSet();
  }

  static void sql(String text) {
    operator.sql(text).then().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void reset() {
    unlimited.set(true);
    missingTaskLimit.set(false);
    bypass.set(false);
    online.set(true);
    permitted.set(true);
    retirement.set(false);
    entitlementVersion.set(1);
    catalogLimit.set(50);
    billingReads.set(0);
    restrictions.set(java.util.List.of());
    sql(
        "TRUNCATE"
            + " taska.task_lifetime_quota_state,taska.task_quota_outbox,taska.issue_authorization_audit");
    sql(
        "TRUNCATE "
            + String.join(",", TABLES.stream().map(t -> "taska." + t).toList())
            + " CASCADE");
  }

  static String state(Throwable error) {
    for (var e = error; e != null; e = e.getCause())
      if (e instanceof R2dbcException r) return r.getSqlState();
    return null;
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  TenantIssueStore.CreateIssue command(UUID project, String summary) {
    return new TenantIssueStore.CreateIssue(
        project,
        "ABC",
        "TASK",
        summary,
        "private description",
        "TODO",
        "MEDIUM",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  Mono<TenantIssueStore.Created> create(
      UUID tenant, UUID actor, String operation, String key, TenantIssueStore.CreateIssue c) {
    return as(tenant, actor, operation, store.execute(s -> s.create(key, c)));
  }

  TenantIssueStore.Created create(UUID tenant, String key) {
    return create(tenant, ACTOR, "createIssue", key, command(PROJECT, "Private summary"))
        .block(WAIT);
  }

  void assertEmpty() {
    for (String table :
        java.util.stream.Stream.concat(
                TABLES.stream(),
                java.util.stream.Stream.of(
                    "task_lifetime_quota_state", "task_quota_outbox", "project_labels"))
            .toList()) assertEquals(0, count(table), table);
  }

  void awaitBlocked(java.util.concurrent.CompletableFuture<?> loser) {
    Long waiting =
        Mono.defer(
                () ->
                    operator
                        .sql(
                            "SELECT count(*) n FROM pg_stat_activity WHERE"
                                + " usename='taska_issue_app' AND wait_event_type='Lock' AND query"
                                + " LIKE '%INSERT INTO taska.idempotency_keys%'")
                        .map((r, m) -> r.get("n", Long.class))
                        .one())
            .repeatWhen(done -> done.delayElements(Duration.ofMillis(25)))
            .filter(n -> n > 0)
            .next()
            .block(WAIT);
    assertNotNull(waiting);
    assertFalse(loser.isDone());
  }

  @Test
  void tenantKeysAndIdempotencyAreIndependentAndUuidVersionStable() {
    var a = create(A, "same");
    var b = create(B, "same");
    assertEquals("ABC-1", a.key());
    assertEquals(a.key(), b.key());
    assertNotEquals(a.id(), b.id());
    assertEquals(1, a.version());
    assertEquals(a, create(A, "same"));
    assertEquals(2, count("issues"));
    assertEquals(2, count("project_counters"));
    assertNull(as(B, ACTOR, store.execute(s -> s.find(a.id()))).block(WAIT));
    assertEquals(List.of(a), as(A, ACTOR, store.execute(s -> s.list(PROJECT))).block(WAIT));
    assertEquals(
        1L,
        as(
                A,
                ACTOR,
                tx.execute(
                    db ->
                        db.sql("SELECT count(*) n FROM taska.issues WHERE tenant_id=:t")
                            .bind("t", A)
                            .map((r, m) -> r.get("n", Long.class))
                            .one()))
            .block(WAIT));
  }

  @Test
  void activeLookupByKeyAndIdNeverCrossesTenantOrFallsBackGlobally() {
    var a = create(A, "lookup-a");
    var b = create(B, "lookup-b");
    var own = as(A, ACTOR, store.execute(s -> s.findActiveByKey(a.key()))).block(WAIT);
    assertNotNull(own);
    assertEquals(a, own.identity());
    assertEquals("Private summary", own.summary());
    assertEquals("private description", own.description());
    assertEquals(ACTOR, own.reporterId());
    assertEquals("TASK", own.issueType());
    assertEquals("TODO", own.statusKey());
    assertEquals("MEDIUM", own.priority());
    assertNotNull(own.createdAt());
    assertNotNull(own.updatedAt());
    assertNull(own.assigneeId());
    assertNull(own.storyPoints());
    assertNull(own.startDate());
    assertNull(own.dueDate());
    assertNull(own.originalEstimateMinutes());
    assertNull(own.remainingEstimateMinutes());
    assertEquals(0, own.timeSpentMinutes());
    assertEquals(own, as(A, ACTOR, store.execute(s -> s.findActive(a.id()))).block(WAIT));
    assertEquals(
        b, as(B, ACTOR, store.execute(s -> s.findActiveByKey(a.key()))).block(WAIT).identity());
    assertNull(as(B, ACTOR, store.execute(s -> s.findActive(a.id()))).block(WAIT));
    var onlyA = create(A, "only-a");
    assertNull(as(B, ACTOR, store.execute(s -> s.findActiveByKey(onlyA.key()))).block(WAIT));
    assertEquals(3, count("issues"));
  }

  @Test
  void activeLookupHidesSoftDeletedIssueWithoutRemovingItsHistoryOrQuota() {
    var a = create(A, "deleted-lookup");
    sql("UPDATE taska.issues SET deleted_at=now() WHERE id='" + a.id() + "'");
    assertNull(as(A, ACTOR, store.execute(s -> s.findActive(a.id()))).block(WAIT));
    assertNull(as(A, ACTOR, store.execute(s -> s.findActiveByKey(a.key()))).block(WAIT));
    assertEquals(1, count("issues"));
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
    assertEquals(2, create(A, "after-deleted-lookup").number());
  }

  @Test
  void activeLookupValidatesIdentifiersAndRequiresVerifiedContext() {
    for (String key : List.of("", "ABC-0", "abc-1", "ABC-1' OR true --"))
      assertEquals(
          DomainStatus.INVALID_ARGUMENT,
          assertThrows(
                  DomainException.class,
                  () -> as(A, ACTOR, store.execute(s -> s.findActiveByKey(key))).block(WAIT))
              .getStatus());
    assertEquals(
        DomainStatus.INVALID_ARGUMENT,
        assertThrows(
                DomainException.class,
                () -> as(A, ACTOR, store.execute(s -> s.findActive(new UUID(0, 0)))).block(WAIT))
            .getStatus());
    assertEquals(
        DomainStatus.UNAUTHENTICATED,
        assertThrows(
                DomainException.class,
                () -> store.execute(s -> s.findActiveByKey("ABC-1")).block(WAIT))
            .getStatus());
    assertEmpty();
  }

  @Test
  void expiryMetadataDoesNotReuseAKeyBeforeExplicitCleanup() {
    var original = create(A, "expired");
    var future =
        Clock.fixed(Clock.systemUTC().instant().plus(Duration.ofDays(2)), java.time.ZoneOffset.UTC);
    assertTrue(
        operator
            .sql("SELECT expires_at FROM taska.idempotency_keys")
            .map((r, m) -> r.get("expires_at", java.time.OffsetDateTime.class))
            .one()
            .block(WAIT)
            .toInstant()
            .isBefore(future.instant()));
    var later = new TenantIssueStore(tx, json, future, quota(future));
    assertEquals(
        original,
        as(
                A,
                ACTOR,
                later.execute(
                    session -> session.create("expired", command(PROJECT, "Private summary"))))
            .block(WAIT));
    assertEquals(
        DomainStatus.ALREADY_EXISTS,
        assertThrows(
                DomainException.class,
                () ->
                    as(
                            A,
                            ACTOR,
                            later.execute(
                                session -> session.create("expired", command(PROJECT, "Changed"))))
                        .block(WAIT))
            .getStatus());
    for (String table : TABLES) assertEquals(1, count(table));
    assertEquals(2, create(A, "fresh-key").number());
  }

  @Test
  void actorAndVerifiedOperationHaveSeparateIdempotencyNamespaces() {
    var c = command(PROJECT, "Same");
    var first = create(A, ACTOR, "createIssue", "same", c).block(WAIT);
    var otherActor = create(A, UUID.randomUUID(), "createIssue", "same", c).block(WAIT);
    var otherOperation = create(A, ACTOR, "importIssue", "same", c).block(WAIT);
    assertEquals(
        List.of(1, 2, 3), List.of(first.number(), otherActor.number(), otherOperation.number()));
    assertEquals(3, count("idempotency_keys"));
  }

  @Test
  void concurrentUniqueRequestsAllocateDistinctNumbersInSameProject() {
    var rows =
        Flux.range(0, 24)
            .flatMap(
                i -> create(A, ACTOR, "createIssue", "key-" + i, command(PROJECT, "Issue " + i)), 8)
            .collectList()
            .block(WAIT);
    assertEquals(24, rows.stream().map(TenantIssueStore.Created::number).distinct().count());
    assertEquals(24, rows.stream().map(TenantIssueStore.Created::id).distinct().count());
    assertEquals(1, rows.stream().mapToInt(TenantIssueStore.Created::number).min().orElseThrow());
    assertEquals(24, rows.stream().mapToInt(TenantIssueStore.Created::number).max().orElseThrow());
    for (String table : List.of("issues", "idempotency_keys", "issue_history", "outbox_events"))
      assertEquals(24, count(table));
    assertEquals(1, count("project_counters"));
    assertEquals(
        24L,
        operator
            .sql("SELECT lifetime_created FROM taska.project_counters")
            .map((r, m) -> r.get("lifetime_created", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void sameKeyRaceWaitsForCommitAndReturnsOnlyOneDurableResult() throws Exception {
    Sinks.Empty<Void> held = Sinks.empty(), release = Sinks.empty();
    var c = command(PROJECT, "Concurrent");
    var winner =
        as(
                A,
                ACTOR,
                store.execute(
                    s ->
                        s.create("shared", c)
                            .flatMap(
                                r -> {
                                  held.tryEmitEmpty();
                                  return release.asMono().thenReturn(r);
                                })))
            .toFuture();
    held.asMono().block(WAIT);
    var loser = create(A, ACTOR, "createIssue", "shared", c).toFuture();
    try {
      awaitBlocked(loser);
    } finally {
      release.tryEmitEmpty();
    }
    assertEquals(winner.get(20, TimeUnit.SECONDS), loser.get(20, TimeUnit.SECONDS));
    for (String table : TABLES) assertEquals(1, count(table), table);
    assertEquals(2, create(A, "next").number());
  }

  @Test
  void failedWinnerRollsBackAndWaitingRetryTakesNumberOne() throws Exception {
    Sinks.Empty<Void> held = Sinks.empty(), release = Sinks.empty();
    var c = command(PROJECT, "Concurrent");
    var winner =
        as(
                A,
                ACTOR,
                store.execute(
                    s ->
                        s.create("shared", c)
                            .flatMap(
                                r -> {
                                  held.tryEmitEmpty();
                                  return release
                                      .asMono()
                                      .then(
                                          Mono.<TenantIssueStore.Created>error(
                                              new IllegalStateException("rollback fixture")));
                                })))
            .toFuture();
    held.asMono().block(WAIT);
    var loser = create(A, ACTOR, "createIssue", "shared", c).toFuture();
    try {
      awaitBlocked(loser);
    } finally {
      release.tryEmitEmpty();
    }
    assertThrows(ExecutionException.class, () -> winner.get(20, TimeUnit.SECONDS));
    assertEquals(1, loser.get(20, TimeUnit.SECONDS).number());
    for (String table : TABLES) assertEquals(1, count(table), table);
  }

  @Test
  void payloadAndTargetConflictPreserveEveryTableAndCounter() {
    var original = create(A, "key");
    for (var changed :
        List.of(
            command(PROJECT, "Changed summary"), command(UUID.randomUUID(), "Private summary"))) {
      var error =
          assertThrows(
              DomainException.class,
              () -> create(A, ACTOR, "createIssue", "key", changed).block(WAIT));
      assertEquals(DomainStatus.ALREADY_EXISTS, error.getStatus());
    }
    assertEquals(original, create(A, "key"));
    for (String table : TABLES) assertEquals(1, count(table));
    assertEquals(2, create(A, "second").number());
  }

  @Test
  void concurrentConflictingPayloadProducesOneSuccessAndOneConflict() {
    var results =
        Flux.merge(
                create(A, ACTOR, "createIssue", "same", command(PROJECT, "A")).materialize(),
                create(A, ACTOR, "createIssue", "same", command(PROJECT, "B")).materialize())
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
    var error =
        results.stream()
            .filter(reactor.core.publisher.Signal::isOnError)
            .findFirst()
            .orElseThrow()
            .getThrowable();
    assertInstanceOf(DomainException.class, error);
    assertEquals(DomainStatus.ALREADY_EXISTS, ((DomainException) error).getStatus());
    for (String table : TABLES) assertEquals(1, count(table));
  }

  @Test
  void outerRollbackAndWriteFailureDoNotConsumeCounterOrReceipt() {
    assertThrows(
        IllegalStateException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    store.execute(
                        s ->
                            s.create("outer", command(PROJECT, "Outer"))
                                .then(Mono.error(new IllegalStateException("fixture")))))
                .block(WAIT));
    assertEmpty();
    sql(
        "CREATE FUNCTION taska.fail_outbox_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'fixture'; END $$");
    sql(
        "CREATE TRIGGER fail_outbox_fixture BEFORE INSERT ON taska.outbox_events FOR EACH ROW"
            + " EXECUTE FUNCTION taska.fail_outbox_fixture()");
    try {
      assertThrows(RuntimeException.class, () -> create(A, "failure"));
      assertEmpty();
    } finally {
      sql("DROP TRIGGER fail_outbox_fixture ON taska.outbox_events");
      sql("DROP FUNCTION taska.fail_outbox_fixture()");
    }
    assertEquals(1, create(A, "failure").number());
  }

  @Test
  void deferredForeignKeyCommitFailureNeverEmitsSuccess() {
    AtomicBoolean emitted = new AtomicBoolean();
    assertThrows(
        RuntimeException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    store.execute(
                        s ->
                            s.create("commit", command(PROJECT, "Commit"))
                                .flatMap(
                                    r ->
                                        tx.execute(
                                                db ->
                                                    db.sql(
                                                            "UPDATE taska.idempotency_keys SET"
                                                                + " resource_id=:missing WHERE"
                                                                + " tenant_id=:t")
                                                        .bind("missing", UUID.randomUUID())
                                                        .bind("t", A)
                                                        .then())
                                            .thenReturn(r))))
                .doOnNext(r -> emitted.set(true))
                .block(WAIT));
    assertFalse(emitted.get());
    assertEmpty();
    assertEquals(1, create(A, "commit").number());
  }

  @Test
  void foreignParentAndForeignTenantWriteAreRejectedByPostgresAppLogin() {
    var a = create(A, "a");
    var fk =
        assertThrows(
            RuntimeException.class,
            () ->
                as(
                        B,
                        ACTOR,
                        tx.execute(
                            db ->
                                db.sql(
                                        "INSERT INTO"
                                            + " taska.issue_history(tenant_id,issue_id,event_type,actor_user_id,occurred_at)"
                                            + " VALUES(:t,:i,'CREATED',:a,now())")
                                    .bind("t", B)
                                    .bind("i", a.id())
                                    .bind("a", ACTOR)
                                    .then()))
                    .block(WAIT));
    assertEquals("23503", state(fk));
    var rls =
        assertThrows(
            RuntimeException.class,
            () ->
                as(
                        A,
                        ACTOR,
                        tx.execute(
                            db ->
                                db.sql(
                                        "INSERT INTO taska.project_counters(tenant_id,project_id)"
                                            + " VALUES(:t,:p)")
                                    .bind("t", B)
                                    .bind("p", UUID.randomUUID())
                                    .then()))
                    .block(WAIT));
    assertEquals("42501", state(rls));
    assertEquals(1, count("issues"));
    assertEquals(1, count("issue_history"));
    assertEquals(
        0L,
        as(
                B,
                ACTOR,
                tx.execute(
                    db ->
                        db.sql(
                                "UPDATE taska.issues SET summary='changed' WHERE tenant_id=:t AND"
                                    + " id=:id")
                            .bind("t", A)
                            .bind("id", a.id())
                            .fetch()
                            .rowsUpdated()))
            .block(WAIT));
  }

  @Test
  void missingAndActorlessContextCannotCreateAndEscapedSessionCannotRunSql() {
    assertEquals(
        DomainStatus.UNAUTHENTICATED,
        assertThrows(
                DomainException.class,
                () -> store.execute(s -> s.find(UUID.randomUUID())).block(WAIT))
            .getStatus());
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    as(A, null, store.execute(s -> s.create("key", command(PROJECT, "No actor"))))
                        .block(WAIT))
            .getStatus());
    var session = as(A, ACTOR, store.execute(Mono::just)).block(WAIT);
    assertThrows(
        DomainException.class, () -> as(A, ACTOR, session.find(UUID.randomUUID())).block(WAIT));
    assertEmpty();
    for (String table : TABLES)
      assertEquals(
          0L,
          raw.sql("SELECT count(*) n FROM taska." + table)
              .map((r, m) -> r.get("n", Long.class))
              .one()
              .block(WAIT));
  }

  @Test
  void migrationAndRuntimeHaveForceRlsAndNoPrivilegeBypass() throws Exception {
    assertEquals(
        "taska_issue_app",
        raw.sql("SELECT session_user AS u")
            .map((r, m) -> r.get("u", String.class))
            .one()
            .block(WAIT));
    for (String table :
        java.util.stream.Stream.concat(
                TABLES.stream(),
                java.util.stream.Stream.of(
                    "task_lifetime_quota_state", "task_quota_outbox", "project_labels"))
            .toList()) {
      assertTrue(
          operator
              .sql(
                  "SELECT relrowsecurity AND relforcerowsecurity AS enabled FROM pg_class WHERE"
                      + " oid=CAST(:table AS regclass)")
              .bind("table", "taska." + table)
              .map((r, m) -> r.get("enabled", Boolean.class))
              .one()
              .block(WAIT));
      assertThrows(
          RuntimeException.class, () -> raw.sql("TRUNCATE taska." + table).then().block(WAIT));
    }
    assertThrows(
        RuntimeException.class, () -> raw.sql("SET ROLE taska_issue_migrator").then().block(WAIT));
    assertThrows(
        IllegalStateException.class,
        () ->
            new TenantStorageConfiguration()
                .safeIssueRuntime(factory(pg.getUsername(), pg.getPassword()))
                .afterPropertiesSet());
    assertEquals(
        6L,
        operator
            .sql("SELECT count(*) n FROM issue_liquibase.databasechangelog")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void counterExhaustionRefusesWithoutPartialState() {
    create(A, "initial");
    sql("UPDATE taska.project_counters SET next_issue_number=2147483647");
    for (boolean admin : List.of(false, true)) {
      bypass.set(admin);
      assertEquals(
          DomainStatus.RESOURCE_EXHAUSTED,
          assertThrows(DomainException.class, () -> create(A, "full")).getStatus());
      assertEquals(1, lifetime(A, PROJECT));
    }
    for (String table : TABLES) assertEquals(1, count(table));
    assertEquals(
        2147483647,
        operator
            .sql("SELECT next_issue_number FROM taska.project_counters")
            .map((r, m) -> r.get("next_issue_number", Integer.class))
            .one()
            .block(WAIT));
  }

  @Test
  void caughtFailureStillMarksTheTransactionRollbackOnly() {
    create(A, "initial");
    sql("UPDATE taska.project_counters SET next_issue_number=2147483647");
    var error =
        assertThrows(
            DomainException.class,
            () ->
                as(
                        A,
                        ACTOR,
                        store.execute(
                            s ->
                                s.create("caught", command(PROJECT, "Caught"))
                                    .onErrorResume(
                                        DomainException.class,
                                        e ->
                                            Mono.just(
                                                new TenantIssueStore.Created(
                                                    UUID.randomUUID(), PROJECT, 7, "ABC-7", 1)))))
                    .block(WAIT));
    assertEquals(DomainStatus.ABORTED, error.getStatus());
    for (String table : TABLES) assertEquals(1, count(table));
  }

  @Test
  void canonicalPlanningPayloadReplaysAndChangedOptionalFieldConflicts() {
    var c =
        new TenantIssueStore.CreateIssue(
            PROJECT,
            "ABC",
            "TASK",
            "Planning",
            "description",
            "TODO",
            "HIGH",
            UUID.randomUUID(),
            new BigDecimal("2.0"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            60,
            30);
    var first = create(A, ACTOR, "createIssue", "planning", c).block(WAIT);
    var same =
        new TenantIssueStore.CreateIssue(
            c.projectId(),
            c.projectKey(),
            c.issueType(),
            c.summary(),
            c.description(),
            c.statusKey(),
            c.priority(),
            c.assigneeId(),
            new BigDecimal("2.00"),
            c.startDate(),
            c.dueDate(),
            c.originalEstimateMinutes(),
            c.remainingEstimateMinutes());
    assertEquals(first, create(A, ACTOR, "createIssue", "planning", same).block(WAIT));
    var changed =
        new TenantIssueStore.CreateIssue(
            c.projectId(),
            c.projectKey(),
            c.issueType(),
            c.summary(),
            null,
            c.statusKey(),
            c.priority(),
            c.assigneeId(),
            c.storyPoints(),
            c.startDate(),
            c.dueDate(),
            c.originalEstimateMinutes(),
            c.remainingEstimateMinutes());
    assertEquals(
        DomainStatus.ALREADY_EXISTS,
        assertThrows(
                DomainException.class,
                () -> create(A, ACTOR, "createIssue", "planning", changed).block(WAIT))
            .getStatus());
    assertEquals(1, count("issues"));
    assertFalse(
        operator
            .sql("SELECT payload::text p FROM taska.outbox_events")
            .map((r, m) -> r.get("p", String.class))
            .one()
            .block(WAIT)
            .contains("description"));
  }

  @Test
  void duplicateNaturalKeyRollsBackNewProjectCounter() {
    create(A, "first");
    var duplicate =
        assertThrows(
            RuntimeException.class,
            () ->
                create(
                        A,
                        ACTOR,
                        "createIssue",
                        "other-project",
                        command(UUID.randomUUID(), "Other project"))
                    .block(WAIT));
    assertEquals("23505", state(duplicate));
    for (String table : TABLES) assertEquals(1, count(table));
    assertEquals(2, create(A, "next").number());
  }

  @Test
  void tenantProjectQueryUsesLeadingIndex() {
    create(A, "a");
    create(B, "b");
    operator
        .sql(
            "INSERT INTO"
                + " taska.issues(tenant_id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)"
                + " SELECT :t,:p,n,'ABC-'||n,'TASK','Fixture','TODO','MEDIUM',:a FROM"
                + " generate_series(2,10000) n")
        .bind("t", B)
        .bind("p", PROJECT)
        .bind("a", ACTOR)
        .then()
        .block(WAIT);
    sql("ANALYZE taska.issues");
    String plan =
        as(
                A,
                ACTOR,
                tx.execute(
                    db ->
                        db.sql(
                                "EXPLAIN SELECT id FROM taska.issues WHERE tenant_id=:t AND"
                                    + " project_id=:p")
                            .bind("t", A)
                            .bind("p", PROJECT)
                            .map((r, m) -> r.get(0, String.class))
                            .all()
                            .collectList()
                            .map(lines -> String.join("\n", lines))))
            .block(WAIT);
    assertTrue(plan.contains("Index"), plan);
    assertTrue(plan.contains("tenant_id"), plan);
  }

  @Test
  void invalidCommandsAreRejectedBeforeAnyMutation() {
    for (var c : List.of(command(new UUID(0, 0), "Zero project"), command(PROJECT, " ")))
      assertEquals(
          DomainStatus.INVALID_ARGUMENT,
          assertThrows(
                  DomainException.class,
                  () -> create(A, ACTOR, "createIssue", "invalid", c).block(WAIT))
              .getStatus());
    assertEmpty();
  }

  long lifetime(UUID tenant, UUID project) {
    return operator
        .sql(
            "SELECT lifetime_created n FROM taska.project_counters WHERE tenant_id=:t AND"
                + " project_id=:p")
        .bind("t", tenant)
        .bind("p", project)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  ru.taska.api.b2b.v1.TenantTaskQuotaSummary quotaSummary(UUID tenant) {
    return as(
            tenant,
            ACTOR,
            "getTenantTaskQuotaSummary",
            quota(Clock.systemUTC())
                .summary(
                    ru.taska.api.b2b.v1.GetTenantTaskQuotaSummaryRequest.newBuilder()
                        .setTenantId(tenant.toString())
                        .build()))
        .block(WAIT);
  }

  @Test
  void lastSlotIsAtomicAndReplayNeedsCurrentAuthorityButNoBilling() {
    unlimited.set(false);
    create(A, "seed");
    sql("UPDATE taska.project_counters SET lifetime_created=49,next_issue_number=50");
    var outcomes =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    create(A, ACTOR, "createIssue", "last-" + i, command(PROJECT, "Last"))
                        .materialize(),
                2)
            .collectList()
            .block(WAIT);
    assertEquals(1, outcomes.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
    assertEquals(50, lifetime(A, PROJECT));
    assertEquals(2, count("issues"));
    assertEquals(2, count("idempotency_keys"));
    assertEquals(2, count("task_quota_outbox"));
    var winning =
        outcomes.stream()
            .filter(reactor.core.publisher.Signal::isOnNext)
            .findFirst()
            .orElseThrow()
            .get();
    String winningKey =
        operator
            .sql("SELECT key FROM taska.idempotency_keys WHERE resource_id=:id")
            .bind("id", winning.id())
            .map((r, m) -> r.get("key", String.class))
            .one()
            .block(WAIT);
    long reads = billingReads.get();
    online.set(false);
    assertEquals(
        winning, create(A, ACTOR, "createIssue", winningKey, command(PROJECT, "Last")).block(WAIT));
    assertEquals(reads, billingReads.get());
    assertEquals(50, lifetime(A, PROJECT));
    permitted.set(false);
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    create(A, ACTOR, "createIssue", winningKey, command(PROJECT, "Last"))
                        .block(WAIT))
            .getStatus());
  }

  @Test
  void paidCountsAllTypesAndDeletionNeverRestoresQuotaOrResetsTheMonth() {
    for (String type : List.of("TASK", "BUG", "EPIC")) {
      var c = command(PROJECT, type);
      c =
          new TenantIssueStore.CreateIssue(
              c.projectId(),
              c.projectKey(),
              type,
              c.summary(),
              c.description(),
              c.statusKey(),
              c.priority(),
              c.assigneeId(),
              c.storyPoints(),
              c.startDate(),
              c.dueDate(),
              c.originalEstimateMinutes(),
              c.remainingEstimateMinutes());
      create(A, ACTOR, "createIssue", type, c).block(WAIT);
    }
    sql("UPDATE taska.issues SET deleted_at=now(),issue_type='BUG',status_key='DONE'");
    assertEquals(3, lifetime(A, PROJECT));
    var future =
        Clock.fixed(
            Clock.systemUTC().instant().plus(Duration.ofDays(400)), java.time.ZoneOffset.UTC);
    var later = new TenantIssueStore(tx, json, future, quota(future));
    assertEquals(
        4,
        as(A, ACTOR, later.execute(s -> s.create("next-year", command(PROJECT, "Later"))))
            .block(WAIT)
            .number());
    assertEquals(4, lifetime(A, PROJECT));
    assertEquals(4, Long.parseLong(quotaSummary(A).getMaximumCreated()));
  }

  @Test
  void equalityOnlyStopsThisProjectAndExcessDowngradeIsTenantWideUntilPaidOrRetired() {
    create(A, "seed");
    sql("UPDATE taska.project_counters SET lifetime_created=50");
    unlimited.set(false);
    entitlementVersion.set(2);
    assertFalse(quotaSummary(A).getOverLimit());
    UUID other = UUID.randomUUID();
    create(
            A,
            ACTOR,
            "createIssue",
            "other",
            new TenantIssueStore.CreateIssue(
                other, "OT", "TASK", "Other", null, "OPEN", "MEDIUM", null, null, null, null, null,
                null))
        .block(WAIT);
    assertEquals(
        DomainStatus.FAILED_PRECONDITION,
        assertThrows(DomainException.class, () -> create(A, "limit")).getStatus());
    bypass.set(true);
    create(A, "admin");
    bypass.set(false);
    assertEquals(51, lifetime(A, PROJECT));
    assertTrue(quotaSummary(A).getOverLimit());
    assertEquals(
        DomainStatus.FAILED_PRECONDITION,
        assertThrows(
                DomainException.class,
                () ->
                    create(
                            A,
                            ACTOR,
                            "createIssue",
                            "other2",
                            new TenantIssueStore.CreateIssue(
                                other, "OT", "TASK", "Other", null, "OPEN", "MEDIUM", null, null,
                                null, null, null, null))
                        .block(WAIT))
            .getStatus());
    assertEquals(1, lifetime(A, other));
    unlimited.set(true);
    entitlementVersion.set(3);
    assertFalse(quotaSummary(A).getOverLimit());
    create(A, "paid");
    assertEquals(52, lifetime(A, PROJECT));
    unlimited.set(false);
    entitlementVersion.set(4);
    assertThrows(
        DomainException.class,
        () ->
            as(A, ACTOR, tx.execute(db -> quota(Clock.systemUTC()).retire(db, PROJECT)))
                .block(WAIT));
    retirement.set(true);
    online.set(false);
    as(A, ACTOR, tx.execute(db -> quota(Clock.systemUTC()).retire(db, PROJECT))).block(WAIT);
    online.set(true);
    assertFalse(quotaSummary(A).getOverLimit());
    assertEquals(52, lifetime(A, PROJECT));
    assertThrows(
        Exception.class,
        () ->
            sql(
                "UPDATE taska.project_counters SET retired_at=NULL WHERE project_id='"
                    + PROJECT
                    + "'"));
    assertThrows(
        Exception.class,
        () -> sql("DELETE FROM taska.project_counters WHERE project_id='" + PROJECT + "'"));
    assertThrows(
        Exception.class,
        () ->
            sql(
                "UPDATE taska.project_counters SET lifetime_created=0 WHERE project_id='"
                    + PROJECT
                    + "'"));
    assertThrows(DomainException.class, () -> create(A, "resurrect"));
    UUID fresh = UUID.randomUUID();
    assertEquals(
        1,
        create(
                A,
                ACTOR,
                "createIssue",
                "new-project",
                new TenantIssueStore.CreateIssue(
                    fresh, "NP", "TASK", "New", null, "OPEN", "MEDIUM", null, null, null, null,
                    null, null))
            .block(WAIT)
            .number());
  }

  @Test
  void quotaFailuresRollbackAllStateAndReadOnlyReasonsRemainIndependent() {
    unlimited.set(false);
    create(A, "seed");
    long outbox = count("task_quota_outbox");
    assertThrows(
        Exception.class,
        () ->
            as(
                    A,
                    ACTOR,
                    store.execute(
                        s ->
                            s.create("rollback", command(PROJECT, "Fail"))
                                .then(Mono.error(new IllegalStateException("abort")))))
                .block(WAIT));
    assertEquals(1, lifetime(A, PROJECT));
    assertEquals(outbox, count("task_quota_outbox"));
    assertEquals(1, count("idempotency_keys"));
    entitlementVersion.set(0);
    assertThrows(DomainException.class, () -> create(A, "invalid-snapshot"));
    entitlementVersion.set(2);
    restrictions.set(List.of(ru.taska.api.b2b.v1.RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE));
    assertFalse(quotaSummary(A).getOverLimit());
    assertThrows(DomainException.class, () -> create(A, "dispute"));
    unlimited.set(true);
    entitlementVersion.set(3);
    assertFalse(quotaSummary(A).getOverLimit());
    assertThrows(DomainException.class, () -> create(A, "paid-dispute"));
    bypass.set(true);
    create(A, "admin-dispute");
    assertEquals(2, lifetime(A, PROJECT));
    online.set(false);
    assertThrows(DomainException.class, () -> create(A, "offline"));
    assertEquals(2, lifetime(A, PROJECT));
  }

  @Test
  void liveCounterKeysCannotMoveAndMaximumUsesAPartialIndex() {
    sql(
        "INSERT INTO taska.project_counters(tenant_id,project_id) VALUES('"
            + A
            + "','"
            + PROJECT
            + "')");
    assertThrows(
        Exception.class,
        () -> sql("UPDATE taska.project_counters SET project_id='" + UUID.randomUUID() + "'"));
    assertThrows(
        Exception.class, () -> sql("UPDATE taska.project_counters SET tenant_id='" + B + "'"));
    assertEquals(0, lifetime(A, PROJECT));
    var plan =
        as(
                A,
                ACTOR,
                tx.execute(
                    db ->
                        db.sql("SET LOCAL enable_seqscan=off")
                            .then()
                            .then(
                                db.sql(
                                        "EXPLAIN SELECT max(lifetime_created) FROM"
                                            + " taska.project_counters WHERE tenant_id=:t AND"
                                            + " retired_at IS NULL")
                                    .bind("t", A)
                                    .map((row, m) -> row.get(0, String.class))
                                    .all()
                                    .collectList()
                                    .map(lines -> String.join("\n", lines)))))
            .block(WAIT);
    assertTrue(plan.contains("project_lifetime_max_live_idx"), plan);
    missingTaskLimit.set(true);
    assertEquals(
        DomainStatus.INVALID_ARGUMENT,
        assertThrows(DomainException.class, () -> create(A, "missing-limit")).getStatus());
    assertEquals(0, lifetime(A, PROJECT));
    assertEquals(0, count("idempotency_keys"));
    assertEquals(0, count("task_quota_outbox"));
  }

  @Test
  void emptyDeletedProjectRetainsATombstoneEvenWithoutBilling() {
    online.set(false);
    retirement.set(true);
    as(A, ACTOR, tx.execute(db -> quota(Clock.systemUTC()).retire(db, PROJECT))).block(WAIT);
    assertEquals(0, lifetime(A, PROJECT));
    var event =
        operator
            .sql("SELECT entitlement_version,over_limit FROM taska.task_quota_outbox")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(0L, event.get("entitlement_version"));
    assertNull(event.get("over_limit"));
    online.set(true);
    assertEquals(
        DomainStatus.NOT_FOUND,
        assertThrows(DomainException.class, () -> create(A, "recreate")).getStatus());
    assertEquals(0, lifetime(A, PROJECT));
    assertEquals(0, count("issues"));
  }

  @Test
  void projectQuotaReadsAreScopedCurrentAndNewProjectStartsAtZero() {
    UUID empty = UUID.randomUUID();
    var request =
        ru.taska.api.b2b.v1.GetProjectTaskQuotaRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .setProjectId(empty.toString())
            .build();
    var q = quota(Clock.systemUTC());
    assertEquals(
        "0", as(A, ACTOR, "getProjectTaskQuota", q.project(request)).block(WAIT).getUsed());
    create(B, "other-tenant");
    assertThrows(
        DomainException.class,
        () -> as(B, ACTOR, "getProjectTaskQuota", q.project(request)).block(WAIT));
    permitted.set(false);
    assertThrows(
        DomainException.class,
        () -> as(A, ACTOR, "getProjectTaskQuota", q.project(request)).block(WAIT));
    permitted.set(true);
    var closed = new TenantIssueStore(tx, json, Clock.systemUTC());
    assertEquals(
        DomainStatus.UNAVAILABLE,
        assertThrows(
                DomainException.class,
                () ->
                    as(A, ACTOR, closed.execute(s -> s.create("closed", command(PROJECT, "No"))))
                        .block(WAIT))
            .getStatus());
    assertEquals(0, Long.parseLong(quotaSummary(A).getMaximumCreated()));
  }

  @Test
  void cleanStartRefusesLegacyDatabaseWithoutChangingItsRows() throws Exception {
    sql("CREATE DATABASE issue_legacy_fixture");
    try {
      bootstrap("issue_legacy_fixture");
      var result =
          pg.execInContainer(
              "psql",
              "-X",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              pg.getUsername(),
              "-d",
              "issue_legacy_fixture",
              "-c",
              "CREATE SCHEMA issue_liquibase AUTHORIZATION taska_issue_migrator; CREATE TABLE"
                  + " taska.issues(id uuid); INSERT INTO taska.issues VALUES(gen_random_uuid())");
      assertEquals(0, result.getExitCode(), result.getStderr());
      assertThrows(
          Exception.class,
          () -> migrate(pg.getJdbcUrl().replace(pg.getDatabaseName(), "issue_legacy_fixture")));
      result =
          pg.execInContainer(
              "psql",
              "-X",
              "-tA",
              "-U",
              pg.getUsername(),
              "-d",
              "issue_legacy_fixture",
              "-c",
              "SELECT count(*) FROM taska.issues");
      assertEquals("1", result.getStdout().strip());
    } finally {
      sql("DROP DATABASE issue_legacy_fixture WITH (FORCE)");
    }
  }

  @Test
  void tenantProfileStartsHealthyWithoutLegacyRepositoriesOrDispatchers() throws Exception {
    try (var app =
        new SpringApplicationBuilder(IssueServiceApplication.class)
            .web(WebApplicationType.REACTIVE)
            .profiles("tenant-storage")
            .run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.r2dbc.url=r2dbc:postgresql://"
                    + pg.getHost()
                    + ":"
                    + pg.getMappedPort(5432)
                    + "/"
                    + pg.getDatabaseName(),
                "--spring.r2dbc.username=taska_issue_app",
                "--spring.r2dbc.password=" + APP,
                "--spring.liquibase.url=" + pg.getJdbcUrl(),
                "--spring.liquibase.user=taska_issue_deployer",
                "--spring.liquibase.password=" + DEPLOY,
                "--spring.grpc.server.port=0",
                "--spring.grpc.server.host=127.0.0.1")) {
      assertNotNull(app.getBean(TenantIssueStore.class));
      assertTrue(app.getBeansOfType(ru.taska.repository.IssueRepository.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.service.impl.IssueServiceImpl.class).isEmpty());
      assertTrue(app.getBeansOfType(ru.taska.config.IssueOutboxConfig.class).isEmpty());
      int port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
      var response =
          java.net.http.HttpClient.newHttpClient()
              .send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                      .GET()
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("UP"));
    }
  }

  TenantIssueAccess access;
  TenantIssueApi api;
  boolean writes = true, adminAudit = false;
  java.time.Instant expiry;
  final UUID assignee = UUID.randomUUID();

  @BeforeEach
  void businessBoundary() {
    expiry = java.time.Instant.now().plusSeconds(30);
    access = org.mockito.Mockito.mock(TenantIssueAccess.class);
    org.mockito.Mockito.when(
            access.authorize(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenAnswer(
            call ->
                ru.taska.security.tenant.VerifiedTenantContext.current()
                    .flatMap(
                        c -> {
                          var action = call.getArgument(0, ru.taska.capability.Action.class);
                          var project = call.getArgument(1, UUID.class);
                          if (!project.equals(PROJECT))
                            return Mono.error(
                                new DomainException(DomainStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
                          if (!writes
                              && action != ru.taska.capability.Action.ISSUE_READ
                              && action != ru.taska.capability.Action.ISSUE_HISTORY)
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.PERMISSION_DENIED, "CURRENT_ACL_DENIED"));
                          return Mono.just(
                              new TenantIssueAccess.Permit(
                                  UUID.fromString(c.tenantId()),
                                  UUID.fromString(c.actorId()),
                                  c.action(),
                                  project,
                                  expiry,
                                  false,
                                  adminAudit,
                                  adminAudit ? java.util.Set.of("READ_ONLY") : java.util.Set.of()));
                        }));
    org.mockito.Mockito.when(access.projectKey(PROJECT)).thenReturn(Mono.just("ABC"));
    org.mockito.Mockito.when(
            access.initialStatus(
                org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.eq("TASK"),
                org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(Mono.just("TODO"));
    org.mockito.Mockito.when(
            access.assignee(
                org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.nullable(UUID.class)))
        .thenAnswer(
            call ->
                call.getArgument(1) == null || assignee.equals(call.getArgument(1))
                    ? Mono.empty()
                    : Mono.error(
                        new DomainException(DomainStatus.INVALID_ARGUMENT, "ASSIGNEE_NOT_ACTIVE")));
    org.mockito.Mockito.when(
            access.transition(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            call ->
                "TODO".equals(call.getArgument(0, TenantIssueStore.Snapshot.class).statusKey())
                    ? Mono.just("DONE")
                    : Mono.error(
                        new DomainException(
                            DomainStatus.FAILED_PRECONDITION, "TRANSITION_DENIED")));
    org.mockito.Mockito.when(access.readMany(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(
            call ->
                ru.taska.security.tenant.VerifiedTenantContext.current()
                    .map(
                        c -> {
                          java.util.List<UUID> ids = call.getArgument(0);
                          return new TenantIssueAccess.ReadBatch(
                              ids.stream()
                                  .map(
                                      project ->
                                          new TenantIssueAccess.Permit(
                                              UUID.fromString(c.tenantId()),
                                              UUID.fromString(c.actorId()),
                                              c.action(),
                                              project,
                                              expiry,
                                              false,
                                              false,
                                              java.util.Set.of()))
                                  .toList(),
                              50L,
                              false);
                        }));
    api = new TenantIssueApi(store, access);
  }

  ru.taska.api.issue.v1.CreateIssueRequestBody createRequest(String key) {
    return ru.taska.api.issue.v1.CreateIssueRequestBody.newBuilder()
        .setIdempotencyKey(key)
        .setProjectId(PROJECT.toString())
        .setReporterId(ACTOR.toString())
        .setIssueTypeKey("TASK")
        .setSummary("Native issue")
        .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_MEDIUM)
        .build();
  }

  TenantIssueStore.Snapshot nativeCreate(String key) {
    return as(A, ACTOR, "createIssue", api.create(createRequest(key))).block(WAIT);
  }

  ru.taska.api.issue.v1.PatchIssueRequestBody patch(TenantIssueStore.Snapshot r, String summary) {
    return ru.taska.api.issue.v1.PatchIssueRequestBody.newBuilder()
        .setIssueId(r.identity().id().toString())
        .setActorUserId(ACTOR.toString())
        .setVersion(r.identity().version())
        .setSummary(summary)
        .build();
  }

  DomainException denied(Mono<?> work, String operation, DomainStatus expected) {
    var e = assertThrows(DomainException.class, () -> as(A, ACTOR, operation, work).block(WAIT));
    assertEquals(expected, e.getStatus());
    return e;
  }

  @Test
  void createPatchReplayAndHistoryRemainExactAfterLaterChanges() {
    var first = nativeCreate("native-create");
    var q = patch(first, "Changed");
    var changed = as(A, ACTOR, "patchIssue", api.patch(q, "patch-1")).block(WAIT);
    assertEquals(2, changed.identity().version());
    var newer =
        as(A, ACTOR, "patchIssue", api.patch(patch(changed, "Newer"), "patch-2")).block(WAIT);
    assertEquals(3, newer.identity().version());
    assertEquals(changed, as(A, ACTOR, "patchIssue", api.patch(q, "patch-1")).block(WAIT));
    assertEquals(first, nativeCreate("native-create"));
    assertEquals(3, count("issue_history"));
    assertEquals(3, count("outbox_events"));
    assertEquals(1, count("project_counters"));
    assertEquals(2, create(A, "next-create").number());
    denied(
        api.patch(q.toBuilder().setSummary("Conflict").build(), "patch-1"),
        "patchIssue",
        DomainStatus.ALREADY_EXISTS);
    assertEquals(4, count("issue_history"));
  }

  @Test
  void foreignIdsActorMismatchCurrentAclAndInvalidAssigneeLeaveStateUntouched() {
    var first = nativeCreate("access");
    assertNull(as(B, ACTOR, store.execute(s -> s.findActive(first.identity().id()))).block(WAIT));
    assertEquals(
        DomainStatus.NOT_FOUND,
        assertThrows(
                DomainException.class,
                () ->
                    as(
                            B,
                            ACTOR,
                            "getIssue",
                            api.get(first.identity().id().toString(), ACTOR.toString()))
                        .block(WAIT))
            .getStatus());
    denied(
        api.patch(
            patch(first, "x").toBuilder().setActorUserId(UUID.randomUUID().toString()).build(),
            "forged"),
        "patchIssue",
        DomainStatus.PERMISSION_DENIED);
    writes = false;
    denied(api.patch(patch(first, "x"), "revoked"), "patchIssue", DomainStatus.PERMISSION_DENIED);
    writes = true;
    var bad =
        ru.taska.api.issue.v1.AssignIssueRequestBody.newBuilder()
            .setIssueId(first.identity().id().toString())
            .setActorUserId(ACTOR.toString())
            .setAssigneeId(UUID.randomUUID().toString())
            .build();
    denied(api.assign(bad, "bad-assignee"), "assignIssue", DomainStatus.INVALID_ARGUMENT);
    assertEquals(
        first,
        as(A, ACTOR, "getIssue", api.get(first.identity().id().toString(), ACTOR.toString()))
            .block(WAIT));
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
    assertEquals(1, count("idempotency_keys"));
  }

  @Test
  void transitionAndSoftDeleteAreIdempotentAndDoNotReclaimLifetimeQuota() {
    var first = nativeCreate("transition");
    var q =
        ru.taska.api.issue.v1.TransitionIssueRequestBody.newBuilder()
            .setIssueId(first.identity().id().toString())
            .setActorUserId(ACTOR.toString())
            .setTransitionId(UUID.randomUUID().toString())
            .setExpectedVersion(1)
            .build();
    var done = as(A, ACTOR, "transitionIssue", api.transition(q, "transition-1")).block(WAIT);
    assertEquals("DONE", done.statusKey());
    assertEquals(
        done, as(A, ACTOR, "transitionIssue", api.transition(q, "transition-1")).block(WAIT));
    org.mockito.Mockito.verify(access, org.mockito.Mockito.times(1))
        .transition(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    var delete =
        ru.taska.api.issue.v1.DeleteIssueRequestBody.newBuilder()
            .setIssueId(first.identity().id().toString())
            .setActorUserId(ACTOR.toString())
            .build();
    var tombstone = as(A, ACTOR, "deleteIssue", api.delete(delete, "delete-1")).block(WAIT);
    assertNotNull(tombstone.deletedAt());
    assertEquals(
        tombstone, as(A, ACTOR, "deleteIssue", api.delete(delete, "delete-1")).block(WAIT));
    denied(
        api.getByKey(first.identity().key(), ACTOR.toString()),
        "getIssueByKey",
        DomainStatus.NOT_FOUND);
    assertEquals(3, count("issue_history"));
    assertEquals(3, count("outbox_events"));
    assertEquals(1, count("issues"));
    assertEquals(2, create(A, "after-delete").number());
  }

  @Test
  void staleVersionAndExpiredAuthorityDoNotLeavePendingReceipts() {
    var first = nativeCreate("version");
    var updated = as(A, ACTOR, "patchIssue", api.patch(patch(first, "won"), "winner")).block(WAIT);
    denied(api.patch(patch(first, "lost"), "loser"), "patchIssue", DomainStatus.ABORTED);
    expiry = java.time.Instant.now().minusSeconds(1);
    denied(api.patch(patch(updated, "expired"), "expired"), "patchIssue", DomainStatus.UNAVAILABLE);
    assertEquals(2, count("idempotency_keys"));
    assertEquals(2, count("issue_history"));
    assertEquals(2, count("outbox_events"));
  }

  @Test
  void outboxFailureRollsBackIssueVersionHistoryReceiptAndAudit() {
    var first = nativeCreate("rollback");
    sql(
        "ALTER TABLE taska.outbox_events ADD CONSTRAINT test_deny_update"
            + " CHECK(event_type<>'UPDATED') NOT VALID");
    adminAudit = true;
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              as(A, ACTOR, "patchIssue", api.patch(patch(first, "must rollback"), "failure"))
                  .block(WAIT));
    } finally {
      sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT test_deny_update");
    }
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
    assertEquals(1, count("idempotency_keys"));
    assertEquals(0, count("issue_authorization_audit"));
    adminAudit = false;
    assertEquals(
        first,
        as(A, ACTOR, "getIssue", api.get(first.identity().id().toString(), ACTOR.toString()))
            .block(WAIT));
  }

  @Test
  void adminAuthorizationAuditIsCommittedWithMutationAndCannotBeChangedByApp() {
    adminAudit = true;
    var first = nativeCreate("audit");
    assertEquals(1, count("issue_authorization_audit"));
    as(A, ACTOR, "patchIssue", api.patch(patch(first, "audit changed"), "audit-patch")).block(WAIT);
    assertEquals(2, count("issue_authorization_audit"));
    assertThrows(
        RuntimeException.class,
        () ->
            as(
                    A,
                    ACTOR,
                    tx.execute(
                        db ->
                            db.sql("DELETE FROM taska.issue_authorization_audit WHERE tenant_id=:t")
                                .bind("t", A)
                                .fetch()
                                .rowsUpdated()))
                .block(WAIT));
    assertEquals(2, count("issue_authorization_audit"));
  }

  @Test
  void concurrentPatchesHaveOneWinnerAndNoDuplicateHistory() {
    var first = nativeCreate("race");
    var q = patch(first, "winner");
    var outcomes =
        Flux.just("race-a", "race-b")
            .flatMap(
                key ->
                    as(A, ACTOR, "patchIssue", api.patch(q, key))
                        .map(v -> "PASS")
                        .onErrorResume(DomainException.class, e -> Mono.just(e.getStatus().name())),
                2)
            .collectList()
            .block(WAIT);
    assertEquals(1, outcomes.stream().filter("PASS"::equals).count());
    assertEquals(1, outcomes.stream().filter("ABORTED"::equals).count());
    assertEquals(2, count("issue_history"));
    assertEquals(2, count("outbox_events"));
    assertEquals(2, count("idempotency_keys"));
  }

  @Test
  void assignmentAndNullablePatchPreserveOtherFieldsAndSupportUnassignment() {
    var first = nativeCreate("assign");
    var q =
        ru.taska.api.issue.v1.AssignIssueRequestBody.newBuilder()
            .setIssueId(first.identity().id().toString())
            .setActorUserId(ACTOR.toString())
            .setAssigneeId(assignee.toString())
            .build();
    var assigned = as(A, ACTOR, "assignIssue", api.assign(q, "assign-1")).block(WAIT);
    assertEquals(assignee, assigned.assigneeId());
    var clear =
        patch(assigned, "same issue").toBuilder()
            .setAssigneeId(ru.taska.api.common.v1.NullableString.newBuilder().setIsNull(true))
            .setDescription(ru.taska.api.common.v1.NullableString.newBuilder().setIsNull(true))
            .build();
    var result = as(A, ACTOR, "patchIssue", api.patch(clear, "clear")).block(WAIT);
    assertNull(result.assigneeId());
    assertNull(result.description());
    assertEquals("TODO", result.statusKey());
  }

  @Test
  void batchQuotaCountsLifetimeCreationsAndKeepsEmptyAccessibleProjects() {
    var first = nativeCreate("bulk-usage");
    var q =
        ru.taska.api.issue.v1.DeleteIssueRequestBody.newBuilder()
            .setIssueId(first.identity().id().toString())
            .setActorUserId(ACTOR.toString())
            .build();
    as(A, ACTOR, "deleteIssue", api.delete(q, "bulk-delete")).block(WAIT);
    UUID empty = UUID.randomUUID();
    var batch =
        ru.taska.api.issue.v1.GetProjectTaskQuotasRequest.newBuilder()
            .addProjectIds(PROJECT.toString())
            .addProjectIds(empty.toString())
            .build();
    var result = as(A, ACTOR, "getProjectTaskQuotas", api.quotas(batch)).block(WAIT);
    assertEquals(2, result.getItemsCount());
    assertEquals(1, result.getItems(0).getUsed());
    assertEquals(50, result.getItems(0).getLimit());
    assertFalse(result.getItems(0).getUnlimited());
    assertEquals(0, result.getItems(1).getUsed());
    var counts =
        as(
                A,
                ACTOR,
                "countProjectIssuesBatchInternal",
                api.counts(
                    ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest.newBuilder()
                        .addProjectIds(PROJECT.toString())
                        .build()))
            .block(WAIT);
    assertEquals(0, counts.getItems(0).getIssueCount());
    assertTrue(counts.getFiltersApplied());
    var other = as(B, ACTOR, "getProjectTaskQuotas", api.quotas(batch)).block(WAIT);
    assertEquals(0, other.getItems(0).getUsed());
  }

  @Test
  void workflowDenialAndAuditFailureLeaveNoIssueHistoryOutboxOrQuota() {
    org.mockito.Mockito.when(
            access.initialStatus(
                org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.eq("TASK"),
                org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(
            Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "WORKFLOW_DENIED")));
    denied(
        api.create(createRequest("workflow-denied")),
        "createIssue",
        DomainStatus.FAILED_PRECONDITION);
    assertEmpty();
    org.mockito.Mockito.when(
            access.initialStatus(
                org.mockito.ArgumentMatchers.eq(PROJECT),
                org.mockito.ArgumentMatchers.eq("TASK"),
                org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(Mono.just("TODO"));
    adminAudit = true;
    sql(
        "ALTER TABLE taska.issue_authorization_audit ADD CONSTRAINT test_deny_create_audit"
            + " CHECK(action<>'createIssue') NOT VALID");
    try {
      assertThrows(RuntimeException.class, () -> nativeCreate("audit-failed-create"));
    } finally {
      sql("ALTER TABLE taska.issue_authorization_audit DROP CONSTRAINT test_deny_create_audit");
    }
    assertEmpty();
    assertEquals(0, count("issue_authorization_audit"));
  }

  @Test
  void waitingForIssueLockCannotExtendAnAuthorizationLease() throws Exception {
    var first = nativeCreate("lease-lock");
    var held = new java.util.concurrent.CountDownLatch(1);
    var release = Sinks.<Void>one();
    var blocker =
        as(
                A,
                ACTOR,
                "holdIssue",
                store.execute(
                    s ->
                        s.stored(first.identity().id(), true)
                            .doOnNext(r -> held.countDown())
                            .then(release.asMono())))
            .toFuture();
    assertTrue(held.await(5, TimeUnit.SECONDS));
    expiry = java.time.Instant.now().plusMillis(300);
    var waiting =
        as(A, ACTOR, "patchIssue", api.patch(patch(first, "expired while waiting"), "wait-expiry"))
            .toFuture();
    try {
      Mono.delay(Duration.ofMillis(700)).block(WAIT);
    } finally {
      release.tryEmitEmpty();
      blocker.get(5, TimeUnit.SECONDS);
    }
    var failure =
        assertThrows(
            java.util.concurrent.ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
    assertEquals(
        DomainStatus.UNAVAILABLE,
        assertInstanceOf(DomainException.class, failure.getCause()).getStatus());
    assertEquals(1, count("idempotency_keys"));
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
  }
}
