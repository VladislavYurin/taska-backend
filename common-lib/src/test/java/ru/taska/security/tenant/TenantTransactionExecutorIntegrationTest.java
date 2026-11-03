package ru.taska.security.tenant;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;

@Testcontainers
class TenantTransactionExecutorIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  static final String A = UUID.randomUUID().toString(),
      B = UUID.randomUUID().toString(),
      ACTOR = UUID.randomUUID().toString();
  static ConnectionPool pool;
  static DatabaseClient raw;
  static TenantTransactionExecutor executor;
  static DatabaseClient admin;

  record Snapshot(int pid, long tx, String tenant, String actor) {}

  static ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, postgres.getHost())
            .option(PORT, postgres.getMappedPort(5432))
            .option(DATABASE, postgres.getDatabaseName())
            .option(USER, user)
            .option(PASSWORD, password)
            .build());
  }

  @BeforeAll
  static void setup() {
    admin = DatabaseClient.create(factory(postgres.getUsername(), postgres.getPassword()));
    for (String sql :
        List.of(
            "CREATE ROLE tenant_app LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS",
            "CREATE TABLE tenant_probe(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,note text NOT"
                + " NULL,UNIQUE(tenant_id,note) DEFERRABLE INITIALLY DEFERRED)",
            "ALTER TABLE tenant_probe ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE tenant_probe FORCE ROW LEVEL SECURITY",
            "CREATE POLICY tenant_guard ON tenant_probe TO tenant_app USING"
                + " (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK"
                + " (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)",
            "GRANT SELECT,INSERT,UPDATE,DELETE ON tenant_probe TO tenant_app"))
      admin.sql(sql).fetch().rowsUpdated().block();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("tenant_app", "test-only"))
                .initialSize(1)
                .maxSize(1)
                .maxAcquireTime(Duration.ofSeconds(5))
                .build());
    raw = DatabaseClient.create(pool);
    executor = new TenantTransactionExecutor(pool);
  }

  @BeforeEach
  void seed() {
    admin.sql("TRUNCATE tenant_probe").fetch().rowsUpdated().block();
    for (String tenant : List.of(A, B))
      admin
          .sql("INSERT INTO tenant_probe VALUES (:id,:tenant,'seed')")
          .bind("id", UUID.randomUUID())
          .bind("tenant", UUID.fromString(tenant))
          .fetch()
          .rowsUpdated()
          .block();
  }

  @AfterAll
  static void cleanup() {
    if (pool != null) pool.disposeLater().block();
  }

  static VerifiedTenantContext identity(String tenant) {
    return identity(tenant, ACTOR);
  }

  static VerifiedTenantContext identity(String tenant, String actor) {
    return new VerifiedTenantContext(
        actor, tenant, UUID.randomUUID().toString(), "test.sql", Set.of("write"), new byte[0]);
  }

  static <T> Mono<T> as(String tenant, Mono<T> work) {
    return work.contextWrite(identity(tenant)::install);
  }

  static Mono<Snapshot> snapshot(DatabaseClient client) {
    return client
        .sql(
            "SELECT pg_backend_pid() AS pid,txid_current() AS"
                + " tx,NULLIF(current_setting('app.tenant_id',true),'') AS"
                + " tenant,NULLIF(current_setting('app.actor_id',true),'') AS actor")
        .map(
            (row, metadata) ->
                new Snapshot(
                    row.get("pid", Integer.class),
                    row.get("tx", Long.class),
                    row.get("tenant", String.class),
                    row.get("actor", String.class)))
        .one();
  }

  static Mono<Long> count(DatabaseClient client) {
    return client
        .sql("SELECT count(*) AS n FROM tenant_probe")
        .map((row, m) -> row.get("n", Long.class))
        .one();
  }

  static Mono<Long> insert(DatabaseClient client, String tenant, String note) {
    return client
        .sql("INSERT INTO tenant_probe VALUES (:id,:tenant,:note)")
        .bind("id", UUID.randomUUID())
        .bind("tenant", UUID.fromString(tenant))
        .bind("note", note)
        .fetch()
        .rowsUpdated();
  }

  static void assertClean() {
    var s = snapshot(raw).block(Duration.ofSeconds(10));
    assertNull(s.tenant());
    assertNull(s.actor());
    assertEquals(0L, count(raw).block());
  }

  @Test
  void commitUsesSameConnectionAndSettingIsLocalAcrossPoolReuse() {
    var a =
        as(
                A,
                executor.execute(
                    db ->
                        snapshot(db)
                            .flatMap(
                                before ->
                                    insert(db, A, "commit")
                                        .then(snapshot(db))
                                        .doOnNext(
                                            after -> {
                                              assertEquals(before.pid(), after.pid());
                                              assertEquals(before.tx(), after.tx());
                                            }))))
            .block();
    assertEquals(A, a.tenant());
    assertEquals(ACTOR, a.actor());
    assertClean();
    var b =
        as(
                B,
                executor.execute(
                    db ->
                        snapshot(db)
                            .flatMap(
                                s -> count(db).doOnNext(n -> assertEquals(1L, n)).thenReturn(s))))
            .block();
    assertEquals(a.pid(), b.pid());
    assertEquals(B, b.tenant());
    assertNotEquals(a.tx(), b.tx());
    assertClean();
    assertEquals(
        2L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
  }

  @Test
  void noContextOrGlobalContextNeverCallsUserSql() {
    var invoked = new AtomicInteger();
    StepVerifier.create(
            executor.execute(
                db -> {
                  invoked.incrementAndGet();
                  return count(db);
                }))
        .expectErrorSatisfies(
            e -> assertEquals(DomainStatus.UNAUTHENTICATED, ((DomainException) e).getStatus()))
        .verify();
    StepVerifier.create(
            executor
                .execute(
                    db -> {
                      invoked.incrementAndGet();
                      return count(db);
                    })
                .contextWrite(identity(null)::install))
        .expectError(DomainException.class)
        .verify();
    assertEquals(0, invoked.get());
    assertClean();
  }

  @Test
  void nestedSameAuthoritySharesTransactionAndConnection() {
    var value =
        as(
                A,
                executor.execute(
                    db ->
                        snapshot(db)
                            .flatMap(
                                before ->
                                    executor
                                        .execute(inner -> snapshot(inner))
                                        .doOnNext(
                                            after -> {
                                              assertEquals(before, after);
                                              assertEquals(A, after.tenant());
                                            }))))
            .block();
    assertNotNull(value);
    assertClean();
  }

  @Test
  void tenantOrActorChangeIsRejectedBeforeSqlAndRollsBackCaughtFailure() {
    for (var changed : List.of(identity(B), identity(A, UUID.randomUUID().toString()))) {
      var invoked = new AtomicInteger();
      var operation =
          as(
              A,
              executor.execute(
                  db ->
                      insert(db, A, "must-rollback")
                          .then(
                              executor
                                  .execute(
                                      inner -> {
                                        invoked.incrementAndGet();
                                        return count(inner);
                                      })
                                  .contextWrite(changed::install)
                                  .onErrorResume(DomainException.class, e -> Mono.just(-1L)))));
      StepVerifier.create(operation)
          .expectErrorSatisfies(
              e -> assertEquals(DomainStatus.ABORTED, ((DomainException) e).getStatus()))
          .verify();
      assertEquals(0, invoked.get());
      assertEquals(
          1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
      assertClean();
    }
  }

  @Test
  void asynchronousAndSynchronousErrorsRollback() {
    StepVerifier.create(
            as(
                A,
                executor.execute(
                    db ->
                        insert(db, A, "rollback")
                            .then(Mono.error(new IllegalStateException("failure"))))))
        .expectError(IllegalStateException.class)
        .verify();
    StepVerifier.create(
            as(
                A,
                executor.execute(
                    db -> {
                      throw new IllegalArgumentException("sync");
                    })))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
    assertClean();
  }

  @Test
  void caughtNestedSqlErrorCannotCommitEarlierWrites() {
    var operation =
        as(
            A,
            executor.execute(
                db ->
                    insert(db, A, "must-rollback")
                        .then(
                            executor
                                .execute(inner -> insert(inner, B, "foreign"))
                                .onErrorReturn(-1L))));
    StepVerifier.create(operation)
        .expectErrorSatisfies(
            e -> assertEquals(DomainStatus.ABORTED, ((DomainException) e).getStatus()))
        .verify();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
    assertClean();
  }

  @Test
  void cancellationRollsBackAndReturnsTheConnection() throws Exception {
    var inserted = new CountDownLatch(1);
    var subscription =
        as(
                A,
                executor.execute(
                    db ->
                        insert(db, A, "cancel")
                            .doOnSuccess(n -> inserted.countDown())
                            .then(Mono.never())))
            .subscribe();
    assertTrue(inserted.await(5, TimeUnit.SECONDS));
    subscription.dispose();
    // maxSize=1: this acquisition waits for the cancelled transaction's rollback/release.
    assertClean();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
  }

  @Test
  void rlsRejectsForeignWritesAndCannotReadForeignRows() {
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
    StepVerifier.create(as(A, executor.execute(db -> insert(db, B, "foreign"))))
        .expectError()
        .verify();
    assertClean();
    assertEquals(
        1L, as(B, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
  }

  @Test
  void ambientTransactionAndDifferentFactoryAreRejected() {
    var invoked = new AtomicInteger();
    var tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    StepVerifier.create(
            as(
                A,
                tx.transactional(
                    executor.execute(
                        db -> {
                          invoked.incrementAndGet();
                          return count(db);
                        }))))
        .expectError(DomainException.class)
        .verify();
    var another =
        new TenantTransactionExecutor(
            new ConnectionFactory() {
              public org.reactivestreams.Publisher<? extends io.r2dbc.spi.Connection> create() {
                return pool.create();
              }

              public io.r2dbc.spi.ConnectionFactoryMetadata getMetadata() {
                return pool.getMetadata();
              }
            });
    StepVerifier.create(
            as(
                A,
                executor.execute(
                    db ->
                        another.execute(
                            inner -> {
                              invoked.incrementAndGet();
                              return count(inner);
                            }))))
        .expectError(DomainException.class)
        .verify();
    assertEquals(0, invoked.get());
    assertClean();
  }

  @Test
  void escapedClientCannotRunAfterTransactionCompletion() {
    var escaped = new AtomicReference<DatabaseClient>();
    as(
            A,
            executor.execute(
                db -> {
                  escaped.set(db);
                  return Mono.just("done");
                }))
        .block();
    StepVerifier.create(as(A, count(escaped.get())))
        .expectErrorSatisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertInstanceOf(DomainException.class, cause);
            })
        .verify();
    assertClean();
  }

  @Test
  void parallelCompaniesDoNotMixAcrossSchedulers() {
    var values =
        Flux.range(0, 24)
            .flatMap(
                i -> {
                  String tenant = i % 2 == 0 ? A : B;
                  return as(
                          tenant,
                          executor.execute(
                              db ->
                                  snapshot(db)
                                      .publishOn(Schedulers.parallel())
                                      .flatMap(
                                          first ->
                                              count(db)
                                                  .map(
                                                      n -> {
                                                        assertEquals(tenant, first.tenant());
                                                        assertEquals(1L, n);
                                                        return first.tenant();
                                                      }))))
                      .subscribeOn(Schedulers.parallel());
                },
                4)
            .collectList()
            .block(Duration.ofSeconds(20));
    assertEquals(12, values.stream().filter(A::equals).count());
    assertEquals(12, values.stream().filter(B::equals).count());
    assertClean();
  }

  @Test
  void serviceWithoutHumanActorClearsPreviousActor() {
    as(A, executor.execute(TenantTransactionExecutorIntegrationTest::snapshot)).block();
    var service =
        executor
            .execute(TenantTransactionExecutorIntegrationTest::snapshot)
            .contextWrite(identity(B, null)::install)
            .block();
    assertEquals(B, service.tenant());
    assertNull(service.actor());
    assertClean();
  }

  @Test
  void commitFailureEmitsNoResultAndReleasesTheConnection() {
    StepVerifier.create(
            as(
                A,
                executor.execute(
                    db -> insert(db, A, "seed").thenReturn("must-not-escape-before-commit"))))
        .expectError()
        .verify();
    assertClean();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
  }

  @Test
  void otherExecutorForSameFactoryAndTransactionAwareProxyJoinSafely() {
    var another =
        new TenantTransactionExecutor(
            new org.springframework.r2dbc.connection.TransactionAwareConnectionFactoryProxy(pool));
    as(
            A,
            executor.execute(
                db ->
                    snapshot(db)
                        .flatMap(
                            first ->
                                another
                                    .execute(inner -> snapshot(inner))
                                    .doOnNext(second -> assertEquals(first, second)))))
        .block();
    assertClean();
  }

  @Test
  void cancellationDuringRunningSqlReturnsAReusableCleanConnection() {
    var subscription =
        as(
                A,
                executor.execute(
                    db ->
                        insert(db, A, "cancel-running")
                            .then(
                                db.sql("SELECT pg_sleep(30) /* tas307-single-cancel */")
                                    .fetch()
                                    .all()
                                    .then())))
            .subscribe();
    Integer sleepingPid;
    try {
      sleepingPid =
          admin
              .sql(
                  "SELECT pid FROM pg_stat_activity WHERE usename='tenant_app' AND"
                      + " wait_event='PgSleep' AND query LIKE '%tas307-single-cancel%'")
              .map((row, m) -> row.get("pid", Integer.class))
              .one()
              .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(20)))
              .block(Duration.ofSeconds(5));
    } finally {
      subscription.dispose();
    }
    assertNotEquals(sleepingPid, snapshot(raw).block(Duration.ofSeconds(10)).pid());
    assertClean();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
    // Cancellation must also release row/table locks, not merely open a second connection.
    admin.sql("TRUNCATE tenant_probe").fetch().rowsUpdated().block(Duration.ofSeconds(5));
  }

  @Test
  void caughtNestedCancellationCannotCommitTheParentTransaction() {
    var operation =
        as(
            A,
            executor.execute(
                db ->
                    insert(db, A, "parent-cancel")
                        .then(
                            executor
                                .execute(
                                    inner ->
                                        insert(inner, A, "nested-cancel")
                                            .then(Mono.<String>never()))
                                .timeout(Duration.ofMillis(50))
                                .onErrorReturn("recovered"))));
    StepVerifier.create(operation)
        .expectErrorSatisfies(
            e -> assertEquals(DomainStatus.ABORTED, ((DomainException) e).getStatus()))
        .verify();
    assertClean();
    assertEquals(
        1L, as(A, executor.execute(TenantTransactionExecutorIntegrationTest::count)).block());
  }

  @Test
  void cancellationInMultiConnectionPoolDoesNotCancelAnotherTenant() throws Exception {
    var multiPool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("tenant_app", "test-only"))
                .initialSize(2)
                .maxSize(2)
                .maxAcquireTime(Duration.ofSeconds(5))
                .build());
    var local = new TenantTransactionExecutor(multiPool);
    reactor.core.Disposable cancelled = null;
    java.util.concurrent.CompletableFuture<Snapshot> other = null;
    try {
      multiPool.warmup().block();
      cancelled =
          as(
                  A,
                  local.execute(
                      db ->
                          insert(db, A, "multi-cancel")
                              .then(
                                  db.sql("SELECT pg_sleep(30) /* tas307-multi-cancel */")
                                      .fetch()
                                      .all()
                                      .then())))
              .subscribe();
      other =
          as(
                  B,
                  local.execute(
                      db ->
                          db.sql(
                                  "SELECT pg_sleep(2),current_setting('app.tenant_id') AS"
                                      + " tenant,pg_backend_pid() AS pid /* tas307-multi-other */")
                              .map(
                                  (row, m) ->
                                      new Snapshot(
                                          row.get("pid", Integer.class),
                                          0L,
                                          row.get("tenant", String.class),
                                          null))
                              .one()))
              .toFuture();
      admin
          .sql(
              "SELECT count(*) AS n FROM pg_stat_activity WHERE usename='tenant_app' AND"
                  + " wait_event='PgSleep' AND (query LIKE '%tas307-multi-cancel%' OR query LIKE"
                  + " '%tas307-multi-other%')")
          .map((row, m) -> row.get("n", Long.class))
          .one()
          .filter(n -> n >= 2)
          .repeatWhenEmpty(repeats -> repeats.delayElements(Duration.ofMillis(20)))
          .block(Duration.ofSeconds(5));
      cancelled.dispose();
      assertEquals(B, other.get(5, TimeUnit.SECONDS).tenant());
      assertEquals(
          1L,
          as(A, local.execute(TenantTransactionExecutorIntegrationTest::count))
              .block(Duration.ofSeconds(5)));
      var plain = DatabaseClient.create(multiPool);
      for (int n = 0; n < 3; n++) {
        var clean = snapshot(plain).block();
        assertNull(clean.tenant());
        assertNull(clean.actor());
      }
      admin.sql("TRUNCATE tenant_probe").fetch().rowsUpdated().block(Duration.ofSeconds(5));
    } finally {
      if (cancelled != null) cancelled.dispose();
      if (other != null) other.cancel(true);
      multiPool.disposeLater().block();
    }
  }
}
