package ru.taska.billing;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.CreatePlanVersionRequest;
import ru.taska.api.b2b.v1.CreatePlanVersionRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ListBillingPlansRpcRequest;
import ru.taska.api.b2b.v1.Money;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.PageRequest;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.PlanVersionPage;
import ru.taska.api.b2b.v1.PublishPlanVersionRequest;
import ru.taska.api.b2b.v1.PublishPlanVersionRpcRequest;
import ru.taska.api.b2b.v1.QuotaLimit;
import ru.taska.api.b2b.v1.ResolvePublishedPlanRpcRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RegistryTestContext;

@Testcontainers
@Timeout(90)
class BillingCatalogIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static final UUID ACTOR = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  static ConnectionPool pool;
  static DatabaseClient db, operator;
  static TransactionalOperator tx;
  BillingCatalog catalog;
  CatalogCursor cursor;

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
            "service=billing",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, result.getExitCode(), result.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA billing_liquibase AUTHORIZATION taska_billing_migrator");
    sql("REVOKE ALL ON SCHEMA billing_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_billing_app PASSWORD '" + APP_PASSWORD + "'");
    sql("ALTER ROLE taska_billing_deployer PASSWORD '" + DEPLOY_PASSWORD + "'");
    migrate();
    migrate();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_billing_app", APP_PASSWORD))
                .maxSize(5)
                .initialSize(1)
                .build());
    db = DatabaseClient.create(pool);
    tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    new BillingConfiguration().safeRuntimeRole(pool).afterPropertiesSet();
  }

  static void migrate() throws Exception {
    new BillingConfiguration()
        .registryLiquibase(pg.getJdbcUrl(), "taska_billing_deployer", DEPLOY_PASSWORD)
        .afterPropertiesSet();
  }

  static void sql(String s) {
    operator.sql(s).fetch().rowsUpdated().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void reset() {
    // Test operator resets its own fixtures; runtime cannot delete any catalog history.
    sql(
        "TRUNCATE"
            + " taska.catalog_mutation_receipts,taska.global_outbox_events,taska.outbox_events,taska.idempotency_records,taska.processed_events");
    sql("ALTER TABLE taska.catalog_plan_versions DISABLE TRIGGER immutable_plan");
    sql("DELETE FROM taska.catalog_plan_versions WHERE version>1");
    sql("ALTER TABLE taska.catalog_plan_versions ENABLE TRIGGER immutable_plan");
    cursor =
        new CatalogCursor(
            Base64.getEncoder().encodeToString(new byte[32]), Clock.fixed(NOW, ZoneOffset.UTC));
    catalog = new BillingCatalog(db, tx, cursor);
  }

  TrustedTenantContext claims(String action) {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setActorGeneration("1")
        .setSessionId(UUID.randomUUID().toString())
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
        .setAction(action)
        .setIssuerService("api-gateway")
        .setAudienceService("billing-service")
        .addScopes("read")
        .addScopes("write")
        .setAdministrativeScope(AdminScope.newBuilder().setKind(AdminScope.KindValue.KIND_PLATFORM))
        .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_MODIFY)
        .build();
  }

  <T> Mono<T> as(String action, Mono<T> work) {
    return RegistryTestContext.withClaims(claims(action), work);
  }

  PlanVersionPage list(int limit, String token) {
    var p = PageRequest.newBuilder().setLimit(limit);
    if (token != null) p.setCursor(token);
    return as(
            "listBillingPlans",
            catalog.list(ListBillingPlansRpcRequest.newBuilder().setPage(p).build()))
        .block(WAIT);
  }

  CreatePlanVersionRequest payload() {
    return CreatePlanVersionRequest.newBuilder()
        .setCode("PAID")
        .setKind(PlanVersion.KindValue.KIND_PAID)
        .setName("Paid next")
        .setPrice(
            Money.newBuilder()
                .setAmountMinor("2500")
                .setCurrency(Money.CurrencyValue.CURRENCY_EUR)
                .setExponent(2))
        .setPeriod(CreatePlanVersionRequest.PeriodValue.PERIOD_CALENDAR_MONTH)
        .addLimits(limit(QuotaLimit.KindValue.KIND_SEAT, "50"))
        .addLimits(limit(QuotaLimit.KindValue.KIND_PROJECT, "5"))
        .addLimits(limit(QuotaLimit.KindValue.KIND_STORAGE_BYTES, "5000000000"))
        .addLimits(
            QuotaLimit.newBuilder()
                .setKind(QuotaLimit.KindValue.KIND_TASK_LIFETIME)
                .setUnlimited(true))
        .build();
  }

  static QuotaLimit limit(QuotaLimit.KindValue kind, String value) {
    return QuotaLimit.newBuilder().setKind(kind).setLimit(value).build();
  }

  CreatePlanVersionRpcRequest request(String key, CreatePlanVersionRequest p) {
    return CreatePlanVersionRpcRequest.newBuilder()
        .setPayload(p)
        .setMutation(MutationOptions.newBuilder().setIdempotencyKey(key))
        .build();
  }

  Mono<PlanVersion> create(String key, CreatePlanVersionRequest p) {
    return as("createPlanVersion", catalog.create(request(key, p)));
  }

  PublishPlanVersionRpcRequest publishRequest(PlanVersion p, String key) {
    return PublishPlanVersionRpcRequest.newBuilder()
        .setPlanVersionId(p.getId())
        .setPayload(PublishPlanVersionRequest.newBuilder().setConfirmed(true))
        .setMutation(
            MutationOptions.newBuilder().setIdempotencyKey(key).setExpectedVersion(p.getVersion()))
        .build();
  }

  PlanVersion publish(PlanVersion p, String key) {
    return as("publishPlanVersion", catalog.publish(publishRequest(p, key))).block(WAIT);
  }

  static void rejects(DomainStatus status, String code, Mono<?> m) {
    var e = assertThrows(DomainException.class, () -> m.block(WAIT));
    assertEquals(status, e.getStatus());
    assertEquals(code, e.getDetails().get("code"));
  }

  static long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  @Test
  void bootstrapIsRepeatableAndDoesNotBill() throws Exception {
    var before = list(0, null);
    migrate();
    assertEquals(before, list(100, null));
    assertEquals(2, before.getItemsCount());
    assertEquals(2, count("catalog_plan_versions"));
    assertEquals(0, count("global_outbox_events"));
    var free = before.getItems(0);
    var paid = before.getItems(1);
    assertEquals("0", free.getPrice().getAmountMinor());
    assertEquals("2000", paid.getPrice().getAmountMinor());
    assertEquals(PlanVersion.KindValue.KIND_FREE, free.getKind());
    assertEquals(PlanVersion.KindValue.KIND_PAID, paid.getKind());
    assertEquals(
        List.of("5", "1", "100000000", "50"),
        free.getLimitsList().stream().map(QuotaLimit::getLimit).toList());
    assertTrue(paid.getLimits(3).getUnlimited());
    assertEquals("5000000000", paid.getLimits(2).getLimit());
  }

  @Test
  void createIsDraftAndPublishPreservesHistory() {
    var initial = list(100, null);
    var draft = create("create", payload()).block(WAIT);
    assertEquals("2", draft.getVersion());
    assertEquals(PlanVersion.StateValue.STATE_DRAFT, draft.getState());
    assertEquals(initial, list(100, null));
    var pub = publish(draft, "publish");
    assertEquals(PlanVersion.StateValue.STATE_PUBLISHED, pub.getState());
    assertEquals(pub, list(100, null).getItems(1));
    assertEquals(3, count("catalog_plan_versions"));
    assertEquals(2, count("global_outbox_events"));
    assertEquals(
        2000L,
        operator
            .sql(
                "SELECT price_minor FROM taska.catalog_plan_versions WHERE plan_code='PAID' AND"
                    + " version=1")
            .map((r, m) -> r.get("price_minor", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void retriesReplayOriginalResponseAndConflictingIntentHasNoEffects() {
    var draft = create("same", payload()).block(WAIT);
    assertEquals(draft, create("same", payload()).block(WAIT));
    var pub = publish(draft, "publish");
    assertEquals(pub, publish(draft, "publish"));
    assertEquals(draft, create("same", payload()).block(WAIT));
    rejects(
        DomainStatus.ALREADY_EXISTS,
        "IDEMPOTENCY_CONFLICT",
        create("same", payload().toBuilder().setName("other").build()));
    assertEquals(3, count("catalog_plan_versions"));
    assertEquals(2, count("global_outbox_events"));
  }

  @Test
  void concurrentRetriesHaveOneVersionAndOneEvent() {
    var values =
        Flux.range(0, 8)
            .flatMap(i -> create("race", payload()).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(1, values.stream().map(PlanVersion::getId).distinct().count());
    assertEquals(3, count("catalog_plan_versions"));
    assertEquals(1, count("global_outbox_events"));
  }

  @Test
  void concurrentDistinctVersionsAreSerialized() {
    var values =
        Flux.range(0, 6)
            .flatMap(i -> create("version-" + i, payload()).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(6, values.stream().map(PlanVersion::getVersion).distinct().count());
    assertEquals(8, count("catalog_plan_versions"));
    assertEquals(6, count("global_outbox_events"));
  }

  @Test
  void latestMeansVersionNotPublicationTime() {
    var two = create("two", payload()).block(WAIT);
    var three = create("three", payload()).block(WAIT);
    publish(three, "pub3");
    publish(two, "pub2");
    assertEquals("3", list(100, null).getItems(1).getVersion());
    var service =
        claims("resolvePublishedPlan").toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setIssuerService("tenant-service")
            .addScopes("catalog:resolve")
            .build();
    assertEquals(
        three.getId(),
        RegistryTestContext.withClaims(
                service,
                catalog.resolve(
                    ResolvePublishedPlanRpcRequest.newBuilder()
                        .setPlanVersionId(three.getId())
                        .build()))
            .block(WAIT)
            .getId());
    rejects(
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        RegistryTestContext.withClaims(
            service,
            catalog.resolve(
                ResolvePublishedPlanRpcRequest.newBuilder()
                    .setPlanVersionId(two.getId())
                    .build())));
  }

  @Test
  void cursorDoesNotRequireTheAnchorToRemainAnActiveOffer() {
    var page = list(1, null);
    assertEquals("FREE", page.getItems(0).getCode());
    // Operator-only fixture: the runtime API cannot archive/delete product identity.
    sql("ALTER TABLE taska.catalog_plans DISABLE TRIGGER immutable_product");
    try {
      sql("UPDATE taska.catalog_plans SET archived_at=now() WHERE plan_code='FREE'");
      var next = list(1, page.getNextCursor());
      assertEquals(
          List.of("PAID"), next.getItemsList().stream().map(PlanVersion::getCode).toList());
      assertFalse(next.hasNextCursor());
      assertEquals(
          List.of("PAID"),
          list(100, null).getItemsList().stream().map(PlanVersion::getCode).toList());
    } finally {
      sql("UPDATE taska.catalog_plans SET archived_at=NULL WHERE plan_code='FREE'");
      sql("ALTER TABLE taska.catalog_plans ENABLE TRIGGER immutable_product");
    }
  }

  @Test
  void cursorPaginatesOnlyPublishedLatestAndRejectsTampering() {
    var page = list(1, null);
    assertTrue(page.hasNextCursor());
    assertEquals("FREE", page.getItems(0).getCode());
    assertEquals("PAID", list(1, page.getNextCursor()).getItems(0).getCode());
    assertFalse(list(1, page.getNextCursor()).hasNextCursor());
    assertThrows(DomainException.class, () -> list(2, page.getNextCursor()));
    assertThrows(DomainException.class, () -> list(1, page.getNextCursor() + "x"));
    assertThrows(DomainException.class, () -> list(101, null));
    assertThrows(DomainException.class, () -> list(-1, null));
    var expired =
        new CatalogCursor(
            Base64.getEncoder().encodeToString(new byte[32]),
            Clock.fixed(NOW.plusSeconds(900), ZoneOffset.UTC));
    assertThrows(DomainException.class, () -> expired.decode(page.getNextCursor(), 1));
  }

  @Test
  void validationRejectsAllInvalidShapesWithoutWrites() {
    var p = payload();
    var bad = new ArrayList<CreatePlanVersionRequest>();
    bad.add(p.toBuilder().setPrice(p.getPrice().toBuilder().setAmountMinor("-1")).build());
    bad.add(
        p.toBuilder()
            .setPrice(p.getPrice().toBuilder().setAmountMinor("9223372036854775808"))
            .build());
    bad.add(
        p.toBuilder()
            .setPrice(p.getPrice().toBuilder().setCurrency(Money.CurrencyValue.CURRENCY_USD))
            .build());
    bad.add(p.toBuilder().setPrice(p.getPrice().toBuilder().setExponent(3)).build());
    bad.add(p.toBuilder().setLimits(0, limit(QuotaLimit.KindValue.KIND_SEAT, "0")).build());
    bad.add(p.toBuilder().setLimits(1, limit(QuotaLimit.KindValue.KIND_PROJECT, "-1")).build());
    bad.add(
        p.toBuilder().setLimits(2, limit(QuotaLimit.KindValue.KIND_STORAGE_BYTES, "-1")).build());
    bad.add(
        p.toBuilder().setLimits(3, limit(QuotaLimit.KindValue.KIND_TASK_LIFETIME, "-1")).build());
    bad.add(p.toBuilder().setLimits(1, p.getLimits(0)).build());
    bad.add(p.toBuilder().setKind(PlanVersion.KindValue.KIND_FREE).build());
    bad.add(p.toBuilder().setKindValue(99).build());
    bad.add(p.toBuilder().setName(" ").build());
    bad.add(p.toBuilder().setCode("bad'").build());
    bad.add(p.toBuilder().clearLimits().build());
    bad.add(p.toBuilder().addCapabilities("secret\nbody").build());
    for (var value : bad)
      rejects(
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED",
          create(UUID.randomUUID().toString(), value));
    assertEquals(2, count("catalog_plan_versions"));
    assertEquals(0, count("catalog_mutation_receipts"));
    assertEquals(0, count("global_outbox_events"));
  }

  @Test
  void newVersionCannotChangeStableProductKind() {
    var p =
        payload().toBuilder()
            .setKind(PlanVersion.KindValue.KIND_FREE)
            .setPrice(payload().getPrice().toBuilder().setAmountMinor("0"))
            .setPeriod(CreatePlanVersionRequest.PeriodValue.PERIOD_NONE)
            .setLimits(3, limit(QuotaLimit.KindValue.KIND_TASK_LIFETIME, "50"))
            .build();
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", create("kind", p));
    assertEquals(2, count("catalog_plan_versions"));
  }

  @Test
  void publishRequiresConfirmationAndExpectedVersion() {
    var p = create("p", payload()).block(WAIT);
    rejects(
        DomainStatus.FAILED_PRECONDITION,
        "CONFIRMATION_REQUIRED",
        as(
            "publishPlanVersion",
            catalog.publish(
                publishRequest(p, "no").toBuilder()
                    .setPayload(PublishPlanVersionRequest.getDefaultInstance())
                    .build())));
    rejects(
        DomainStatus.ABORTED,
        "VERSION_CONFLICT",
        as(
            "publishPlanVersion",
            catalog.publish(
                publishRequest(p, "stale").toBuilder()
                    .setMutation(
                        MutationOptions.newBuilder()
                            .setIdempotencyKey("stale")
                            .setExpectedVersion("1"))
                    .build())));
    assertEquals(1, count("global_outbox_events"));
    assertEquals(PlanVersion.StateValue.STATE_PUBLISHED, publish(p, "valid").getState());
  }

  @Test
  void publishedRowsCannotBeMutatedOrDeletedEvenByRuntimeSql() {
    assertThrows(
        RuntimeException.class,
        () ->
            db.sql("UPDATE taska.catalog_plan_versions SET published_at=now() WHERE version=1")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            db.sql("UPDATE taska.catalog_plan_versions SET price_minor=1 WHERE version=1")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        RuntimeException.class,
        () -> db.sql("DELETE FROM taska.catalog_plan_versions").fetch().rowsUpdated().block(WAIT));
    assertThrows(
        RuntimeException.class,
        () ->
            db.sql("UPDATE taska.catalog_plans SET plan_code='OTHER' WHERE plan_code='PAID'")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(2, list(100, null).getItemsCount());
  }

  @Test
  void eventFailureRollsBackPlanAndReceipt() {
    sql(
        "CREATE FUNCTION taska.test_fail_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'fixture failure'; END $$");
    sql(
        "CREATE TRIGGER test_fail_event BEFORE INSERT ON taska.global_outbox_events FOR EACH ROW"
            + " EXECUTE FUNCTION taska.test_fail_event()");
    try {
      assertThrows(RuntimeException.class, () -> create("rollback", payload()).block(WAIT));
      assertEquals(2, count("catalog_plan_versions"));
      assertEquals(0, count("catalog_mutation_receipts"));
    } finally {
      sql("DROP TRIGGER test_fail_event ON taska.global_outbox_events");
      sql("DROP FUNCTION taska.test_fail_event()");
    }
    assertEquals("2", create("rollback", payload()).block(WAIT).getVersion());
  }

  @Test
  void nonAdministrativeAndTenantScopedCredentialsCannotMutate() {
    var req = request("denied", payload());
    rejects(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED", catalog.create(req));
    for (var kind :
        List.of(
            CredentialKind.CREDENTIAL_KIND_WEB_TENANT,
            CredentialKind.CREDENTIAL_KIND_USER_PAT,
            CredentialKind.CREDENTIAL_KIND_USER_OAUTH,
            CredentialKind.CREDENTIAL_KIND_SERVICE)) {
      rejects(
          DomainStatus.PERMISSION_DENIED,
          "ADMIN_SCOPE_DENIED",
          RegistryTestContext.withClaims(
              claims("createPlanVersion").toBuilder().setCredentialKind(kind).build(),
              catalog.create(req)));
    }
    for (var c :
        List.of(
            claims("createPlanVersion").toBuilder().setTenantId(A.toString()).build(),
            claims("createPlanVersion").toBuilder().clearAdministrativeScope().build(),
            claims("createPlanVersion").toBuilder()
                .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
                .build(),
            claims("createPlanVersion").toBuilder()
                .setAdministrativeScope(
                    AdminScope.newBuilder()
                        .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                        .addTenantIds(A.toString()))
                .build(),
            claims("createPlanVersion").toBuilder().clearScopes().addScopes("read").build()))
      rejects(
          DomainStatus.PERMISSION_DENIED,
          "ADMIN_SCOPE_DENIED",
          RegistryTestContext.withClaims(c, catalog.create(req)));
    assertEquals(2, count("catalog_plan_versions"));
  }

  @Test
  void platformModifyIntegrationsRequireExplicitCurrentGrant() {
    var c =
        claims("createPlanVersion").toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
            .clearSessionId()
            .setCredentialId(UUID.randomUUID().toString())
            .setGrantId(UUID.randomUUID().toString())
            .setGrantGeneration("2")
            .build();
    var req = request("pat", payload());
    assertEquals(
        "2", RegistryTestContext.withClaims(c, catalog.create(req)).block(WAIT).getVersion());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "ADMIN_SCOPE_DENIED",
        RegistryTestContext.withClaims(
            c.toBuilder().clearGrantGeneration().build(),
            catalog.create(request("bad", payload()))));
  }

  @Test
  void tenantInfrastructureRejectsMissingOrForeignScope() {
    var executor = new ru.taska.r2dbc.TenantTransactionExecutor(pool);
    var claim =
        claims("fixture").toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .setTenantId(A.toString())
            .build();
    var write =
        executor.execute(
            client ->
                client
                    .sql(
                        "INSERT INTO"
                            + " taska.processed_events(tenant_id,consumer,event_id,processed_at)"
                            + " VALUES(:tenant,'fixture',:event,now())")
                    .bind("tenant", A)
                    .bind("event", UUID.randomUUID())
                    .fetch()
                    .rowsUpdated());
    RegistryTestContext.withClaims(claim, write).block(WAIT);
    assertEquals(
        0L,
        db.sql("SELECT count(*) n FROM taska.processed_events")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    var other = claim.toBuilder().setTenantId(B.toString()).build();
    assertEquals(
        0L,
        RegistryTestContext.withClaims(
                other,
                executor.execute(
                    client ->
                        client
                            .sql("SELECT count(*) n FROM taska.processed_events")
                            .map((r, m) -> r.get("n", Long.class))
                            .one()))
            .block(WAIT));
    assertThrows(
        RuntimeException.class, () -> RegistryTestContext.withClaims(other, write).block(WAIT));
    assertEquals(1, count("processed_events"));
  }

  @Test
  void missingCursorKeyFailsClosedAndUnsafeRuntimeRoleIsRejected() {
    var closed = new BillingCatalog(db, tx, new CatalogCursor("", Clock.systemUTC()));
    rejects(
        DomainStatus.UNAVAILABLE,
        "POLICY_NOT_CONFIGURED",
        as("listBillingPlans", closed.list(ListBillingPlansRpcRequest.getDefaultInstance())));
    assertThrows(IllegalArgumentException.class, () -> new CatalogCursor("bad", Clock.systemUTC()));
    assertThrows(
        IllegalStateException.class,
        () ->
            new BillingConfiguration()
                .safeRuntimeRole(factory(pg.getUsername(), pg.getPassword()))
                .afterPropertiesSet());
  }

  @Test
  void internalResolutionReturnsOnlyCurrentPublishedOffersToAllowlistedServices() {
    var req =
        ResolvePublishedPlanRpcRequest.newBuilder()
            .setPlanVersionId("00000000-0000-8000-8000-000000000002")
            .build();
    var service =
        claims("resolvePublishedPlan").toBuilder()
            .clearActorUserId()
            .clearSessionId()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setIssuerService("tenant-service")
            .addScopes("catalog:resolve")
            .build();
    var first = RegistryTestContext.withClaims(service, catalog.resolve(req)).block(WAIT);
    var draft = create("resolve", payload()).block(WAIT);
    rejects(
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        RegistryTestContext.withClaims(
            service, catalog.resolve(req.toBuilder().setPlanVersionId(draft.getId()).build())));
    publish(draft, "resolve-publish");
    rejects(
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        RegistryTestContext.withClaims(service, catalog.resolve(req)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            service.toBuilder().setIssuerService("issue-service").build(), catalog.resolve(req)));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(claims("resolvePublishedPlan"), catalog.resolve(req)));
  }

  @Test
  void applicationStartsWithSeparateMigrationAndRuntimeRolesAndFailsClosed() throws Exception {
    int grpcPort;
    try (var socket = new java.net.ServerSocket(0)) {
      grpcPort = socket.getLocalPort();
    }
    var app = new org.springframework.boot.SpringApplication(BillingApplication.class);
    app.addInitializers(
        context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "isolated-fixture",
                        java.util.Map.of(
                            "server.port",
                            "0",
                            "spring.grpc.server.port",
                            Integer.toString(grpcPort),
                            "spring.r2dbc.url",
                            "r2dbc:postgresql://"
                                + pg.getHost()
                                + ":"
                                + pg.getMappedPort(5432)
                                + "/"
                                + pg.getDatabaseName(),
                            "spring.r2dbc.username",
                            "taska_billing_app",
                            "spring.r2dbc.password",
                            APP_PASSWORD,
                            "spring.liquibase.url",
                            pg.getJdbcUrl(),
                            "spring.liquibase.user",
                            "taska_billing_deployer",
                            "spring.liquibase.password",
                            DEPLOY_PASSWORD,
                            "logging.level.root",
                            "WARN"))));
    // CLI property precedence overrides application.yml without exposing secrets in argv.
    try (var context = app.run("--server.port=0", "--spring.grpc.server.port=" + grpcPort)) {
      var channel =
          io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress("localhost", grpcPort)
              .usePlaintext()
              .build();
      try {
        var stub =
            ru.taska.api.b2b.v1.BillingServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        assertEquals(
            io.grpc.Status.Code.UNAVAILABLE,
            assertThrows(
                    io.grpc.StatusRuntimeException.class,
                    () -> stub.listBillingPlans(ListBillingPlansRpcRequest.getDefaultInstance()))
                .getStatus()
                .getCode());
      } finally {
        channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      }
    }
  }

  static void openssl(java.nio.file.Path directory, String... args) throws Exception {
    var command = new ArrayList<>(List.of("openssl"));
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    byte[] output = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    assertEquals(
        0, process.exitValue(), new String(output, java.nio.charset.StandardCharsets.UTF_8));
  }

  static io.grpc.Metadata signed(
      ru.taska.security.tenant.RpcContextSecurity sender,
      com.google.protobuf.Message message,
      ru.taska.security.tenant.RpcContextSecurity.Policy policy,
      String rpc) {
    var parent =
        sender
            .authorize(ru.taska.security.tenant.RpcContextSecurity.context(message), rpc, policy)
            .block(WAIT);
    var metadata = new io.grpc.Metadata();
    metadata.put(
        io.grpc.Metadata.Key.of("x-taska-context", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
        sender.sign(parent, message, rpc, policy).block(WAIT));
    return metadata;
  }

  @Test
  void realMtlsAndSignedPayloadProtectAllCatalogRpcs(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path certs) throws Exception {
    openssl(
        certs,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt",
        "-days",
        "1",
        "-subj",
        "/CN=tenant-test-ca");
    for (String name : List.of("api-gateway", "billing-service")) {
      openssl(
          certs,
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=localhost");
      java.nio.file.Files.writeString(
          certs.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      openssl(
          certs,
          "x509",
          "-req",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          name + ".crt",
          "-days",
          "1",
          "-extfile",
          name + ".ext");
    }

    var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new ru.taska.security.tenant.RpcContextSecurity.Keys() {
          public ru.taska.security.tenant.RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new ru.taska.security.tenant.RpcContextSecurity.SigningKey(
                "fixture", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return issuer.equals("api-gateway") && kid.equals("fixture") ? pair.getPublic() : null;
          }
        };
    var consumed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    ru.taska.security.tenant.RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + ":" + jti));
    var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    ru.taska.security.tenant.RpcContextSecurity.Authority authority =
        (context, rpc, policy) ->
            Mono.just(
                allowed.get() && context.getContext().getIssuerService().equals("api-gateway"));
    var sender =
        new ru.taska.security.tenant.RpcContextSecurity(
            "api-gateway", Clock.systemUTC(), keys, replay, authority);
    var receiver =
        new ru.taska.security.tenant.RpcContextSecurity(
            "billing-service", Clock.systemUTC(), keys, replay, authority);
    var provider = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    provider.registerSingleton("security", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(
                provider.getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class),
                "test");
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                        certs.resolve("billing-service.crt").toFile(),
                        certs.resolve("billing-service.key").toFile())
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(new BillingCatalogRpc(catalog), interceptor))
            .build()
            .start();
    var channel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("api-gateway.crt").toFile(),
                        certs.resolve("api-gateway.key").toFile())
                    .build())
            .build();
    try {
      var plain =
          ru.taska.api.b2b.v1.BillingServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS);
      var create =
          request("signed", payload()).toBuilder().setContext(wire("createPlanVersion")).build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.createPlanVersion(create))
              .getStatus()
              .getCode());
      String rpc =
          ru.taska.api.b2b.v1.BillingServiceGrpc.getCreatePlanVersionMethod().getFullMethodName();
      var meta =
          signed(
              sender,
              create,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(false, true),
              rpc);
      var stub =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(meta));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () ->
                      stub.createPlanVersion(
                          create.toBuilder()
                              .setPayload(payload().toBuilder().setName("tampered"))
                              .build()))
              .getStatus()
              .getCode());
      var draft = stub.createPlanVersion(create);
      assertEquals(PlanVersion.StateValue.STATE_DRAFT, draft.getState());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.createPlanVersion(create))
              .getStatus()
              .getCode());
      var pub =
          publishRequest(draft, "signed-publish").toBuilder()
              .setContext(wire("publishPlanVersion"))
              .build();
      rpc =
          ru.taska.api.b2b.v1.BillingServiceGrpc.getPublishPlanVersionMethod().getFullMethodName();
      assertEquals(
          PlanVersion.StateValue.STATE_PUBLISHED,
          plain
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      signed(
                          sender,
                          pub,
                          new ru.taska.security.tenant.RpcContextSecurity.Policy(false, true),
                          rpc)))
              .publishPlanVersion(pub)
              .getState());
      var read =
          ListBillingPlansRpcRequest.newBuilder().setContext(wire("listBillingPlans")).build();
      rpc = ru.taska.api.b2b.v1.BillingServiceGrpc.getListBillingPlansMethod().getFullMethodName();
      assertEquals(
          draft.getId(),
          plain
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      signed(
                          sender,
                          read,
                          new ru.taska.security.tenant.RpcContextSecurity.Policy(false, false),
                          rpc)))
              .listBillingPlans(read)
              .getItems(1)
              .getId());
      var resolve =
          ResolvePublishedPlanRpcRequest.newBuilder()
              .setPlanVersionId(draft.getId())
              .setContext(
                  wire("resolvePublishedPlan").toBuilder()
                      .setContext(
                          wire("resolvePublishedPlan").getContext().toBuilder()
                              .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                              .clearActorUserId()
                              .clearSessionId()
                              .addScopes("catalog:resolve")))
              .build();
      rpc =
          ru.taska.api.b2b.v1.BillingServiceGrpc.getResolvePublishedPlanMethod()
              .getFullMethodName();
      assertEquals(
          draft.getId(),
          plain
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      signed(
                          sender,
                          resolve,
                          new ru.taska.security.tenant.RpcContextSecurity.Policy(false, false),
                          rpc)))
              .resolvePublishedPlan(resolve)
              .getId());
      var fresh =
          request("denied-after-review", payload()).toBuilder()
              .setContext(wire("createPlanVersion"))
              .build();
      rpc = ru.taska.api.b2b.v1.BillingServiceGrpc.getCreatePlanVersionMethod().getFullMethodName();
      meta =
          signed(
              sender,
              fresh,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(false, true),
              rpc);
      allowed.set(false);
      var denied =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(meta));
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> denied.createPlanVersion(fresh))
              .getStatus()
              .getCode());
      assertEquals(3, count("catalog_plan_versions"));
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private ru.taska.api.b2b.v1.RequestContext wire(String action) {
    Instant now = Instant.now();
    return ru.taska.api.b2b.v1.RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            claims(action).toBuilder()
                .setIssuerService("api-gateway")
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }
}
