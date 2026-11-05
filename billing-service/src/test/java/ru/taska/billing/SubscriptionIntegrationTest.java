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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.GetSubscriptionRpcRequest;
import ru.taska.api.b2b.v1.ListSubscriptionPeriodsRpcRequest;
import ru.taska.api.b2b.v1.PageRequest;
import ru.taska.api.b2b.v1.Subscription;
import ru.taska.api.b2b.v1.SubscriptionPeriod;
import ru.taska.api.b2b.v1.SubscriptionPeriodPage;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.billing.SubscriptionDependencies.CoverageEvidence;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.billing.SubscriptionDependencies.PaymentSource;
import ru.taska.billing.SubscriptionDependencies.RevocationEvidence;
import ru.taska.billing.SubscriptionDependencies.SettlementEvidenceAuthority;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextBridge;

@Testcontainers
@Timeout(90)
class SubscriptionIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      OWNER_A = UUID.randomUUID(),
      OWNER_B = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      ADMIN = UUID.randomUUID();
  static final UUID FREE = UUID.fromString("00000000-0000-8000-8000-000000000001"),
      PAID = UUID.fromString("00000000-0000-8000-8000-000000000002");
  static final Instant NOW = Instant.parse("2026-01-31T12:34:56Z");
  static final Duration WAIT = Duration.ofSeconds(20);
  static ConnectionPool pool;
  static DatabaseClient db, operator;
  static TransactionalOperator tx;
  TenantTransactionExecutor transactions;
  SubscriptionStore store;
  AtomicReference<Instant> time;
  AtomicBoolean offline;
  Clock clock;
  Map<UUID, CoverageEvidence> confirmed;
  Map<UUID, RevocationEvidence> reversed;
  Map<String, String> owners;
  Set<String> active;
  FinancialReadAuthority readers;
  SettlementEvidenceAuthority evidence;

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
    var source =
        new BillingConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_billing_deployer", DEPLOY_PASSWORD)
            .getDataSource();
    try (var connection = source.getConnection()) {
      var database =
          liquibase.database.DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(
                  new liquibase.database.jvm.JdbcConnection(connection));
      database.setLiquibaseSchemaName("billing_liquibase");
      try (var runner =
          new liquibase.Liquibase(
              "db/changelog/db.changelog-master.yaml",
              new liquibase.resource.ClassLoaderResourceAccessor(),
              database)) {
        runner.update(2, new liquibase.Contexts(), new liquibase.LabelExpression());
      }
    }
    String checksum =
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-catalog-0001'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT);
    assertEquals(
        2L,
        operator
            .sql("SELECT count(*) n FROM taska.catalog_plan_versions")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    UUID upgradeTenant = UUID.randomUUID();
    sql(
        "INSERT INTO"
            + " taska.subscriptions(tenant_id,selected_plan_version_id,selected_terms_snapshot,mode)"
            + " VALUES('"
            + upgradeTenant
            + "','"
            + PAID
            + "','{\"upgrade\":true}','AWAITING_PAYMENT')");
    String subscriptionChecksum =
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-subscriptions-0002'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT);
    migrateNext(); // 0003: start the ledger upgrade with a real issued invoice.
    UUID upgradeInvoice = UUID.randomUUID();
    InvoiceTestFixtures.ensure(operator, upgradeTenant, upgradeInvoice, PAID);
    String invoiceChecksum =
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-invoices-0003'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT);
    migrate();
    migrate();
    assertEquals(
        invoiceChecksum,
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-invoices-0003'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT));
    assertEquals(
        "OPEN",
        operator
            .sql(
                "SELECT state FROM taska.invoices WHERE tenant_id='"
                    + upgradeTenant
                    + "' AND id='"
                    + upgradeInvoice
                    + "'")
            .map((r, m) -> r.get("state", String.class))
            .one()
            .block(WAIT));
    assertEquals(
        subscriptionChecksum,
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-subscriptions-0002'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT));
    assertEquals(
        "AWAITING_PAYMENT",
        operator
            .sql("SELECT mode FROM taska.subscriptions WHERE tenant_id='" + upgradeTenant + "'")
            .map((r, m) -> r.get("mode", String.class))
            .one()
            .block(WAIT));
    assertEquals(
        1L,
        operator
            .sql(
                "SELECT count(*) n FROM pg_constraint WHERE"
                    + " conname='subscription_period_invoice_fk'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        checksum,
        operator
            .sql(
                "SELECT md5sum FROM billing_liquibase.databasechangelog WHERE"
                    + " id='billing-catalog-0001'")
            .map((r, m) -> r.get("md5sum", String.class))
            .one()
            .block(WAIT));
    assertEquals(
        2L,
        operator
            .sql("SELECT count(*) n FROM taska.catalog_plan_versions")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
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

  static void migrateNext() throws Exception {
    var source =
        new BillingConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_billing_deployer", DEPLOY_PASSWORD)
            .getDataSource();
    try (var connection = source.getConnection()) {
      var database =
          liquibase.database.DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(
                  new liquibase.database.jvm.JdbcConnection(connection));
      database.setLiquibaseSchemaName("billing_liquibase");
      try (var runner =
          new liquibase.Liquibase(
              "db/changelog/db.changelog-master.yaml",
              new liquibase.resource.ClassLoaderResourceAccessor(),
              database)) {
        runner.update(1, new liquibase.Contexts(), new liquibase.LabelExpression());
      }
    }
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
    sql(
        "TRUNCATE"
            + " taska.payment_facts,taska.payment_allocations,taska.invoice_payment_matches,taska.financial_command_receipts,taska.invoices,taska.invoice_lines,taska.invoice_command_receipts,taska.subscriptions,taska.subscription_periods,taska.subscription_command_receipts,taska.outbox_events,taska.idempotency_records,taska.processed_events");
    sql("ALTER TABLE taska.catalog_plan_versions DISABLE TRIGGER immutable_plan");
    sql("DELETE FROM taska.catalog_plan_versions WHERE version>1");
    sql("ALTER TABLE taska.catalog_plan_versions ENABLE TRIGGER immutable_plan");
    transactions = new TenantTransactionExecutor(pool);
    time = new AtomicReference<>(NOW);
    offline = new AtomicBoolean();
    clock =
        new Clock() {
          public Instant instant() {
            return time.get();
          }

          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(java.time.ZoneId z) {
            return this;
          }
        };
    confirmed = new ConcurrentHashMap<>();
    reversed = new ConcurrentHashMap<>();
    owners =
        new ConcurrentHashMap<>(
            Map.of(A.toString(), OWNER_A.toString(), B.toString(), OWNER_B.toString()));
    active = ConcurrentHashMap.newKeySet();
    active.addAll(List.of(OWNER_A.toString(), OWNER_B.toString()));
    readers =
        v -> {
          if (offline.get())
            return Mono.error(
                CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
          var c = RpcContextBridge.details(v).getContext();
          boolean admin =
              Set.of(
                          CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
                          CredentialKind.CREDENTIAL_KIND_ADMIN_PAT,
                          CredentialKind.CREDENTIAL_KIND_ADMIN_OAUTH)
                      .contains(c.getCredentialKind())
                  && ADMIN.toString().equals(v.actorId());
          return Mono.just(
              admin
                  || (v.actorId().equals(owners.get(v.tenantId()))
                      && active.contains(v.actorId())
                      && c.getMembershipGeneration().equals("1")));
        };
    evidence =
        new SettlementEvidenceAuthority() {
          public Mono<CoverageEvidence> confirmed(DatabaseClient ignored, UUID tenant, UUID id) {
            return offline.get()
                ? Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))
                : Mono.justOrEmpty(confirmed.get(id));
          }

          public Mono<RevocationEvidence> reversed(DatabaseClient ignored, UUID tenant, UUID id) {
            return offline.get()
                ? Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))
                : Mono.justOrEmpty(reversed.get(id));
          }
        };
    store =
        new SubscriptionStore(
            transactions,
            readers,
            evidence,
            clock,
            new PeriodCursor(Base64.getEncoder().encodeToString(new byte[32]), clock));
  }

  @AfterEach
  void scopeDoesNotLeak() {
    var row =
        db.sql(
                "SELECT current_user::text role,NULLIF(current_setting('app.tenant_id',true),'')"
                    + " tenant,NULLIF(current_setting('app.actor_id',true),'') actor")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals("taska_billing_app", row.get("role"));
    assertEquals(null, row.get("tenant"));
    assertEquals(null, row.get("actor"));
    assertEquals(
        0L,
        db.sql("SELECT count(*) n FROM taska.subscriptions")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  TrustedTenantContext human(UUID actor, UUID tenant, String action) {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(actor.toString())
        .setTenantId(tenant.toString())
        .setActorGeneration("1")
        .setMembershipGeneration("1")
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
        .setSessionId(
            UUID.nameUUIDFromBytes(
                    actor.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString())
        .setIssuerService("api-gateway")
        .setAudienceService("billing-service")
        .setAction(action)
        .addScopes("read")
        .build();
  }

  TrustedTenantContext worker(UUID tenant, String action, String issuer) {
    return TrustedTenantContext.newBuilder()
        .setTenantId(tenant.toString())
        .setActorGeneration("1")
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
        .setIssuerService(issuer)
        .setAudienceService("billing-service")
        .setAction(action)
        .addScopes("write")
        .build();
  }

  <T> Mono<T> asWorker(UUID tenant, String action, String issuer, Mono<T> work) {
    return RegistryTestContext.withClaims(worker(tenant, action, issuer), work);
  }

  Mono<Subscription> init(UUID tenant, UUID plan, String key) {
    return asWorker(
        tenant, "initializeSubscription", "tenant-service", store.initialize(tenant, plan, key));
  }

  Mono<Subscription> grant(UUID tenant, UUID proof, String key) {
    return asWorker(
        tenant, "applySubscriptionCoverage", "billing-service", store.grant(tenant, proof, key));
  }

  Mono<Subscription> revoke(UUID tenant, UUID proof, String key) {
    return asWorker(
        tenant, "revokeSubscriptionCoverage", "billing-service", store.revoke(tenant, proof, key));
  }

  Subscription get(UUID actor, UUID tenant) {
    return RegistryTestContext.withClaims(
            human(actor, tenant, "getSubscription"),
            store.get(
                GetSubscriptionRpcRequest.newBuilder().setTenantId(tenant.toString()).build()))
        .block(WAIT);
  }

  Mono<Subscription> get(TrustedTenantContext claims, UUID target) {
    return RegistryTestContext.withClaims(
        claims,
        store.get(GetSubscriptionRpcRequest.newBuilder().setTenantId(target.toString()).build()));
  }

  SubscriptionPeriodPage periods(UUID actor, UUID tenant, int size, String token) {
    var p = PageRequest.newBuilder().setLimit(size);
    if (token != null) p.setCursor(token);
    return RegistryTestContext.withClaims(
            human(actor, tenant, "listSubscriptionPeriods"),
            store.periods(
                ListSubscriptionPeriodsRpcRequest.newBuilder()
                    .setTenantId(tenant.toString())
                    .setPage(p)
                    .build()))
        .block(WAIT);
  }

  UUID proof(UUID tenant, UUID invoice, UUID fact, Instant start, Instant end) {
    InvoiceTestFixtures.ensure(operator, tenant, invoice, PAID);
    UUID key = UUID.randomUUID();
    confirmed.put(
        key,
        new CoverageEvidence(
            tenant,
            invoice,
            fact,
            PAID,
            UUID.randomUUID(),
            start,
            end,
            31,
            LocalTime.of(12, 34, 56),
            start,
            PaymentSource.PSP,
            List.of()));
    return key;
  }

  UUID proof(UUID tenant, Instant start, Instant end) {
    return proof(tenant, UUID.randomUUID(), UUID.randomUUID(), start, end);
  }

  UUID reverse(CoverageEvidence e) {
    UUID key = UUID.randomUUID();
    reversed.put(key, new RevocationEvidence(e.tenantId(), e.invoiceId(), e.financialFactId()));
    return key;
  }

  static void rejects(DomainStatus status, String code, Mono<?> work) {
    var e = assertThrows(DomainException.class, () -> work.block(WAIT));
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

  static Map<String, Object> header(UUID tenant) {
    return operator
        .sql("SELECT * FROM taska.subscriptions WHERE tenant_id=:t")
        .bind("t", tenant)
        .fetch()
        .one()
        .block(WAIT);
  }

  @Test
  void freeAndAwaitingPaymentAreDistinctWithoutFabricatedCoverage() {
    init(A, FREE, "free").block(WAIT);
    init(B, PAID, "paid").block(WAIT);
    assertEquals("FREE", header(A).get("mode"));
    assertEquals("AWAITING_PAYMENT", header(B).get("mode"));
    for (var s : List.of(get(OWNER_A, A), get(OWNER_B, B))) {
      assertFalse(s.hasPaidThrough());
      assertFalse(s.hasCurrentPeriodId());
      assertEquals(Subscription.AutopayStateValue.AUTOPAY_STATE_DISABLED, s.getAutopayState());
    }
    assertEquals(0, count("subscription_periods"));
    assertEquals(0, periods(OWNER_B, B, 0, null).getItemsCount());
  }

  @Test
  void initializationRacesHaveOneSubscriptionAndNoPeriod() {
    var results =
        Flux.range(0, 8)
            .flatMap(i -> init(A, PAID, "init-" + i).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().map(Subscription::getVersion).distinct().count());
    assertEquals(1, count("subscriptions"));
    assertEquals(1, count("outbox_events"));
    assertEquals(0, count("subscription_periods"));
  }

  @Test
  void retryAndConflictPreserveState() {
    var first = init(A, PAID, "same").block(WAIT);
    assertEquals(first, init(A, PAID, "same").block(WAIT));
    rejects(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT", init(A, FREE, "same"));
    rejects(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE", init(A, FREE, "different"));
    assertEquals(PAID.toString(), get(OWNER_A, A).getPlanVersionId());
    assertEquals(1, count("subscription_command_receipts"));
  }

  @Test
  void noEvidenceAndOfflineAdaptersCannotGrantPaidTime() {
    init(A, PAID, "init").block(WAIT);
    rejects(
        DomainStatus.FAILED_PRECONDITION,
        "PAYMENT_PENDING",
        grant(A, UUID.randomUUID(), "pending"));
    offline.set(true);
    rejects(
        DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", grant(A, UUID.randomUUID(), "offline"));
    assertEquals("1", header(A).get("version").toString());
    assertEquals(0, count("subscription_periods"));
    assertEquals(1, count("subscription_command_receipts"));
  }

  @Test
  void confirmedCoverageIsOncePerInvoiceAcrossConcurrentCommands() {
    init(A, PAID, "init").block(WAIT);
    UUID proof = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    var results =
        Flux.range(0, 8)
            .flatMap(i -> grant(A, proof, "grant-" + i).subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(
        Set.of("2"),
        results.stream()
            .map(Subscription::getVersion)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(1, count("subscription_periods"));
    assertEquals(2, count("outbox_events"));
    assertEquals("2026-02-28T12:34:56Z", get(OWNER_A, A).getPaidThrough());
  }

  @Test
  void duplicateInvoiceWithAnotherSourceDoesNotGrantSecondMonth() {
    init(A, PAID, "init").block(WAIT);
    UUID invoice = UUID.randomUUID();
    UUID first = proof(A, invoice, UUID.randomUUID(), NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, first, "first").block(WAIT);
    UUID second = proof(A, invoice, UUID.randomUUID(), NOW, Instant.parse("2026-02-28T12:34:56Z"));
    rejects(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT", grant(A, second, "second"));
    assertEquals(1, count("subscription_periods"));
    assertEquals("2", get(OWNER_A, A).getVersion());
  }

  @Test
  void invalidForeignEvidenceRollsBackWithoutStateChanges() {
    init(A, PAID, "init").block(WAIT);
    UUID proof = proof(B, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", grant(A, proof, "foreign"));
    assertEquals(0, count("subscription_periods"));
    assertEquals("1", get(OWNER_A, A).getVersion());
  }

  @Test
  void earlyRenewalKeepsCurrentTermsUntilExactUtcBoundary() {
    init(A, PAID, "init").block(WAIT);
    Instant feb = Instant.parse("2026-02-28T12:34:56Z"),
        mar = Instant.parse("2026-03-31T12:34:56Z");
    UUID first = proof(A, NOW, feb), next = proof(A, feb, mar);
    grant(A, first, "first").block(WAIT);
    grant(A, next, "next").block(WAIT);
    assertEquals(feb.toString(), get(OWNER_A, A).getPaidThrough());
    assertEquals(
        confirmed.get(first).activationOperationId().toString(),
        get(OWNER_A, A).getCurrentPeriodId());
    time.set(feb);
    assertEquals(mar.toString(), get(OWNER_A, A).getPaidThrough());
    assertEquals(31, get(OWNER_A, A).getAnchorDay());
    time.set(mar);
    assertEquals(mar.toString(), get(OWNER_A, A).getPaidThrough());
    assertFalse(time.get().isBefore(Instant.parse(get(OWNER_A, A).getPaidThrough())));
  }

  @Test
  void financialSnapshotDoesNotFollowNewCatalogPublication() {
    init(A, PAID, "init").block(WAIT);
    UUID proof = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, proof, "grant").block(WAIT);
    String snapshot =
        operator
            .sql("SELECT terms_snapshot::text s FROM taska.subscription_periods")
            .map((r, m) -> r.get("s", String.class))
            .one()
            .block(WAIT);
    sql(
        "INSERT INTO"
            + " taska.catalog_plan_versions(id,plan_code,version,name,price_minor,currency,currency_exponent,period,max_projects,max_members,max_storage_bytes,max_tasks_per_project,features,published_at)"
            + " SELECT"
            + " gen_random_uuid(),plan_code,2,'Changed',9900,currency,currency_exponent,period,99,99,max_storage_bytes,max_tasks_per_project,features,now()"
            + " FROM taska.catalog_plan_versions WHERE plan_code='PAID' AND version=1");
    assertEquals(
        snapshot,
        operator
            .sql("SELECT terms_snapshot::text s FROM taska.subscription_periods")
            .map((r, m) -> r.get("s", String.class))
            .one()
            .block(WAIT));
    assertTrue(snapshot.contains("2000"));
    assertEquals(PAID.toString(), get(OWNER_A, A).getPlanVersionId());
  }

  @Test
  void d251RevokesWholeLinkedCoverageAndDoesNotResurrectOnRetry() {
    init(A, PAID, "init").block(WAIT);
    UUID p = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, p, "paid").block(WAIT);
    UUID r = reverse(confirmed.get(p));
    var revoked = revoke(A, r, "loss").block(WAIT);
    assertFalse(revoked.hasPaidThrough());
    assertEquals("PAID", header(A).get("mode"));
    assertEquals(
        "REVOKED",
        operator
            .sql("SELECT status FROM taska.subscription_periods")
            .map((row, m) -> row.get("status", String.class))
            .one()
            .block(WAIT));
    assertEquals(revoked, revoke(A, r, "loss").block(WAIT));
    assertFalse(grant(A, p, "another-delivery").block(WAIT).hasPaidThrough());
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void oldOrDuplicateFinancialLossDoesNotRevokeIndependentNewCoverage() {
    init(A, PAID, "init").block(WAIT);
    UUID old = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, old, "old").block(WAIT);
    time.set(NOW.plusSeconds(3600));
    UUID newer = proof(A, time.get(), Instant.parse("2026-02-28T13:34:56Z"));
    grant(A, newer, "new").block(WAIT);
    UUID r = reverse(confirmed.get(old));
    var result = revoke(A, r, "loss").block(WAIT);
    assertEquals(
        confirmed.get(newer).activationOperationId().toString(), result.getCurrentPeriodId());
    assertEquals("2026-02-28T13:34:56Z", result.getPaidThrough());
    assertEquals(result.getVersion(), revoke(A, r, "duplicate-loss").block(WAIT).getVersion());
    assertEquals(
        SubscriptionPeriod.StateValue.STATE_GRANTED,
        periods(OWNER_A, A, 100, null).getItems(0).getState());
  }

  @Test
  void newFullPaymentAfterRevocationStartsAnIndependentFullMonth() {
    init(A, PAID, "init").block(WAIT);
    UUID old = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, old, "old").block(WAIT);
    revoke(A, reverse(confirmed.get(old)), "loss").block(WAIT);
    time.set(Instant.parse("2026-02-10T15:00:00Z"));
    UUID next = proof(A, time.get(), Instant.parse("2026-03-10T15:00:00Z"));
    var e = confirmed.get(next);
    confirmed.put(
        next,
        new CoverageEvidence(
            A,
            e.invoiceId(),
            e.financialFactId(),
            PAID,
            e.activationOperationId(),
            e.startsAt(),
            e.endsAt(),
            10,
            LocalTime.of(15, 0),
            e.startsAt(),
            PaymentSource.WIRE,
            List.of()));
    var sub = grant(A, next, "new-full-payment").block(WAIT);
    assertEquals("2026-03-10T15:00:00Z", sub.getPaidThrough());
    assertEquals(10, sub.getAnchorDay());
    assertEquals(2, count("subscription_periods"));
  }

  @Test
  void explicitSupersedeIsScopedAndAtomic() {
    init(A, PAID, "init").block(WAIT);
    UUID old = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, old, "old").block(WAIT);
    UUID next = proof(A, NOW.plusSeconds(60), Instant.parse("2026-02-28T12:35:56Z"));
    var e = confirmed.get(next);
    confirmed.put(
        next,
        new CoverageEvidence(
            A,
            e.invoiceId(),
            e.financialFactId(),
            PAID,
            e.activationOperationId(),
            e.startsAt(),
            e.endsAt(),
            31,
            e.anchorTimeUtc(),
            e.confirmedAt(),
            e.source(),
            List.of(confirmed.get(old).activationOperationId())));
    rejects(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE", grant(A, next, "new"));
    assertEquals(
        confirmed.get(old).activationOperationId().toString(),
        get(OWNER_A, A).getCurrentPeriodId());
    time.set(e.startsAt());
    grant(A, next, "new").block(WAIT);
    assertEquals(
        "SUPERSEDED",
        operator
            .sql("SELECT status FROM taska.subscription_periods WHERE id=:id")
            .bind("id", confirmed.get(old).activationOperationId())
            .map((r, m) -> r.get("status", String.class))
            .one()
            .block(WAIT));
  }

  @Test
  void outerSettlementRollbackRestoresVersionPeriodAndOutbox() {
    init(A, PAID, "init").block(WAIT);
    UUID p = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    var work =
        transactions.execute(
            client ->
                store
                    .grant(A, p, "rollback")
                    .then(Mono.error(new IllegalStateException("fixture rollback"))));
    assertThrows(
        IllegalStateException.class,
        () -> asWorker(A, "applySubscriptionCoverage", "billing-service", work).block(WAIT));
    assertEquals("1", get(OWNER_A, A).getVersion());
    assertEquals(0, count("subscription_periods"));
    assertEquals(1, count("outbox_events"));
    assertEquals(1, count("subscription_command_receipts"));
    grant(A, p, "rollback").block(WAIT);
    assertEquals("2", get(OWNER_A, A).getVersion());
  }

  @Test
  void outboxFailureRollsBackCoverageAndReceipt() {
    init(A, PAID, "init").block(WAIT);
    UUID p = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    sql(
        "CREATE FUNCTION taska.fixture_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE"
            + " EXCEPTION 'fixture'; END $$");
    sql(
        "CREATE TRIGGER fixture_fail BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE"
            + " FUNCTION taska.fixture_fail()");
    try {
      assertThrows(RuntimeException.class, () -> grant(A, p, "retry").block(WAIT));
      assertEquals(0, count("subscription_periods"));
      assertEquals("1", header(A).get("version").toString());
    } finally {
      sql("DROP TRIGGER fixture_fail ON taska.outbox_events");
      sql("DROP FUNCTION taska.fixture_fail()");
    }
    grant(A, p, "retry").block(WAIT);
    assertEquals(1, count("subscription_periods"));
  }

  @Test
  void ordinaryTenantAdminAndFormerOwnerCannotReadFinancialData() {
    init(A, PAID, "init").block(WAIT);
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        get(human(OTHER, A, "getSubscription"), A));
    owners.put(A.toString(), OTHER.toString());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        get(human(OWNER_A, A, "getSubscription"), A));
    active.add(OTHER.toString());
    assertEquals(A.toString(), get(OTHER, A).getTenantId());
    active.remove(OTHER.toString());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        get(human(OTHER, A, "getSubscription"), A));
  }

  @Test
  void tenantSetAdministrativeReadDoesNotBecomePlatformScope() {
    init(A, PAID, "a").block(WAIT);
    init(B, PAID, "b").block(WAIT);
    var admin =
        human(ADMIN, A, "getSubscription").toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
            .clearSessionId()
            .setCredentialId(UUID.randomUUID().toString())
            .setGrantId(UUID.randomUUID().toString())
            .setGrantGeneration("1")
            .setAdministrativeScope(
                AdminScope.newBuilder()
                    .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                    .addTenantIds(A.toString()))
            .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
            .build();
    assertEquals(A.toString(), get(admin, A).block(WAIT).getTenantId());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        get(admin.toBuilder().setTenantId(B.toString()).build(), B));
    var ordinary =
        human(ADMIN, A, "getSubscription").toBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
            .setCredentialId(UUID.randomUUID().toString())
            .build();
    rejects(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED", get(ordinary, A));
  }

  @Test
  void scopePurposeAndMissingAuthorityAreDeniedBeforeData() {
    init(A, PAID, "init").block(WAIT);
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "AUTHENTICATION_REQUIRED",
        store.get(GetSubscriptionRpcRequest.newBuilder().setTenantId(A.toString()).build()));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        get(human(OWNER_A, A, "getSubscription"), B));
    rejects(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH", get(human(OWNER_A, A, "other"), A));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        get(human(OWNER_A, A, "getSubscription").toBuilder().clearScopes().build(), A));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        get(
            human(OWNER_A, A, "getSubscription").toBuilder().clearMembershipGeneration().build(),
            A));
    offline.set(true);
    rejects(
        DomainStatus.UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        get(human(OWNER_A, A, "getSubscription"), A));
  }

  @Test
  void readWithoutCursorKeyDoesNotDisableSubscriptionGet() {
    init(A, PAID, "init").block(WAIT);
    var noCursor =
        new SubscriptionStore(transactions, readers, evidence, clock, new PeriodCursor("", clock));
    assertEquals(
        A.toString(),
        RegistryTestContext.withClaims(
                human(OWNER_A, A, "getSubscription"),
                noCursor.get(
                    GetSubscriptionRpcRequest.newBuilder().setTenantId(A.toString()).build()))
            .block(WAIT)
            .getTenantId());
    rejects(
        DomainStatus.UNAVAILABLE,
        "POLICY_NOT_CONFIGURED",
        RegistryTestContext.withClaims(
            human(OWNER_A, A, "listSubscriptionPeriods"),
            noCursor.periods(
                ListSubscriptionPeriodsRpcRequest.newBuilder().setTenantId(A.toString()).build())));
  }

  @Test
  void periodPaginationIncludesRevokedHistoryAndInvalidatesChangedGeneration() {
    init(A, PAID, "init").block(WAIT);
    UUID first = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z")),
        second =
            proof(A, Instant.parse("2026-02-28T12:34:56Z"), Instant.parse("2026-03-31T12:34:56Z"));
    grant(A, first, "one").block(WAIT);
    grant(A, second, "two").block(WAIT);
    var page = periods(OWNER_A, A, 1, null);
    assertTrue(page.hasNextCursor());
    assertEquals(
        confirmed.get(second).activationOperationId().toString(), page.getItems(0).getId());
    assertEquals(
        confirmed.get(first).activationOperationId().toString(),
        periods(OWNER_A, A, 1, page.getNextCursor()).getItems(0).getId());
    assertThrows(DomainException.class, () -> periods(OWNER_A, A, 2, page.getNextCursor()));
    assertThrows(DomainException.class, () -> periods(OWNER_A, A, 1, page.getNextCursor() + "x"));
    revoke(A, reverse(confirmed.get(first)), "loss").block(WAIT);
    assertThrows(DomainException.class, () -> periods(OWNER_A, A, 1, page.getNextCursor()));
    assertEquals(2, periods(OWNER_A, A, 0, null).getItemsCount());
    assertThrows(DomainException.class, () -> periods(OWNER_A, A, 101, null));
    assertThrows(DomainException.class, () -> periods(OWNER_A, A, -1, null));
  }

  @Test
  void actualRuntimeRlsAndCompositeReferencesRejectForeignTenant() {
    init(A, PAID, "a").block(WAIT);
    init(B, PAID, "b").block(WAIT);
    UUID pb = proof(B, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(B, pb, "grant-b").block(WAIT);
    var foreign =
        transactions.execute(
            client ->
                client
                    .sql("SELECT count(*) n FROM taska.subscription_periods WHERE tenant_id=:t")
                    .bind("t", B)
                    .map((r, m) -> r.get("n", Long.class))
                    .one());
    assertEquals(0L, asWorker(A, "fixture", "billing-service", foreign).block(WAIT));
    var cross =
        transactions.execute(
            client ->
                client
                    .sql("UPDATE taska.subscriptions SET current_period_id=:p WHERE tenant_id=:t")
                    .bind("p", confirmed.get(pb).activationOperationId())
                    .bind("t", A)
                    .fetch()
                    .rowsUpdated());
    assertThrows(
        RuntimeException.class, () -> asWorker(A, "fixture", "billing-service", cross).block(WAIT));
    assertEquals(null, header(A).get("current_period_id"));
  }

  @Test
  void periodTimesTermsAndTerminalStatusCannotBeRewritten() {
    init(A, PAID, "init").block(WAIT);
    UUID p = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, p, "grant").block(WAIT);
    for (String mutation :
        List.of(
            "UPDATE taska.subscription_periods SET ends_at=ends_at+interval '1 day'",
            "UPDATE taska.subscription_periods SET terms_snapshot='{}'::jsonb",
            "DELETE FROM taska.subscription_periods")) {
      var work = transactions.execute(client -> client.sql(mutation).fetch().rowsUpdated());
      assertThrows(
          RuntimeException.class,
          () -> asWorker(A, "fixture", "billing-service", work).block(WAIT));
    }
    revoke(A, reverse(confirmed.get(p)), "loss").block(WAIT);
    var revive =
        transactions.execute(
            client ->
                client
                    .sql("UPDATE taska.subscription_periods SET status='GRANTED'")
                    .fetch()
                    .rowsUpdated());
    assertThrows(
        RuntimeException.class,
        () -> asWorker(A, "fixture", "billing-service", revive).block(WAIT));
    assertFalse(get(OWNER_A, A).hasPaidThrough());
  }

  @Test
  void internalCommandsRequireExactVerifiedServicePurposeAndTenant() {
    rejects(
        DomainStatus.UNAUTHENTICATED,
        "AUTHENTICATION_REQUIRED",
        store.initialize(A, PAID, "missing"));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "TENANT_MISMATCH",
        asWorker(
            A, "initializeSubscription", "tenant-service", store.initialize(B, PAID, "foreign")));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        asWorker(
            A, "initializeSubscription", "api-gateway", store.initialize(A, PAID, "wrong-issuer")));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            human(OWNER_A, A, "initializeSubscription").toBuilder().addScopes("write").build(),
            store.initialize(A, PAID, "user")));
    assertEquals(0, count("subscriptions"));
  }

  @Test
  void invalidPeriodAndFreePlanEvidenceCannotCreateCoverage() {
    init(A, PAID, "init").block(WAIT);
    UUID key = proof(A, NOW, NOW);
    rejects(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED", grant(A, key, "invalid-time"));
    UUID free = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    var e = confirmed.get(free);
    confirmed.put(
        free,
        new CoverageEvidence(
            A,
            e.invoiceId(),
            e.financialFactId(),
            FREE,
            e.activationOperationId(),
            e.startsAt(),
            e.endsAt(),
            e.anchorDay(),
            e.anchorTimeUtc(),
            e.confirmedAt(),
            e.source(),
            List.of()));
    rejects(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE", grant(A, free, "free-evidence"));
    assertEquals(0, count("subscription_periods"));
    assertEquals("1", header(A).get("version").toString());
  }

  @Test
  void periodCursorBindsSessionTenantAndCurrentFinancialPermission() {
    init(A, PAID, "a").block(WAIT);
    init(B, PAID, "b").block(WAIT);
    grant(A, proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z")), "one").block(WAIT);
    grant(
            A,
            proof(A, Instant.parse("2026-02-28T12:34:56Z"), Instant.parse("2026-03-31T12:34:56Z")),
            "two")
        .block(WAIT);
    String token = periods(OWNER_A, A, 1, null).getNextCursor();
    var req =
        ListSubscriptionPeriodsRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .setPage(PageRequest.newBuilder().setLimit(1).setCursor(token))
            .build();
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED",
        RegistryTestContext.withClaims(
            human(OWNER_A, A, "listSubscriptionPeriods").toBuilder()
                .setSessionId(UUID.randomUUID().toString())
                .build(),
            store.periods(req)));
    assertThrows(DomainException.class, () -> periods(OWNER_B, B, 1, token));
    active.remove(OWNER_A.toString());
    rejects(
        DomainStatus.PERMISSION_DENIED,
        "OWNER_REQUIRED",
        RegistryTestContext.withClaims(
            human(OWNER_A, A, "listSubscriptionPeriods"), store.periods(req)));
  }

  @Test
  void realMtlsReadRpcsRecheckOwnerAndPrimaryCoverage(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path certs) throws Exception {
    init(A, PAID, "init").block(WAIT);
    UUID payment = proof(A, NOW, Instant.parse("2026-02-28T12:34:56Z"));
    grant(A, payment, "grant").block(WAIT);
    BillingCatalogIntegrationTest.openssl(
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
      BillingCatalogIntegrationTest.openssl(
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
      BillingCatalogIntegrationTest.openssl(
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
    var consumed = ConcurrentHashMap.<String>newKeySet();
    ru.taska.security.tenant.RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + ":" + jti));
    var online = new AtomicBoolean(true);
    ru.taska.security.tenant.RpcContextSecurity.Authority authority =
        (ctx, rpc, policy) ->
            Mono.just(online.get() && ctx.getContext().getIssuerService().equals("api-gateway"));
    var sender =
        new ru.taska.security.tenant.RpcContextSecurity(
            "api-gateway", Clock.systemUTC(), keys, replay, authority);
    var receiver =
        new ru.taska.security.tenant.RpcContextSecurity(
            "billing-service", Clock.systemUTC(), keys, replay, authority);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("security", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(
                beans.getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class), "test");
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
                io.grpc.ServerInterceptors.intercept(
                    new BillingCatalogRpc(
                        org.mockito.Mockito.mock(BillingCatalog.class),
                        store,
                        org.mockito.Mockito.mock(InvoiceStore.class)),
                    interceptor))
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
      var req =
          GetSubscriptionRpcRequest.newBuilder()
              .setTenantId(A.toString())
              .setContext(wire(OWNER_A, A, "getSubscription"))
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.getSubscription(req))
              .getStatus()
              .getCode());
      String rpc =
          ru.taska.api.b2b.v1.BillingServiceGrpc.getGetSubscriptionMethod().getFullMethodName();
      var meta =
          BillingCatalogIntegrationTest.signed(
              sender,
              req,
              new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
              rpc);
      var stub =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(meta));
      assertTrue(stub.getSubscription(req).hasPaidThrough());
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> stub.getSubscription(req.toBuilder().setTenantId(B.toString()).build()))
              .getStatus()
              .getCode());
      var history =
          ListSubscriptionPeriodsRpcRequest.newBuilder()
              .setTenantId(A.toString())
              .setContext(wire(OWNER_A, A, "listSubscriptionPeriods"))
              .build();
      rpc =
          ru.taska.api.b2b.v1.BillingServiceGrpc.getListSubscriptionPeriodsMethod()
              .getFullMethodName();
      assertEquals(
          1,
          plain
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      BillingCatalogIntegrationTest.signed(
                          sender,
                          history,
                          new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false),
                          rpc)))
              .listSubscriptionPeriods(history)
              .getItemsCount());
      revoke(A, reverse(confirmed.get(payment)), "loss").block(WAIT);
      assertFalse(stub.getSubscription(req).hasPaidThrough());
      active.remove(OWNER_A.toString());
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.getSubscription(req))
              .getStatus()
              .getCode());
      active.add(OWNER_A.toString());
      online.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.getSubscription(req))
              .getStatus()
              .getCode());
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  private ru.taska.api.b2b.v1.RequestContext wire(UUID actor, UUID tenant, String action) {
    Instant now = Instant.now();
    return ru.taska.api.b2b.v1.RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            human(actor, tenant, action).toBuilder()
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }
}
