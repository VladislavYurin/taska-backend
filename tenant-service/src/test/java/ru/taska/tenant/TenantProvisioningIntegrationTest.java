package ru.taska.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.api.b2b.v1.CreateTenantResponse;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.GetTenantOperationRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class TenantProvisioningIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final UUID OWNER = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      FREE = UUID.randomUUID(),
      PAID = UUID.randomUUID();
  static ConnectionPool pool;
  static DatabaseClient operator, raw;
  static TenantTransactionExecutor tx;
  AtomicReference<Instant> now;
  Clock clock;
  TenantRegistryService registry;
  TenantProvisioning saga;
  AtomicInteger admissions;
  Map<String, UUID> workflow, billing;
  AtomicReference<String> failAfter;

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
    var bootstrap =
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
    assertEquals(0, bootstrap.getExitCode(), bootstrap.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
    sql("ALTER ROLE taska_tenant_app PASSWORD '" + APP + "'");
    sql("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY + "'");
    var migration =
        new TenantConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
    migration.afterPropertiesSet();
    migration.afterPropertiesSet();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP))
                .initialSize(1)
                .maxSize(2)
                .build());
    raw = DatabaseClient.create(pool);
    tx = new TenantTransactionExecutor(pool);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  static void sql(String sql) {
    operator.sql(sql).then().block(WAIT);
  }

  @BeforeEach
  void reset() {
    sql(
        "TRUNCATE"
            + " taska.quota_effects,taska.quota_reservations,taska.quota_counters,taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.free_admission_subjects,taska.free_plan_admissions,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events");
    now = new AtomicReference<>(Instant.parse("2026-11-05T12:00:00Z"));
    clock =
        new Clock() {
          public Instant instant() {
            return now.get();
          }

          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }
        };
    admissions = new AtomicInteger();
    workflow = new ConcurrentHashMap<>();
    billing = new ConcurrentHashMap<>();
    failAfter = new AtomicReference<>();
    registry =
        new TenantRegistryService(
            tx,
            id -> Mono.just(new RegistryDependencies.PublishedPlan(id, id.equals(FREE))),
            (db, actor, tenant) -> Mono.fromRunnable(admissions::incrementAndGet),
            clock,
            Duration.ofDays(30));
    saga = new TenantProvisioning(tx, dependencies(), clock);
  }

  TenantProvisioning.Dependencies dependencies() {
    return new TenantProvisioning.Dependencies() {
      Mono<Void> apply(
          Map<String, UUID> store, String step, UUID tenant, UUID operation, UUID input) {
        return Mono.<Void>defer(
                () -> {
                  assertEquals(
                      0L,
                      raw.sql("SELECT count(*) n FROM taska.tenants")
                          .map((r, m) -> r.get("n", Long.class))
                          .one()
                          .block(WAIT));
                  var existing = store.putIfAbsent(tenant + ":" + operation + ":" + step, input);
                  if (existing != null && !existing.equals(input))
                    return Mono.error(new AssertionError("Changed step identity"));
                  if (failAfter.compareAndSet(step, null))
                    return Mono.error(
                        new IllegalStateException("private upstream body must never be stored"));
                  return Mono.empty();
                })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
      }

      public Mono<Void> workflow(UUID tenant, UUID operation, UUID template) {
        return apply(workflow, "WORKFLOW", tenant, operation, template);
      }

      public Mono<Void> billing(UUID tenant, UUID operation, UUID plan, boolean free) {
        assertEquals(plan.equals(FREE), free);
        return apply(billing, "BILLING", tenant, operation, plan);
      }
    };
  }

  CreateTenantResponse create(String key, UUID plan) {
    return RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            registry.create(TenantRegistryIntegrationTest.request(key, plan, "Company")))
        .block(WAIT);
  }

  <T> Mono<T> worker(CreateTenantResponse created, Mono<T> work) {
    var claims =
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setTenantId(created.getTenant().getId())
            .setIssuerService("tenant-service")
            .setAction("provisionTenant")
            .addScopes("write")
            .build();
    return RegistryTestContext.withClaims(claims, work);
  }

  Mono<Operation> resume(CreateTenantResponse created) {
    return worker(
        created,
        saga.resume(
            UUID.fromString(created.getTenant().getId()),
            UUID.fromString(created.getOperation().getId())));
  }

  Operation read(CreateTenantResponse created, UUID actor, UUID scope, CredentialKind kind) {
    return RegistryTestContext.as(
            actor,
            scope,
            "getTenantOperation",
            kind,
            true,
            registry.getOperation(
                GetTenantOperationRpcRequest.newBuilder()
                    .setTenantId(created.getTenant().getId())
                    .setOperationId(created.getOperation().getId())
                    .build()))
        .block(WAIT);
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void ambiguousGrpcRejectionsKeepRetryingAndCanRecover() {
    var healthy = dependencies();
    for (var status :
        List.of(
            io.grpc.Status.INVALID_ARGUMENT,
            io.grpc.Status.FAILED_PRECONDITION,
            io.grpc.Status.ALREADY_EXISTS)) {
      var created = create("rpc-status-" + status.getCode(), PAID);
      saga =
          new TenantProvisioning(
              tx,
              new TenantProvisioning.Dependencies() {
                public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                  return Mono.error(status.withDescription("private").asRuntimeException());
                }

                public Mono<Void> billing(UUID t, UUID o, UUID plan, boolean free) {
                  throw new AssertionError();
                }
              },
              clock);
      assertEquals(Operation.StateValue.STATE_RUNNING, resume(created).block(WAIT).getState());
      assertEquals(
          ru.taska.api.b2b.v1.ErrorCode.ERROR_CODE_DEPENDENCY_UNAVAILABLE,
          read(created, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL).getFailureCode());
      saga = new TenantProvisioning(tx, healthy, clock);
      assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(created).block(WAIT).getState());
    }
  }

  @Test
  void nameBoundaryMatchesBlueprintAndRejectedCreationLeavesNoState() {
    var invalid = TenantRegistryIntegrationTest.request("invalid-name", FREE, "x".repeat(121));
    var error =
        assertThrows(
            DomainException.class,
            () ->
                RegistryTestContext.as(
                        OWNER,
                        null,
                        "createTenant",
                        CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                        true,
                        registry.create(invalid))
                    .block(WAIT));
    assertEquals("name", error.getDetails().get("field"));
    assertEquals(0, count("tenants"));
    assertEquals(0, count("tenant_operations"));
    var valid =
        invalid.toBuilder()
            .setPayload(invalid.getPayload().toBuilder().setName("😀".repeat(120)))
            .build();
    RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            registry.create(valid))
        .block(WAIT);
    assertEquals(1, count("tenants"));
    assertEquals(1, admissions.get());
  }

  @Test
  void upgradePreservesPendingOperationIdentityAndRestoresForcedRls() throws Exception {
    try (var previous = new PostgreSQLContainer<>("postgres:16")) {
      previous.start();
      for (String ext : List.of("sql", "psql"))
        previous.copyFileToContainer(
            MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
            "/tmp/bootstrap." + ext);
      var boot =
          previous.execInContainer(
              "psql",
              "-X",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              previous.getUsername(),
              "-d",
              previous.getDatabaseName(),
              "-v",
              "service=tenant",
              "-v",
              "schema=taska",
              "-f",
              "/tmp/bootstrap.psql");
      assertEquals(0, boot.getExitCode(), boot.getStderr());
      UUID tenant = UUID.randomUUID(), operation = UUID.randomUUID();
      try (var connection =
              java.sql.DriverManager.getConnection(
                  previous.getJdbcUrl(), previous.getUsername(), previous.getPassword());
          var sql = connection.createStatement()) {
        sql.execute("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
        sql.execute("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY + "'");
        var migration =
            new TenantConfiguration()
                .registryLiquibase(previous.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
        try (var deployer = migration.getDataSource().getConnection()) {
          var database = new liquibase.database.core.PostgresDatabase();
          database.setConnection(new liquibase.database.jvm.JdbcConnection(deployer));
          database.setLiquibaseSchemaName("tenant_liquibase");
          try (var old =
              new liquibase.Liquibase(
                  "db/changelog/db.changelog-master.yaml",
                  new liquibase.resource.ClassLoaderResourceAccessor(),
                  database)) {
            old.update(5, new liquibase.Contexts(), new liquibase.LabelExpression());
          }
        }
        connection.setAutoCommit(false);
        String tenantSeed =
            "INSERT INTO"
                + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,creation_deadline_at,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                + " VALUES ('"
                + tenant
                + "','Upgrade','"
                + OWNER
                + "','AWAITING_PAYMENT','PAID',now()+interval '30 days','"
                + PAID
                + "','"
                + OWNER
                + "',decode(repeat('00',32),'hex'),'"
                + operation
                + "')";
        sql.execute(tenantSeed);
        String memberSeed =
            "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
                + tenant
                + "','"
                + OWNER
                + "','TENANT_ADMIN','ACTIVE')";
        sql.execute(memberSeed);
        connection.commit();
        connection.setAutoCommit(true);
        UUID duplicate = UUID.randomUUID();
        connection.setAutoCommit(false);
        sql.execute(
            "INSERT INTO taska.tenants SELECT"
                + " (jsonb_populate_record(NULL::taska.tenants,to_jsonb(t)||jsonb_build_object('id','"
                + duplicate
                + "'))).* FROM taska.tenants t WHERE id='"
                + tenant
                + "'");
        sql.execute(
            "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
                + duplicate
                + "','"
                + OWNER
                + "','TENANT_ADMIN','ACTIVE')");
        connection.commit();
        connection.setAutoCommit(true);
        assertThrows(liquibase.exception.LiquibaseException.class, migration::afterPropertiesSet);
        try (var rows =
            sql.executeQuery(
                "SELECT relforcerowsecurity,to_regclass('taska.tenant_operations') IS NULL"
                    + " rolled_back FROM pg_class WHERE oid='taska.tenants'::regclass")) {
          assertTrue(rows.next());
          assertTrue(rows.getBoolean(1));
          assertTrue(rows.getBoolean(2));
        }
        connection.setAutoCommit(false);
        // Reset only this private pre-upgrade fault fixture; RESTRICT protects the cyclic FK.
        sql.execute("TRUNCATE taska.free_admission_subjects,taska.free_plan_admissions,taska.tenants,taska.tenant_memberships");
        sql.execute(tenantSeed);
        sql.execute(memberSeed);
        connection.commit();
        connection.setAutoCommit(true);
        migration.afterPropertiesSet();
        migration.afterPropertiesSet();
        try (var rows =
            sql.executeQuery(
                "SELECT o.id,o.tenant_id,o.state,t.relforcerowsecurity FROM taska.tenant_operations"
                    + " o CROSS JOIN pg_class t WHERE t.oid='taska.tenants'::regclass")) {
          assertTrue(rows.next());
          assertEquals(operation, rows.getObject(1, UUID.class));
          assertEquals(tenant, rows.getObject(2, UUID.class));
          assertEquals("ACCEPTED", rows.getString(3));
          assertTrue(rows.getBoolean(4));
          assertFalse(rows.next());
        }
        try (var rows =
            sql.executeQuery(
                "SELECT operation_id,creation_deadline_at FROM"
                    + " taska.tenant_provisioning_dispatch")) {
          assertTrue(rows.next());
          assertEquals(operation, rows.getObject(1, UUID.class));
          assertTrue(rows.getObject(2) != null);
          assertFalse(rows.next());
        }
      }
    }
  }

  @Test
  void upgradeBackfillsDistinctLegacyClosureReasonsAndRestoresRls() throws Exception {
    try (var oldPg = new PostgreSQLContainer<>("postgres:16")) {
      oldPg.start();
      for (String ext : List.of("sql", "psql"))
        oldPg.copyFileToContainer(
            MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
            "/tmp/bootstrap." + ext);
      var boot =
          oldPg.execInContainer(
              "psql",
              "-X",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              oldPg.getUsername(),
              "-d",
              oldPg.getDatabaseName(),
              "-v",
              "service=tenant",
              "-v",
              "schema=taska",
              "-f",
              "/tmp/bootstrap.psql");
      assertEquals(0, boot.getExitCode(), boot.getStderr());
      try (var connection =
              java.sql.DriverManager.getConnection(
                  oldPg.getJdbcUrl(), oldPg.getUsername(), oldPg.getPassword());
          var sql = connection.createStatement()) {
        sql.execute("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
        sql.execute("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY + "'");
        var migration =
            new TenantConfiguration()
                .registryLiquibase(oldPg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY);
        try (var deployer = migration.getDataSource().getConnection()) {
          var database = new liquibase.database.core.PostgresDatabase();
          database.setConnection(new liquibase.database.jvm.JdbcConnection(deployer));
          database.setLiquibaseSchemaName("tenant_liquibase");
          try (var old =
              new liquibase.Liquibase(
                  "db/changelog/db.changelog-master.yaml",
                  new liquibase.resource.ClassLoaderResourceAccessor(),
                  database)) {
            old.update(6, new liquibase.Contexts(), new liquibase.LabelExpression());
          }
        }
        connection.setAutoCommit(false);
        for (String state : List.of("AUTO_CLOSED", "OWNER_CANCELLED")) {
          UUID tenant = UUID.randomUUID();
          sql.execute(
              "INSERT INTO"
                  + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,creation_deadline_at,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                  + " VALUES ('"
                  + tenant
                  + "','Legacy','"
                  + OWNER
                  + "','"
                  + state
                  + "','PAID',now()-interval '1 day','"
                  + PAID
                  + "','"
                  + OWNER
                  + "',decode(repeat('00',32),'hex'),'"
                  + UUID.randomUUID()
                  + "')");
          sql.execute(
              "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
                  + tenant
                  + "','"
                  + OWNER
                  + "','TENANT_ADMIN','ACTIVE')");
        }
        connection.commit();
        connection.setAutoCommit(true);
        migration.afterPropertiesSet();
        migration.afterPropertiesSet();
        try (var rows =
            sql.executeQuery(
                "SELECT lifecycle,creation_closure_reason,creation_closed_at=updated_at same_time"
                    + " FROM taska.tenants ORDER BY lifecycle")) {
          assertTrue(rows.next());
          assertEquals("AUTO_CLOSED", rows.getString(1));
          assertEquals("AUTO_EXPIRED", rows.getString(2));
          assertTrue(rows.getBoolean(3));
          assertTrue(rows.next());
          assertEquals("OWNER_CANCELLED", rows.getString(1));
          assertEquals("OWNER_CANCELLED", rows.getString(2));
          assertTrue(rows.getBoolean(3));
          assertFalse(rows.next());
        }
        try (var rows =
            sql.executeQuery(
                "SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE oid IN"
                    + " ('taska.tenants'::regclass,'taska.tenant_creation_receipts'::regclass)")) {
          assertTrue(rows.next());
          assertTrue(rows.getBoolean(1));
        }
      }
    }
  }

  @Test
  void freeAndPaidCompleteOnceAndReplayReturnsActualState() {
    var free = create("free", FREE);
    var paid = create("paid", PAID);
    var deadline = paid.getTenant().getCreationDeadline();
    assertEquals(Operation.StateValue.STATE_PENDING, free.getOperation().getState());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_PROVISIONING, free.getTenant().getLifecycle());
    now.set(now.get().plusSeconds(1));
    for (var tenant : List.of(free, paid)) {
      assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(tenant).block(WAIT).getState());
      assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(tenant).block(WAIT).getState());
      assertEquals(
          Operation.StateValue.STATE_SUCCEEDED,
          read(tenant, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL).getState());
    }
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_ACTIVE, create("free", FREE).getTenant().getLifecycle());
    var replay = create("paid", PAID);
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT, replay.getTenant().getLifecycle());
    assertEquals(deadline, replay.getTenant().getCreationDeadline());
    assertTrue(replay.getTenant().getRestrictionsCount() > 0);
    assertEquals(Operation.StateValue.STATE_SUCCEEDED, replay.getOperation().getState());
    assertFalse(replay.getOperation().getCreatedAt().equals(replay.getOperation().getUpdatedAt()));
    assertEquals(2, count("tenants"));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(2, count("tenant_operations"));
    assertEquals(4, count("outbox_events"));
    assertEquals(2, workflow.size());
    assertEquals(2, billing.size());
    assertEquals(1, admissions.get());
  }

  @Test
  void restartAfterEachRemoteSuccessUsesSameKeysAndNeverActivatesPartiallyReadyFree() {
    for (String step : List.of("WORKFLOW", "BILLING")) {
      var created = create("retry-" + step, FREE);
      failAfter.set(step);
      assertEquals(Operation.StateValue.STATE_RUNNING, resume(created).block(WAIT).getState());
      assertEquals(
          Tenant.LifecycleValue.LIFECYCLE_PROVISIONING,
          create("retry-" + step, FREE).getTenant().getLifecycle());
      String state =
          operator
              .sql(
                  "SELECT result::text||coalesce(failure_code,'') data FROM taska.tenant_operations"
                      + " WHERE id=:id")
              .bind("id", UUID.fromString(created.getOperation().getId()))
              .map((r, m) -> r.get("data", String.class))
              .one()
              .block(WAIT);
      assertFalse(state.contains("private"));
      saga = new TenantProvisioning(new TenantTransactionExecutor(pool), dependencies(), clock);
      assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(created).block(WAIT).getState());
    }
    assertEquals(2, workflow.size());
    assertEquals(2, billing.size());
  }

  @Test
  void parallelWorkersAndExpiredLeaseFenceStaleAcknowledgement() {
    var created = create("race", FREE);
    var waiting = Sinks.<Void>one();
    var started = Sinks.<Void>one();
    var normal = dependencies();
    saga =
        new TenantProvisioning(
            tx,
            new TenantProvisioning.Dependencies() {
              public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                started.tryEmitEmpty();
                return waiting.asMono();
              }

              public Mono<Void> billing(UUID t, UUID o, UUID plan, boolean free) {
                return Mono.error(new AssertionError("Stale worker advanced"));
              }
            },
            clock);
    var old = resume(created).toFuture();
    started.asMono().block(WAIT);
    saga = new TenantProvisioning(tx, normal, clock);
    assertEquals(Operation.StateValue.STATE_RUNNING, resume(created).block(WAIT).getState());
    now.set(now.get().plusSeconds(31));
    var results = Flux.range(0, 8).flatMap(i -> resume(created), 8).collectList().block(WAIT);
    assertTrue(
        results.stream().anyMatch(o -> o.getState() == Operation.StateValue.STATE_SUCCEEDED));
    waiting.tryEmitEmpty();
    assertEquals(Operation.StateValue.STATE_SUCCEEDED, old.join().getState());
    assertEquals(1, workflow.size());
    assertEquals(1, billing.size());
    assertEquals(2, count("outbox_events"));
  }

  @Test
  void operationReadChecksOriginalActorSessionAndTenantAndRls() {
    var created = create("read", PAID);
    UUID tenant = UUID.fromString(created.getTenant().getId());
    assertEquals(
        created.getOperation(),
        read(created, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL));
    assertEquals(
        created.getOperation(),
        read(created, OWNER, tenant, CredentialKind.CREDENTIAL_KIND_WEB_TENANT));
    assertThrows(
        DomainException.class,
        () -> read(created, OTHER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL));
    assertThrows(
        DomainException.class,
        () -> read(created, OWNER, UUID.randomUUID(), CredentialKind.CREDENTIAL_KIND_WEB_TENANT));
    assertThrows(
        DomainException.class,
        () -> read(created, OWNER, tenant, CredentialKind.CREDENTIAL_KIND_USER_PAT));
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.tenant_operations")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertThrows(Exception.class, () -> sql("UPDATE taska.tenant_operations SET payload='{}'"));
  }

  @Test
  void finalCommitRollsBackLifecycleOperationAndEventTogether() {
    var created = create("atomic", FREE);
    sql(
        "CREATE FUNCTION taska.reject_provision_finish() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN IF NEW.state='SUCCEEDED' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW;"
            + " END $$");
    sql(
        "CREATE TRIGGER reject_provision_finish BEFORE UPDATE ON taska.tenant_operations FOR EACH"
            + " ROW EXECUTE FUNCTION taska.reject_provision_finish()");
    try {
      assertEquals(Operation.StateValue.STATE_RUNNING, resume(created).block(WAIT).getState());
      assertEquals(
          Tenant.LifecycleValue.LIFECYCLE_PROVISIONING,
          create("atomic", FREE).getTenant().getLifecycle());
      assertEquals(1, count("outbox_events"));
    } finally {
      sql("DROP TRIGGER reject_provision_finish ON taska.tenant_operations");
      sql("DROP FUNCTION taska.reject_provision_finish()");
    }
    assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(created).block(WAIT).getState());
    assertEquals(2, count("outbox_events"));
    assertEquals(1, workflow.size());
    assertEquals(1, billing.size());
  }

  @Test
  void permanentFailureDoesNotTouchOtherCompanyOrReleaseFreeAdmission() {
    var other = create("other", PAID);
    var created = create("invalid", FREE);
    saga =
        new TenantProvisioning(
            tx,
            new TenantProvisioning.Dependencies() {
              public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                return Mono.error(
                    new DomainException(
                        ru.taska.exception.DomainStatus.FAILED_PRECONDITION, "secret"));
              }

              public Mono<Void> billing(UUID t, UUID o, UUID plan, boolean free) {
                throw new AssertionError();
              }
            },
            clock);
    assertEquals(Operation.StateValue.STATE_FAILED, resume(created).block(WAIT).getState());
    assertEquals(Operation.StateValue.STATE_FAILED, resume(created).block(WAIT).getState());
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_PROVISIONING,
        create("invalid", FREE).getTenant().getLifecycle());
    assertEquals(other, create("other", PAID));
    assertEquals(1, admissions.get());
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, billing.size());
  }
}
