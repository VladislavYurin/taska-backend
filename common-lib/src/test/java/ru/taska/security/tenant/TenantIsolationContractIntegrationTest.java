package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.security.tenant.TenantIsolationFixture.*;

import io.r2dbc.spi.R2dbcException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;

/** Exercises shipped RLS templates through the real executor/driver/pool as app LOGIN. */
@Timeout(60)
class TenantIsolationContractIntegrationTest {
  static TenantIsolationFixture f;

  @BeforeAll
  static void start() throws Exception {
    f = new TenantIsolationFixture(1);
  }

  @AfterAll
  static void stop() {
    if (f != null) f.close();
  }

  @BeforeEach
  void seed() {
    f.reset(3);
  }

  @AfterEach
  void clean() {
    f.assertCleanSingleConnection();
  }

  static <T> T run(UUID tenant, Function<DatabaseClient, Mono<T>> work) {
    return as(tenant, f.executor.execute(work)).block(WAIT);
  }

  static void sqlState(String code, Mono<?> work) {
    StepVerifier.create(work)
        .expectErrorSatisfies(
            error -> {
              Throwable cause = error;
              while (!(cause instanceof R2dbcException) && cause.getCause() != null)
                cause = cause.getCause();
              assertInstanceOf(R2dbcException.class, cause);
              assertEquals(code, ((R2dbcException) cause).getSqlState());
            })
        .verify(WAIT);
  }

  static Mono<Long> insert(DatabaseClient db, UUID id, UUID tenant, UUID project, long number) {
    return db.sql("INSERT INTO taska.issues VALUES (:id,:tenant,:project,:number)")
        .bind("id", id)
        .bind("tenant", tenant)
        .bind("project", project)
        .bind("number", number)
        .fetch()
        .rowsUpdated();
  }

