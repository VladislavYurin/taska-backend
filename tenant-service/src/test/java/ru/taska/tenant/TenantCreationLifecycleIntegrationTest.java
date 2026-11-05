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
import java.util.Set;
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
import ru.taska.api.b2b.v1.*;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.GetTenantOperationRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.tenant.TenantCreationDecisions.*;

@Testcontainers
@Timeout(90)
class TenantCreationLifecycleIntegrationTest {
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
  TenantCreationLifecycle lifecycle;
  Map<UUID, InitialPayment> decisions;
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
            + " taska.quota_effects,taska.quota_reservations,taska.quota_counters,taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events");
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
    decisions = new ConcurrentHashMap<>();
    lifecycle =
        new TenantCreationLifecycle(tx, (t, id) -> Mono.justOrEmpty(decisions.get(id)), clock);
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

  UUID id(CreateTenantResponse c) {
    return UUID.fromString(c.getTenant().getId());
  }

  Mono<Tenant> expire(CreateTenantResponse c) {
    return service(c, "tenant-service", "expireTenantCreation", lifecycle.expire(id(c)));
  }

  <T> Mono<T> service(CreateTenantResponse c, String issuer, String action, Mono<T> work) {
    return RegistryTestContext.withClaims(
        TrustedTenantContext.newBuilder()
            .setTenantId(c.getTenant().getId())
            .setIssuerService(issuer)
            .setAction(action)
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .addScopes("write")
            .build(),
        work);
  }

  CancelTenantCreationRpcRequest cancelRequest(
      CreateTenantResponse c, String key, String version, boolean confirmed) {
    return CancelTenantCreationRpcRequest.newBuilder()
        .setTenantId(c.getTenant().getId())
        .setPayload(ConfirmedAction.newBuilder().setConfirmed(confirmed))
        .setMutation(
            MutationOptions.newBuilder().setIdempotencyKey(key).setExpectedVersion(version))
        .build();
  }

  Mono<Tenant> cancel(
      CancelTenantCreationRpcRequest r,
      UUID actor,
      UUID scope,
      CredentialKind kind,
      boolean session) {
    return RegistryTestContext.as(
        actor, scope, "cancelTenantCreation", kind, session, lifecycle.cancel(r));
  }

  Mono<Tenant> cancel(CreateTenantResponse c) {
    return cancel(
        cancelRequest(c, UUID.randomUUID().toString(), current(c).getVersion(), true),
        OWNER,
        null,
        CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
        true);
  }

  Tenant current(CreateTenantResponse c) {
    return operator
        .sql("SELECT * FROM taska.tenants WHERE id=:id")
        .bind("id", id(c))
        .fetch()
        .one()
        .map(TenantRegistryService::tenant)
        .block(WAIT);
  }

  Instant deadline(CreateTenantResponse c) {
    return Instant.parse(c.getTenant().getCreationDeadline());
  }

  InitialPayment evidence(CreateTenantResponse c, Instant paidAt, boolean full, UUID coverage) {
    return new InitialPayment(
        UUID.randomUUID(),
        id(c),
        UUID.fromString(c.getOperation().getId()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        PAID,
        full,
        coverage,
        Precision.EXACT,
        paidAt,
        null,
        null,
        deadline(c));
  }

  Mono<TenantInitialPaymentResult> apply(CreateTenantResponse c, InitialPayment e) {
    decisions.put(e.decisionId(), e);
    return service(
        c,
        "billing-service",
        "applyTenantInitialPaymentDecision",
        lifecycle.apply(
            ApplyTenantInitialPaymentDecisionRequest.newBuilder()
                .setTenantId(c.getTenant().getId())
                .setDecisionId(e.decisionId().toString())
                .build()));
  }

  void rejected(Mono<?> work, String code) {
    assertEquals(
        code, assertThrows(DomainException.class, () -> work.block(WAIT)).getDetails().get("code"));
  }

  @Test
  void ownerCancelIsConfirmedVersionedAuthorizedAndExactlyOnce() {
    var c = create("cancel", PAID);
    String key = UUID.randomUUID().toString();
    var r = cancelRequest(c, key, c.getTenant().getVersion(), true);
    var initial = current(c);
    sql(
        "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
            + id(c)
            + "','"
            + OTHER
            + "','TENANT_ADMIN','ACTIVE')");
    rejected(
        cancel(r, OTHER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL, true), "OWNER_REQUIRED");
    sql(
        "UPDATE taska.tenant_memberships SET status='REMOVED' WHERE tenant_id='"
            + id(c)
            + "' AND user_id='"
            + OTHER
            + "'");

    rejected(
        cancel(r, OTHER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL, true),
        "TENANT_NOT_FOUND");
    rejected(
        cancel(r, OWNER, id(c), CredentialKind.CREDENTIAL_KIND_USER_PAT, true), "SESSION_REQUIRED");
    rejected(
        cancel(r, OWNER, UUID.randomUUID(), CredentialKind.CREDENTIAL_KIND_WEB_TENANT, true),
        "SESSION_REQUIRED");
    rejected(
        cancel(r, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL, false),
        "SESSION_REQUIRED");
    rejected(
        cancel(
            r.toBuilder().setPayload(ConfirmedAction.getDefaultInstance()).build(),
            OWNER,
            null,
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true),
        "CONFIRMATION_REQUIRED");
    rejected(
        cancel(
            r.toBuilder().setMutation(r.getMutation().toBuilder().clearExpectedVersion()).build(),
            OWNER,
            null,
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true),
        "VERSION_CONFLICT");
    rejected(
        cancel(
            r.toBuilder().setMutation(r.getMutation().toBuilder().setExpectedVersion("99")).build(),
            OWNER,
            null,
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true),
        "VERSION_CONFLICT");
    assertEquals(initial, current(c));
    assertEquals(0, count("tenant_creation_receipts"));
    assertEquals(1, count("outbox_events"));
    var closed =
        cancel(r, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL, true).block(WAIT);
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, closed.getLifecycle());
    assertEquals(
        Tenant.CreationClosureReason.CREATION_CLOSURE_REASON_OWNER_CANCELLED,
        closed.getCreationClosureReason());
    assertEquals(
        closed,
        cancel(r, OWNER, id(c), CredentialKind.CREDENTIAL_KIND_WEB_TENANT, true).block(WAIT));
    rejected(
        cancel(
            r.toBuilder()
                .setMutation(r.getMutation().toBuilder().setExpectedVersion(closed.getVersion()))
                .build(),
            OWNER,
            null,
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true),
        "IDEMPOTENCY_CONFLICT");
    rejected(
        cancel(
            cancelRequest(c, UUID.randomUUID().toString(), c.getTenant().getVersion(), true),
            OWNER,
            null,
            CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
            true),
        "VERSION_CONFLICT");
    assertEquals(closed, cancel(c).block(WAIT));
    assertEquals(2, count("outbox_events"));
    assertEquals(2, count("tenant_memberships"));
    assertEquals(0, admissions.get());
    assertEquals(Operation.StateValue.STATE_FAILED, resume(c).block(WAIT).getState());
    sql("UPDATE taska.tenant_memberships SET status='SUSPENDED' WHERE tenant_id='" + id(c) + "'");
    rejected(
        cancel(r, OWNER, null, CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL, true),
        "TENANT_NOT_FOUND");
  }

  @Test
  void deadlineIsOriginalAndConcurrentExpiryHasOneEffect() {
    var c = create("deadline", PAID);
    var free = create("free", FREE);
    now.set(deadline(c).minusNanos(1000));
    assertEquals(c.getTenant(), expire(c).block(WAIT));
    now.set(deadline(c));
    var results = Flux.range(0, 8).flatMap(i -> expire(c), 8).collectList().block(WAIT);
    assertTrue(
        results.stream()
            .allMatch(t -> t.getLifecycle() == Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED));
    assertEquals(
        Tenant.CreationClosureReason.CREATION_CLOSURE_REASON_AUTO_EXPIRED,
        current(c).getCreationClosureReason());
    assertEquals(deadline(c).toString(), current(c).getCreationDeadline());
    assertEquals(current(c), create("deadline", PAID).getTenant());
    assertEquals(free.getTenant(), expire(free).block(WAIT));
    rejected(cancel(free), "INVALID_STATE");
    assertEquals(3, count("outbox_events"));
    assertEquals(1, count("tenant_creation_receipts"));
    assertEquals(1, admissions.get());
    assertEquals(Operation.StateValue.STATE_SUCCEEDED, resume(c).block(WAIT).getState());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED, current(c).getLifecycle());
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, cancel(c).block(WAIT).getLifecycle());
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, expire(c).block(WAIT).getLifecycle());
  }

  @Test
  void onlyFullTimelyPrimaryDecisionWithCommittedCoverageRestoresAutoClosed() {
    var c = create("payment", PAID);
    resume(c).block(WAIT);
    now.set(deadline(c).plusSeconds(60));
    expire(c).block(WAIT);
    var partial = evidence(c, deadline(c).minusSeconds(1), false, UUID.randomUUID());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
        apply(c, partial).block(WAIT).getOutcome());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
        apply(c, evidence(c, deadline(c).plusNanos(1000), true, UUID.randomUUID()))
            .block(WAIT)
            .getOutcome());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NEEDS_EVIDENCE,
        apply(c, evidence(c, deadline(c), true, null)).block(WAIT).getOutcome());
    var e = evidence(c, deadline(c), true, UUID.randomUUID());
    var day =
        new InitialPayment(
            UUID.randomUUID(),
            e.tenantId(),
            e.creationOperationId(),
            e.financialFactId(),
            e.invoiceId(),
            e.planVersionId(),
            true,
            e.coverageId(),
            Precision.DAY,
            null,
            deadline(c).minusSeconds(3600),
            deadline(c).plusSeconds(3600),
            deadline(c));
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NEEDS_EVIDENCE,
        apply(c, day).block(WAIT).getOutcome());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED, current(c).getLifecycle());
    var paid = apply(c, e).block(WAIT);
    assertEquals(TenantInitialPaymentResult.Outcome.OUTCOME_APPLIED, paid.getOutcome());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, paid.getTenant().getLifecycle());
    assertFalse(paid.getTenant().hasCreationClosureReason());
    assertEquals(paid, apply(c, e).block(WAIT));
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_ALREADY_APPLIED,
        apply(
                c,
                new InitialPayment(
                    UUID.randomUUID(),
                    e.tenantId(),
                    e.creationOperationId(),
                    e.financialFactId(),
                    e.invoiceId(),
                    e.planVersionId(),
                    true,
                    e.coverageId(),
                    e.precision(),
                    e.occurredAt(),
                    null,
                    null,
                    e.invoiceDeadline()))
            .block(WAIT)
            .getOutcome());
    rejected(cancel(c), "INVALID_STATE");
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, expire(c).block(WAIT).getLifecycle());
    assertThrows(
        Exception.class,
        () -> sql("UPDATE taska.tenants SET first_payment_fact_id=NULL WHERE id='" + id(c) + "'"));
    assertThrows(
        Exception.class, () -> sql("UPDATE taska.tenant_creation_receipts SET result='x'"));
    assertEquals(4, count("outbox_events"));
  }

  @Test
  void invoiceDeadlineAndDecisionIdentityAreChecked() {
    var c = create("invoice", PAID);
    resume(c).block(WAIT);
    var e = evidence(c, now.get().plusSeconds(10), true, UUID.randomUUID());
    var late =
        new InitialPayment(
            e.decisionId(),
            e.tenantId(),
            e.creationOperationId(),
            e.financialFactId(),
            e.invoiceId(),
            e.planVersionId(),
            true,
            e.coverageId(),
            e.precision(),
            e.occurredAt(),
            null,
            null,
            now.get());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
        apply(c, late).block(WAIT).getOutcome());
    rejected(apply(c, e), "IDEMPOTENCY_CONFLICT");
    var wrong =
        new InitialPayment(
            UUID.randomUUID(),
            e.tenantId(),
            UUID.randomUUID(),
            e.financialFactId(),
            e.invoiceId(),
            e.planVersionId(),
            true,
            e.coverageId(),
            e.precision(),
            now.get(),
            null,
            null,
            e.invoiceDeadline());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
        apply(c, wrong).block(WAIT).getOutcome());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT, current(c).getLifecycle());
    assertEquals(2, count("outbox_events"));
  }

  @Test
  void paymentWaitsForReadinessAndHardCancellationFencesStaleWorker() {
    var c = create("wait", PAID);
    var e = evidence(c, now.get(), true, UUID.randomUUID());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_WAITING_PROVISIONING,
        apply(c, e).block(WAIT).getOutcome());
    now.set(deadline(c));
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT, expire(c).block(WAIT).getLifecycle());
    resume(c).block(WAIT);
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, current(c).getLifecycle());
    var cancelled = create("stale", PAID);
    var started = Sinks.<Void>one();
    var release = Sinks.<Void>one();
    saga =
        new TenantProvisioning(
            tx,
            new TenantProvisioning.Dependencies() {
              public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                started.tryEmitEmpty();
                return release.asMono();
              }

              public Mono<Void> billing(UUID t, UUID o, UUID p, boolean f) {
                throw new AssertionError("stale worker advanced");
              }
            },
            clock);
    var old = resume(cancelled).toFuture();
    started.asMono().block(WAIT);
    cancel(cancelled).block(WAIT);
    release.tryEmitEmpty();
    assertEquals(Operation.StateValue.STATE_FAILED, old.join().getState());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
        apply(cancelled, evidence(cancelled, now.get(), true, UUID.randomUUID()))
            .block(WAIT)
            .getOutcome());
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, current(cancelled).getLifecycle());
  }

  @Test
  void expiryAndCancelAndPaymentAreSerializedWithoutResurrection() {
    for (int i = 0; i < 6; i++) {
      var c = create("race" + i, PAID);
      resume(c).block(WAIT);
      now.set(deadline(c));
      var e = evidence(c, deadline(c), true, UUID.randomUUID());
      var version = current(c).getVersion();
      var cancel =
          cancel(
                  cancelRequest(c, UUID.randomUUID().toString(), version, true),
                  OWNER,
                  null,
                  CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                  true)
              .onErrorResume(
                  DomainException.class,
                  error -> {
                    assertTrue(
                        Set.of("VERSION_CONFLICT", "INVALID_STATE")
                            .contains(error.getDetails().get("code")));
                    return Mono.empty();
                  });
      Mono.when(expire(c), cancel, apply(c, e)).block(WAIT);
      var state = current(c).getLifecycle();
      assertTrue(
          state == Tenant.LifecycleValue.LIFECYCLE_ACTIVE
              || state == Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED);
      assertEquals(state, expire(c).block(WAIT).getLifecycle());
    }
  }

  @Test
  void closureRollbackKeepsStateReceiptAndOutboxAtomicAndRlsHidesOtherCompany() {
    var c = create("atomic", PAID);
    sql(
        "CREATE FUNCTION taska.reject_creation_receipt() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'fixture'; END $$");
    sql(
        "CREATE TRIGGER reject_creation_receipt BEFORE INSERT ON taska.tenant_creation_receipts FOR"
            + " EACH ROW EXECUTE FUNCTION taska.reject_creation_receipt()");
    try {
      assertThrows(Exception.class, () -> cancel(c).block(WAIT));
      assertEquals(c.getTenant(), current(c));
      assertEquals(1, count("outbox_events"));
    } finally {
      sql("DROP TRIGGER reject_creation_receipt ON taska.tenant_creation_receipts");
      sql("DROP FUNCTION taska.reject_creation_receipt()");
    }
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.tenant_creation_receipts")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    rejected(
        service(c, "workflow-service", "expireTenantCreation", lifecycle.expire(id(c))),
        "SCOPE_DENIED");
    assertEquals(
        Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED, cancel(c).block(WAIT).getLifecycle());
    assertEquals(0, admissions.get());
    assertEquals(1, count("tenant_memberships"));
  }

  @Test
  void immutableIncompleteDecisionReplaysAndNewDecisionCanSupplyCoverage() {
    var c = create("immutable-decision", PAID);
    resume(c).block(WAIT);
    var e = evidence(c, now.get(), true, null);
    var first = apply(c, e).block(WAIT);
    assertEquals(TenantInitialPaymentResult.Outcome.OUTCOME_NEEDS_EVIDENCE, first.getOutcome());
    assertEquals(first, apply(c, e).block(WAIT));
    var corrected =
        new InitialPayment(
            UUID.randomUUID(),
            e.tenantId(),
            e.creationOperationId(),
            e.financialFactId(),
            e.invoiceId(),
            e.planVersionId(),
            true,
            UUID.randomUUID(),
            e.precision(),
            e.occurredAt(),
            null,
            null,
            e.invoiceDeadline());
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_APPLIED,
        apply(c, corrected).block(WAIT).getOutcome());
    assertEquals(3, count("outbox_events"));
  }

  @Test
  void firstPaymentPreservesLiveWorkerLeaseAndNoTrustLeavesExpiryIdle() {
    var c = create("live-payment", PAID);
    var started = Sinks.<Void>one();
    var release = Sinks.<Void>one();
    var healthy = dependencies();
    saga =
        new TenantProvisioning(
            tx,
            new TenantProvisioning.Dependencies() {
              public Mono<Void> workflow(UUID t, UUID o, UUID template) {
                started.tryEmitEmpty();
                return release.asMono().then(healthy.workflow(t, o, template));
              }

              public Mono<Void> billing(UUID t, UUID o, UUID p, boolean f) {
                return healthy.billing(t, o, p, f);
              }
            },
            clock);
    var live = resume(c).toFuture();
    started.asMono().block(WAIT);
    var before =
        operator
            .sql("SELECT fence,lease_until FROM taska.tenant_operations WHERE tenant_id=:id")
            .bind("id", id(c))
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(
        TenantInitialPaymentResult.Outcome.OUTCOME_WAITING_PROVISIONING,
        apply(c, evidence(c, now.get(), true, UUID.randomUUID())).block(WAIT).getOutcome());
    var after =
        operator
            .sql("SELECT fence,lease_until FROM taska.tenant_operations WHERE tenant_id=:id")
            .bind("id", id(c))
            .fetch()
            .one()
            .block(WAIT);
    assertEquals(before, after);
    assertEquals(Operation.StateValue.STATE_RUNNING, resume(c).block(WAIT).getState());
    release.tryEmitEmpty();
    assertEquals(Operation.StateValue.STATE_SUCCEEDED, live.join().getState());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, current(c).getLifecycle());
    var unpaid = create("idle-trust", PAID);
    now.set(deadline(unpaid));
    new TenantCreationExpiryWorker(raw, () -> null, lifecycle, clock).poll().block(WAIT);
    assertEquals(unpaid.getTenant(), current(unpaid));
    assertEquals(1, workflow.size());
    assertEquals(1, billing.size());
  }

  @Test
  void deletionAndRenewalAndOtherCompaniesArePreserved() {
    var untouched = create("untouched", PAID);
    for (String state : List.of("DELETING", "DELETED", "ACTIVE")) {
      var c = create(state, PAID);
      sql("UPDATE taska.tenants SET lifecycle='" + state + "' WHERE id='" + id(c) + "'");
      var snapshot = current(c);
      now.set(deadline(c).plusSeconds(60));
      assertEquals(snapshot, expire(c).block(WAIT));
      assertEquals(
          TenantInitialPaymentResult.Outcome.OUTCOME_NOT_ELIGIBLE,
          apply(c, evidence(c, deadline(c), true, UUID.randomUUID())).block(WAIT).getOutcome());
      assertEquals(snapshot, current(c));
    }
    assertEquals(untouched.getTenant(), current(untouched));
    assertEquals(4, count("tenant_memberships"));
    assertEquals(0, admissions.get());
  }
}