  @Test
  void runtimeLoginAndMissingContextFailClosed() {
    var state = run(A, TenantIsolationFixture::snapshot);
    assertEquals("taska_project_app", state.login());
    assertEquals(state.login(), state.role());
    assertEquals(A.toString(), state.tenant());
    assertEquals(
        false,
        f.raw
            .sql(
                "SELECT rolsuper OR rolbypassrls OR rolcreaterole AS unsafe FROM pg_roles WHERE"
                    + " rolname=current_user")
            .map((r, m) -> r.get("unsafe", Boolean.class))
            .one()
            .block(WAIT));
    var invoked = new AtomicInteger();
    StepVerifier.create(
            f.executor.execute(
                db -> {
                  invoked.incrementAndGet();
                  return count(db);
                }))
        .expectError(DomainException.class)
        .verify(WAIT);
    assertEquals(0, invoked.get());
    assertEquals(0L, count(f.raw).block(WAIT));
    sqlState("42501", insert(f.raw, UUID.randomUUID(), A, PROJECT_A, 50));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void joinsAggregatesWindowsAndSubqueriesSeeOnlyOwnRows() {
    for (UUID tenant : List.of(A, B)) {
      var rows =
          run(
              tenant,
              db ->
                  db.sql(
                          "SELECT i.tenant_id,i.project_id,i.issue_number,count(*) OVER()"
                              + " total,row_number() OVER(ORDER BY i.issue_number) position FROM"
                              + " taska.issues i JOIN taska.projects p ON p.tenant_id=i.tenant_id"
                              + " AND p.id=i.project_id WHERE EXISTS (SELECT 1 FROM taska.issues"
                              + " child WHERE child.id=i.id) ORDER BY i.issue_number")
                      .fetch()
                      .all()
                      .collectList());
      assertEquals(3, rows.size());
      for (int i = 0; i < rows.size(); i++) {
        assertEquals(tenant, rows.get(i).get("tenant_id"));
        assertEquals(tenant.equals(A) ? PROJECT_A : PROJECT_B, rows.get(i).get("project_id"));
        assertEquals(3L, rows.get(i).get("total"));
        assertEquals((long) i + 1, rows.get(i).get("position"));
      }
      assertEquals(
          6L,
          TenantIsolationContractIntegrationTest.<Long>run(
              tenant,
              db ->
                  db.sql("SELECT sum(issue_number) n FROM taska.issues")
                      .map((r, m) -> r.get("n", java.math.BigDecimal.class).longValueExact())
                      .one()));
      assertEquals(
          0L,
          TenantIsolationContractIntegrationTest.<Long>run(
              tenant,
              db ->
                  db.sql("SELECT count(*) n FROM taska.issues WHERE tenant_id=:foreign")
                      .bind("foreign", tenant.equals(A) ? B : A)
                      .map((r, m) -> r.get("n", Long.class))
                      .one()));
    }
  }

  @Test
  void updateDeleteReturningAndWritableCteCannotTouchForeignRows() {
    for (String sql :
        List.of(
            "UPDATE taska.issues SET issue_number=issue_number+100 WHERE tenant_id=:foreign"
                + " RETURNING tenant_id",
            "DELETE FROM taska.issues WHERE tenant_id=:foreign RETURNING tenant_id",
            "WITH removed AS (DELETE FROM taska.issues WHERE tenant_id=:foreign RETURNING *) SELECT"
                + " tenant_id FROM removed")) {
      assertTrue(
          run(A, db -> db.sql(sql).bind("foreign", B).fetch().all().collectList()).isEmpty());
      assertEquals(
          3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
      assertEquals(
          6L,
          TenantIsolationContractIntegrationTest.<Long>run(
              B,
              db ->
                  db.sql("SELECT sum(issue_number) n FROM taska.issues")
                      .map((r, m) -> r.get("n", java.math.BigDecimal.class).longValueExact())
                      .one()));
    }
    var updated =
        run(
            A,
            db ->
                db.sql(
                        "UPDATE taska.issues SET issue_number=issue_number+10 RETURNING"
                            + " tenant_id,issue_number")
                    .fetch()
                    .all()
                    .collectList());
    assertEquals(3, updated.size());
    assertTrue(updated.stream().allMatch(row -> A.equals(row.get("tenant_id"))));
    var deleted =
        run(
            A,
            db ->
                db.sql("DELETE FROM taska.issues WHERE issue_number=11 RETURNING tenant_id")
                    .fetch()
                    .all()
                    .collectList());
    assertEquals(1, deleted.size());
    assertEquals(A, deleted.getFirst().get("tenant_id"));
    assertEquals(
        2L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void withCheckAndCompositeFkRejectForeignWritesWithoutPartialChanges() {
    sqlState(
        "42501", as(A, f.executor.execute(db -> insert(db, UUID.randomUUID(), B, PROJECT_B, 50))));
    sqlState(
        "42501",
        as(
            A,
            f.executor.execute(
                db ->
                    db.sql(
                            "UPDATE taska.issues SET tenant_id=:foreign,project_id=:project WHERE"
                                + " issue_number=1")
                        .bind("foreign", B)
                        .bind("project", PROJECT_B)
                        .fetch()
                        .rowsUpdated())));
    sqlState(
        "23503", as(A, f.executor.execute(db -> insert(db, UUID.randomUUID(), A, PROJECT_B, 50))));
    assertEquals(
        1L,
        TenantIsolationContractIntegrationTest.<Long>run(
            A, db -> insert(db, UUID.randomUUID(), A, PROJECT_A, 50)));
    assertEquals(
        4L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  static Mono<List<UUID>> batch(DatabaseClient db, boolean foreignSecond) {
    return db.inConnectionMany(
            connection -> {
              var statement =
                  connection.createStatement(
                      "INSERT INTO taska.issues VALUES ($1,$2,$3,$4) RETURNING tenant_id");
              statement.bind(0, UUID.randomUUID()).bind(1, A).bind(2, PROJECT_A).bind(3, 50L).add();
              statement
                  .bind(0, UUID.randomUUID())
                  .bind(1, foreignSecond ? B : A)
                  .bind(2, foreignSecond ? PROJECT_B : PROJECT_A)
                  .bind(3, 51L);
              return Flux.from(statement.execute())
                  .concatMap(result -> result.map((row, m) -> row.get("tenant_id", UUID.class)));
            })
        .collectList();
  }

  @Test
  void boundBatchReturningIsAtomicAndTenantScoped() {
    sqlState("42501", as(A, f.executor.execute(db -> batch(db, true))));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
    assertEquals(List.of(A, A), run(A, db -> batch(db, false)));
    assertEquals(
        5L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void insertSelectAndReturningCannotCopyForeignRows() {
    var copied =
        run(
            A,
            db ->
                db.sql(
                        "INSERT INTO taska.issues SELECT md5(id::text ||"
                            + " 'copy')::uuid,tenant_id,project_id,issue_number+100 FROM"
                            + " taska.issues RETURNING tenant_id")
                    .fetch()
                    .all()
                    .collectList());
    assertEquals(3, copied.size());
    assertTrue(copied.stream().allMatch(row -> A.equals(row.get("tenant_id"))));
    assertEquals(
        6L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void nestedQueriesShareConnectionAndRollbackTogether() {
    run(
        A,
        db ->
            snapshot(db)
                .flatMap(
                    before ->
                        insert(db, UUID.randomUUID(), A, PROJECT_A, 50)
                            .then(
                                f.executor.execute(
                                    inner ->
                                        snapshot(inner)
                                            .flatMap(
                                                after -> {
                                                  assertEquals(before, after);
                                                  return count(inner)
                                                      .doOnNext(n -> assertEquals(4L, n));
                                                })))));
    assertEquals(
        4L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    StepVerifier.create(
            as(
                A,
                f.executor.execute(
                    db ->
                        insert(db, UUID.randomUUID(), A, PROJECT_A, 51)
                            .then(as(B, f.executor.execute(TenantIsolationFixture::count)))
                            .onErrorReturn(-1L))))
        .expectError(DomainException.class)
        .verify(WAIT);
    assertEquals(
        4L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void retryOutsideTransactionRollsBackAndReacquiresCleanContext() {
    var attempts = new AtomicInteger();
    var transactions = new ArrayList<Long>();
    UUID id = UUID.randomUUID();
    var work =
        f.executor.execute(
            db ->
                snapshot(db)
                    .flatMap(
                        state -> {
                          assertEquals(A.toString(), state.tenant());
                          transactions.add(state.tx());
                          return insert(db, id, A, PROJECT_A, 50)
                              .then(
                                  attempts.incrementAndGet() == 1
                                      ? db.sql("SELECT 1/0").fetch().all().then(Mono.just(-1L))
                                      : count(db));
                        }));
    assertEquals(
        4L,
        as(
                A,
                work.retryWhen(
                    reactor.util.retry.Retry.max(1)
                        .filter(
                            error -> {
                              Throwable cause = error;
                              while (!(cause instanceof R2dbcException) && cause.getCause() != null)
                                cause = cause.getCause();
                              return cause instanceof R2dbcException sql
                                  && "22012".equals(sql.getSqlState());
                            })))
            .block(WAIT));
    assertEquals(2, attempts.get());
    assertEquals(2, new HashSet<>(transactions).size());
    f.assertCleanSingleConnection();
    var b = run(B, TenantIsolationFixture::snapshot);
    assertEquals(B.toString(), b.tenant());
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
  }

  @Test
  void cancellationDuringSqlRollsBackBatchAndDoesNotLeakToNextTenant() throws Exception {
    var subscription =
        as(
                A,
                f.executor.execute(
                    db ->
                        batch(db, false)
                            .then(
                                db.sql("SELECT pg_sleep(30) /* tas309-cancel */")
                                    .fetch()
                                    .all()
                                    .then())))
            .subscribe();
    try {
      assertNotNull(
          f.operator
              .sql(
                  "SELECT pid FROM pg_stat_activity WHERE usename='taska_project_app' AND"
                      + " wait_event='PgSleep' AND query LIKE '%tas309-cancel%'")
              .map((r, m) -> r.get("pid", Integer.class))
              .one()
              .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(20)))
              .block(Duration.ofSeconds(5)));
    } finally {
      subscription.dispose();
    }
    f.assertCleanSingleConnection();
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(B, TenantIsolationFixture::count));
    assertEquals(
        3L, TenantIsolationContractIntegrationTest.<Long>run(A, TenantIsolationFixture::count));
    // Cancellation must release server locks as well as the pooled slot.
    f.operator
        .inConnection(
            c ->
                Flux.from(c.createStatement("SET lock_timeout='3s'").execute())
                    .flatMap(result -> result.getRowsUpdated())
                    .thenMany(Flux.from(c.createStatement("TRUNCATE taska.issues").execute()))
                    .flatMap(result -> result.getRowsUpdated())
                    .then())
        .block(Duration.ofSeconds(5));
  }

  @Test
  void overlappingCompaniesUseDistinctConnectionsWithoutMixingRows() throws Exception {
    try (var parallel = new TenantIsolationFixture(2)) {
      parallel.reset(3);
      var entered = new CountDownLatch(2);
      var pids = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
      var values =
          Flux.range(0, 24)
              .flatMap(
                  i -> {
                    UUID tenant = i % 2 == 0 ? A : B;
                    return as(
                            tenant,
                            parallel.executor.execute(
                                db ->
                                    snapshot(db)
                                        .flatMap(
                                            state -> {
                                              assertEquals(tenant.toString(), state.tenant());
                                              assertEquals("taska_project_app", state.login());
                                              pids.add(state.pid());
                                              if (i < 2) entered.countDown();
                                              return Mono.fromCallable(
                                                      () -> {
                                                        assertTrue(
                                                            entered.await(5, TimeUnit.SECONDS));
                                                        return true;
                                                      })
                                                  .subscribeOn(Schedulers.boundedElastic())
                                                  .then(
                                                      db.sql("SELECT tenant_id FROM taska.issues")
                                                          .map(
                                                              (r, m) ->
                                                                  r.get("tenant_id", UUID.class))
                                                          .all()
                                                          .collectList())
                                                  .doOnNext(
                                                      rows ->
                                                          assertEquals(
                                                              List.of(tenant, tenant, tenant),
                                                              rows));
                                            })))
                        .subscribeOn(Schedulers.parallel());
                  },
                  2)
              .collectList()
              .block(Duration.ofSeconds(20));
      assertEquals(24, values.size());
      assertEquals(2, pids.size());
      // Hold both borrowed connections simultaneously, so both physical sessions are inspected.
      Mono.usingWhen(
              Mono.from(parallel.pool.create()),
              first ->
                  Mono.usingWhen(
                      Mono.from(parallel.pool.create()),
                      second -> {
                        // Use raw statements here: DatabaseClient would close our deliberately held
                        // handles.
                        return Flux.fromIterable(List.of(first, second))
                            .concatMap(
                                c ->
                                    Flux.from(
                                            c.createStatement(
                                                    "SELECT"
                                                        + " NULLIF(current_setting('app.tenant_id',true),'')"
                                                        + " IS NULL AND"
                                                        + " NULLIF(current_setting('app.actor_id',true),'')"
                                                        + " IS NULL AS clean")
                                                .execute())
                                        .concatMap(
                                            result ->
                                                result.map(
                                                    (r, m) -> r.get("clean", Boolean.class))))
                            .doOnNext(ok -> assertTrue(ok))
                            .then();
                      },
                      c -> Mono.from(c.close())),
              c -> Mono.from(c.close()))
          .block(WAIT);
    }
  }

  @Test
  void selectivePlansUseTenantLeadingIndexesWithNaturalPlannerSettings() {
    f.reset(4000);
    for (UUID tenant : List.of(A, B)) {
      UUID project = tenant.equals(A) ? PROJECT_A : PROJECT_B;
      String plan =
          run(
              tenant,
              db ->
                  db.sql(
                          "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT issue_number FROM"
                              + " taska.issues WHERE tenant_id=:tenant AND project_id=:project AND"
                              + " issue_number BETWEEN 10 AND 20")
                      .bind("tenant", tenant)
                      .bind("project", project)
                      .map((r, m) -> r.get(0, io.r2dbc.postgresql.codec.Json.class).asString())
                      .one());
      assertNotNull(plan);
      assertTrue(plan.contains("Index"), plan);
      assertTrue(
          java.util.regex.Pattern.compile("\"Index Cond\"\\s*:\\s*\"[^\"]*tenant_id")
              .matcher(plan)
              .find(),
          plan);
      assertFalse(plan.contains("Seq Scan"), plan);
      assertTrue(plan.contains("\"Actual Rows\": 11"), plan);
      // Store explain artifacts outside Git for diagnosis; no timing threshold or disabled seqscan.
      try {
        java.nio.file.Files.createDirectories(
            java.nio.file.Path.of("target/tenant-isolation-plans"));
        java.nio.file.Files.writeString(
            java.nio.file.Path.of("target/tenant-isolation-plans/" + tenant + ".json"), plan);
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }
}
