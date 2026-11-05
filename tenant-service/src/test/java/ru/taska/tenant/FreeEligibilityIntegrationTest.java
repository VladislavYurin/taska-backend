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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.api.b2b.v1.AdmitFreeTenantRequest;
import ru.taska.api.b2b.v1.CreateTenantResponse;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.FreeEligibilityState;
import ru.taska.api.b2b.v1.Money;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.tenant.FreeEligibilityDependencies.ConfirmedEmail;
import ru.taska.tenant.FreeEligibilityDependencies.Disposition;
import ru.taska.tenant.FreeEligibilityDependencies.DispositionAction;
import ru.taska.tenant.FreeEligibilityDependencies.KeySet;
import ru.taska.tenant.FreeEligibilityDependencies.MarkerKey;
import ru.taska.tenant.FreeEligibilityDependencies.PurposeAssessment;

@Testcontainers
@Timeout(90)
class FreeEligibilityIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final Duration WAIT = Duration.ofSeconds(25);
  static final UUID ACTOR = UUID.randomUUID(), SESSION = UUID.randomUUID();
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static ConnectionPool pool;
  static DatabaseClient operator, db;
  static PlatformFlags service;
  static MerchantConfigurations merchant;

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
    sql("REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_tenant_app PASSWORD '" + APP_PASSWORD + "'");
    sql("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY_PASSWORD + "'");
    var lb =
        new TenantConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY_PASSWORD);
    // Upgrade the real preceding schema, preserving old changeset checksums.
    var json = tools.jackson.databind.json.JsonMapper.builder().build();
    var log =
        json.readTree(
            FreeEligibilityIntegrationTest.class.getResourceAsStream(
                "/db/changelog/db.changelog-master.yaml"));
    var changes = (tools.jackson.databind.node.ArrayNode) log.get("databaseChangeLog");
    while (changes.size() > 4) changes.remove(changes.size() - 1);
    for (var entry : changes)
      for (var change : entry.get("changeSet").get("changes")) {
        if (change.has("sqlFile") && change.get("sqlFile").has("relativeToChangelogFile")) {
          var f = (tools.jackson.databind.node.ObjectNode) change.get("sqlFile");
          f.put("relativeToChangelogFile", false);
          f.put("path", "db/changelog/" + f.get("path").asString());
        }
      }
    Path oldLog = Files.createTempFile("taska-flags-upgrade-", ".json");
    try {
      Files.writeString(oldLog, json.writeValueAsString(log));
      lb.setChangeLog(oldLog.toUri().toString());
      lb.afterPropertiesSet();
    } finally {
      Files.deleteIfExists(oldLog);
    }
    // The temporary changelog filename differs; pin the existing identities before applying current
    // log.
    sql(
        "UPDATE tenant_liquibase.databasechangelog SET"
            + " filename='db/changelog/db.changelog-master.yaml'");
    var checksums =
        operator
            .sql("SELECT id,md5sum FROM tenant_liquibase.databasechangelog ORDER BY id")
            .fetch()
            .all()
            .collectList()
            .block(WAIT);
    lb.setChangeLog("classpath:/db/changelog/db.changelog-master.yaml");
    lb.afterPropertiesSet();
    lb.afterPropertiesSet();
    var priorIds =
        checksums.stream().map(r -> r.get("id")).collect(java.util.stream.Collectors.toSet());
    assertEquals(
        checksums,
        operator
            .sql("SELECT id,md5sum FROM tenant_liquibase.databasechangelog ORDER BY id")
            .fetch()
            .all()
            .filter(r -> priorIds.contains(r.get("id")))
            .collectList()
            .block(WAIT));
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP_PASSWORD))
                .maxSize(4)
                .initialSize(1)
                .build());
    db = DatabaseClient.create(pool);
    service =
        new PlatformFlags(
            db,
            TransactionalOperator.create(new R2dbcTransactionManager(pool)),
            new PlatformFlagCursor(
                Base64.getEncoder().encodeToString(new byte[32]), Clock.systemUTC()));
    merchant =
        new MerchantConfigurations(
            db, TransactionalOperator.create(new R2dbcTransactionManager(pool)), service);
  }

  static void sql(String sql) {
    operator.sql(sql).fetch().rowsUpdated().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      C = UUID.randomUUID(),
      FREE = UUID.randomUUID(),
      PAID = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  Map<UUID, ConfirmedEmail> emails;
  Map<UUID, Disposition> cases;
  Map<Integer, byte[]> secretKeys;
  AtomicReference<Set<Integer>> matchingKeys;
  AtomicInteger activeKey, hmacCalls;
  AtomicReference<Instant> time;
  AtomicReference<PurposeAssessment> assessment;
  FreeEligibilityLedger ledger;
  TenantRegistryService registry;
  TenantTransactionExecutor transactions;
  List<PlanVersion> published;

  @BeforeEach
  void reset() {
    sql(
        "TRUNCATE"
            + " taska.free_eligibility_ledger,taska.free_eligibility_dispositions,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events,taska.global_outbox_events");
    emails = new ConcurrentHashMap<>();
    cases = new ConcurrentHashMap<>();
    secretKeys = new ConcurrentHashMap<>();
    for (UUID actor : List.of(A, B, C))
      emails.put(actor, new ConfirmedEmail(actor, actor + "@example.test", "AUTH_EMAIL_V1"));
    byte[] key1 = new byte[32], key2 = new byte[32];
    new java.security.SecureRandom().nextBytes(key1);
    new java.security.SecureRandom().nextBytes(key2);
    secretKeys.put(1, key1);
    secretKeys.put(2, key2);
    matchingKeys = new AtomicReference<>(Set.of(1));
    activeKey = new AtomicInteger(1);
    hmacCalls = new AtomicInteger();
    time = new AtomicReference<>(NOW);
    assessment =
        new AtomicReference<>(
            new PurposeAssessment("ES_V1", NOW.minusSeconds(86400), NOW.plusSeconds(86400L * 300)));
    transactions = new TenantTransactionExecutor(pool);
    published =
        List.of(
            PlanVersion.newBuilder()
                .setId(FREE.toString())
                .setCode("FREE")
                .setKind(PlanVersion.KindValue.KIND_FREE)
                .setState(PlanVersion.StateValue.STATE_PUBLISHED)
                .setPrice(
                    Money.newBuilder()
                        .setAmountMinor("0")
                        .setCurrency(Money.CurrencyValue.CURRENCY_EUR)
                        .setExponent(2))
                .build(),
            PlanVersion.newBuilder()
                .setId(PAID.toString())
                .setCode("PAID")
                .setKind(PlanVersion.KindValue.KIND_PAID)
                .setState(PlanVersion.StateValue.STATE_PUBLISHED)
                .setPrice(
                    Money.newBuilder()
                        .setAmountMinor("2000")
                        .setCurrency(Money.CurrencyValue.CURRENCY_EUR)
                        .setExponent(2))
                .build());
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            return time.get();
          }
        };
    ledger =
        new FreeEligibilityLedger(
            db,
            TransactionalOperator.create(new R2dbcTransactionManager(pool)),
            transactions,
            actor -> Mono.defer(() -> Mono.justOrEmpty(emails.get(actor))),
            new FreeEligibilityDependencies.HmacAuthority() {
              public Mono<KeySet> keys() {
                return Mono.just(new KeySet(activeKey.get(), matchingKeys.get()));
              }

              public Mono<byte[]> sign(int version, byte[] message) {
                return Mono.fromCallable(
                    () -> {
                      hmacCalls.incrementAndGet();
                      var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                      mac.init(
                          new javax.crypto.spec.SecretKeySpec(
                              secretKeys.get(version), "HmacSHA256"));
                      return mac.doFinal(message);
                    });
              }
            },
            () -> Mono.justOrEmpty(assessment.get()),
            (caller, id) -> Mono.justOrEmpty(cases.get(id)),
            () -> Mono.just(published),
            clock);
    registry =
        new TenantRegistryService(
            transactions,
            id -> Mono.just(new RegistryDependencies.PublishedPlan(id, id.equals(FREE))),
            ledger,
            clock,
            Duration.ofDays(30));
  }

  static TrustedTenantContext web(UUID actor, String action) {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(actor.toString())
        .setActorGeneration("1")
        .setSessionId(SESSION.toString())
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
        .setAction(action)
        .addScopes("read")
        .addScopes("write")
        .build();
  }

  static <T> Mono<T> account(UUID actor, String action, Mono<T> work) {
    return RegistryTestContext.withClaims(web(actor, action), work);
  }

  static <T> Mono<T> backend(String issuer, String action, UUID tenant, Mono<T> work) {
    var c =
        TrustedTenantContext.newBuilder()
            .setActorGeneration("service-v1")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setIssuerService(issuer)
            .setAction(action)
            .addScopes("write")
            .addScopes(action.equals("admitFreeTenant") ? "free:admit" : "free:retention");
    if (tenant != null) c.setTenantId(tenant.toString());
    return RegistryTestContext.withClaims(c.build(), work);
  }

  Mono<CreateTenantResponse> create(UUID actor, UUID plan, String key) {
    return account(
        actor,
        "createTenant",
        registry.create(TenantRegistryIntegrationTest.request(key, plan, "FREE fixture")));
  }

  UUID free(UUID actor) {
    return UUID.fromString(
        create(actor, FREE, UUID.randomUUID().toString()).block(WAIT).getTenant().getId());
  }

  static long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  static DomainException failure(Mono<?> work) {
    return assertThrows(DomainException.class, () -> work.block(WAIT));
  }

  static void reason(String code, Mono<?> work) {
    assertEquals(code, failure(work).getMessage());
  }

  static void active(UUID tenant) {
    sql("UPDATE taska.tenants SET lifecycle='ACTIVE' WHERE id='" + tenant + "'");
  }

  static void transfer(UUID tenant, UUID owner) {
    sql(
        "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
            + tenant
            + "','"
            + owner
            + "','TENANT_ADMIN','ACTIVE') ON CONFLICT DO NOTHING");
    sql(
        "UPDATE taska.tenants SET owner_id='"
            + owner
            + "',owner_version=owner_version+1 WHERE id='"
            + tenant
            + "'");
  }

  Mono<?> admit(UUID tenant, long version, String op) {
    return backend(
        "billing-service",
        "admitFreeTenant",
        tenant,
        ledger.admit(
            AdmitFreeTenantRequest.newBuilder()
                .setTenantId(tenant.toString())
                .setExpectedOwnerVersion(Long.toString(version))
                .setOperationId(op)
                .build()));
  }

  List<Map<String, Object>> markers() {
    return operator
        .sql(
            "SELECT *,encode(email_digest,'hex') AS digest_hex FROM taska.free_eligibility_ledger"
                + " ORDER BY consumed_at,digest_key_version")
        .fetch()
        .all()
        .collectList()
        .block(WAIT);
  }

  List<MarkerKey> markerKeys() {
    return operator
        .sql("SELECT digest_key_version,email_digest FROM taska.free_eligibility_ledger")
        .map(
            (r, m) ->
                new MarkerKey(
                    r.get("digest_key_version", Integer.class),
                    r.get("email_digest", byte[].class)))
        .all()
        .collectList()
        .block(WAIT);
  }

  Disposition decision(
      DispositionAction action,
      UUID subject,
      List<ConfirmedEmail> identities,
      List<MarkerKey> keys,
      Instant due) {
    var d =
        new Disposition(
            UUID.randomUUID(), "decision-v1", action, subject, identities, keys, "ES_V1", due);
    cases.put(d.caseId(), d);
    return d;
  }

  Mono<?> apply(Disposition d) {
    return backend(
        d.action() == DispositionAction.MINIMIZE_ACCOUNT ? "auth-service" : "tenant-service",
        "applyFreeEligibilityDisposition",
        null,
        ledger.disposition(d.caseId().toString()));
  }

  @Test
  void twoConcurrentFreeCreationsAdmitOneAndReplayDoesNotSpendTwice() {
    String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
    var results =
        Mono.zip(
                create(A, FREE, first).materialize().subscribeOn(Schedulers.parallel()),
                create(A, FREE, second).materialize().subscribeOn(Schedulers.parallel()))
            .block(WAIT);
    var values = List.of(results.getT1(), results.getT2());
    assertEquals(1, values.stream().filter(v -> v.isOnNext()).count());
    assertEquals(
        "FREE_ELIGIBILITY_USED",
        ((DomainException)
                values.stream().filter(v -> v.isOnError()).findFirst().orElseThrow().getThrowable())
            .getMessage());
    assertEquals(1, count("tenants"));
    assertEquals(1, count("tenant_memberships"));
    assertEquals(1, count("free_eligibility_ledger"));
    assertEquals(1, count("outbox_events"));
    String winner = results.getT1().isOnNext() ? first : second;
    assertEquals(
        values.stream().filter(v -> v.isOnNext()).findFirst().orElseThrow().get(),
        create(A, FREE, winner).block(WAIT));
    assertEquals(1, count("free_eligibility_ledger"));
    var conflict = TenantRegistryIntegrationTest.request(winner, FREE, "Another name");
    reason("IDEMPOTENCY_CONFLICT", account(A, "createTenant", registry.create(conflict)));
    emails.clear();
    secretKeys.clear();
    create(A, FREE, winner).block(WAIT); // Durable replay does not reconstruct identity/KMS.
    assertEquals(1, count("tenants"));
  }

  @Test
  void paidAndForeignMembershipsDoNotSpendOrBlockTheOwnersFreeOpportunity() {
    UUID a = free(A);
    sql(
        "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
            + a
            + "','"
            + B
            + "','MEMBER','ACTIVE')");
    free(B);
    create(A, PAID, UUID.randomUUID().toString()).block(WAIT);
    create(A, PAID, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(4, count("tenants"));
    assertEquals(2, count("free_eligibility_ledger"));
    emails.clear();
    create(C, PAID, UUID.randomUUID().toString()).block(WAIT);
    reason("POLICY_NOT_CONFIGURED", create(C, FREE, UUID.randomUUID().toString()));
    assertEquals(5, count("tenants"));
  }

  @Test
  void sameCompanyReturnsToFreeAndTransferUsesCurrentOwnersOwnOpportunity() {
    UUID a = free(A);
    active(a);
    admit(a, 1, UUID.randomUUID().toString()).block(WAIT);
    admit(a, 1, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(1, count("free_eligibility_ledger"));
    UUID b = free(B);
    active(b);
    transfer(a, B);
    reason("FREE_ELIGIBILITY_USED", admit(a, 2, UUID.randomUUID().toString()));
    reason("FREE_ELIGIBILITY_USED", create(A, FREE, UUID.randomUUID().toString()));
    transfer(a, C);
    admit(a, 3, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(3, count("free_eligibility_ledger"));
    assertEquals(
        2L,
        operator
            .sql(
                "SELECT count(*) n FROM taska.free_eligibility_ledger WHERE"
                    + " bound_tenant_id=:tenant")
            .bind("tenant", a)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    reason("VERSION_CONFLICT", admit(a, 2, UUID.randomUUID().toString()));
    assertEquals(2, count("tenants"));
  }

  @Test
  void failedOutboxRollsBackCompanyMembershipClaimAndReceipt() {
    sql(
        "ALTER TABLE taska.outbox_events ADD CONSTRAINT free_test_failure"
            + " CHECK(event_type<>'TenantCreationRequested')");
    try {
      assertThrows(Exception.class, () -> free(A));
    } finally {
      sql("ALTER TABLE taska.outbox_events DROP CONSTRAINT free_test_failure");
    }
    assertEquals(0, count("tenants"));
    assertEquals(0, count("free_eligibility_ledger"));
    assertEquals(0, count("idempotency_records"));
    assertEquals(0, count("tenant_memberships"));
    free(A);
    assertEquals(1, count("free_eligibility_ledger"));
  }

  @Test
  void eligibilityOptionsAreAdvisoryAndNeverIdentifyTheConsumedCompanyOrEmail() {
    var before = account(A, "getTenantCreationOptions", ledger.options()).block(WAIT);
    assertEquals(FreeEligibilityState.FREE_ELIGIBILITY_AVAILABLE, before.getFreeEligibility());
    assertEquals(2, before.getPlansCount());
    UUID tenant = free(A);
    var due = markers().getFirst().get("review_due_at");
    time.set(NOW.plusSeconds(3600));
    var after = account(A, "getTenantCreationOptions", ledger.options()).block(WAIT);
    assertEquals(FreeEligibilityState.FREE_ELIGIBILITY_USED, after.getFreeEligibility());
    assertFalse(after.toString().contains(tenant.toString()));
    assertFalse(after.toString().contains(emails.get(A).canonicalEmail()));
    assertEquals(due, markers().getFirst().get("review_due_at"));
    reason(
        "SCOPE_DENIED",
        RegistryTestContext.withClaims(
            web(A, "getTenantCreationOptions").toBuilder()
                .setTenantId(tenant.toString())
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .build(),
            ledger.options()));
  }

  @Test
  void emailChangeAndTenantDeletionLeaveOnlyMinimalMarkersAndPreventReregistration() {
    UUID tenant = free(A);
    var initial = markers().getFirst();
    emails.put(A, new ConfirmedEmail(A, "changed@example.test", "AUTH_EMAIL_V1"));
    sql("UPDATE taska.tenants SET lifecycle='DELETING' WHERE id='" + tenant + "'");
    assertThrows(
        Exception.class,
        () -> sql("UPDATE taska.tenants SET lifecycle='DELETED' WHERE id='" + tenant + "'"));
    var missing = decision(DispositionAction.MINIMIZE_TENANT, tenant, List.of(), List.of(), null);
    reason("VERSION_CONFLICT", apply(missing));
    assertEquals(1, count("free_eligibility_ledger"));
    var d =
        decision(
            DispositionAction.MINIMIZE_TENANT, tenant, List.of(emails.get(A)), List.of(), null);
    apply(d).block(WAIT);
    apply(d).block(WAIT);
    assertEquals(2, count("free_eligibility_ledger"));
    for (var row : markers()) {
      assertEquals(null, row.get("first_user_id"));
      assertEquals(null, row.get("bound_tenant_id"));
      assertEquals(null, row.get("operation_id"));
      assertEquals(initial.get("consumed_at"), row.get("consumed_at"));
      assertEquals(initial.get("review_due_at"), row.get("review_due_at"));
    }
    sql("UPDATE taska.tenants SET lifecycle='DELETED' WHERE id='" + tenant + "'");
    reason("FREE_ELIGIBILITY_USED", create(A, FREE, UUID.randomUUID().toString()));
    emails.put(B, new ConfirmedEmail(B, "changed@example.test", "AUTH_EMAIL_V1"));
    reason("FREE_ELIGIBILITY_USED", create(B, FREE, UUID.randomUUID().toString()));
    create(B, PAID, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(2, count("tenants"));
  }

  @Test
  void accountMinimizationKeepsLiveBindingAndDoesNotReconstructOnReplay() {
    UUID tenant = free(A);
    active(tenant);
    transfer(tenant, B);
    emails.put(A, new ConfirmedEmail(A, "latest@example.test", "AUTH_EMAIL_V1"));
    var d =
        decision(DispositionAction.MINIMIZE_ACCOUNT, A, List.of(emails.get(A)), List.of(), null);
    apply(d).block(WAIT);
    emails.remove(A);
    int signed = hmacCalls.get();
    apply(d).block(WAIT);
    assertEquals(signed, hmacCalls.get());
    for (var row : markers()) {
      assertEquals(null, row.get("first_user_id"));
      assertEquals(tenant, row.get("bound_tenant_id"));
    }
    admit(tenant, 2, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(3, count("free_eligibility_ledger"));
    emails.put(C, new ConfirmedEmail(C, "latest@example.test", "AUTH_EMAIL_V1"));
    reason("FREE_ELIGIBILITY_USED", create(C, FREE, UUID.randomUUID().toString()));
  }

  @Test
  void reregisteredOwnerRetainsLiveBindingAfterAnotherEmailChange() {
    UUID tenant = free(A);
    active(tenant);
    var oldEmail = emails.get(A).canonicalEmail();
    apply(decision(DispositionAction.MINIMIZE_ACCOUNT, A, List.of(emails.get(A)), List.of(), null))
        .block(WAIT);
    emails.remove(A);
    emails.put(B, new ConfirmedEmail(B, oldEmail, "AUTH_EMAIL_V1"));
    transfer(tenant, B);
    admit(tenant, 2, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(B, markers().getFirst().get("first_user_id"));
    emails.put(B, new ConfirmedEmail(B, "new-again@example.test", "AUTH_EMAIL_V1"));
    reason("FREE_ELIGIBILITY_USED", create(B, FREE, UUID.randomUUID().toString()));
    admit(tenant, 2, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(2, count("free_eligibility_ledger"));
    assertTrue(markers().stream().allMatch(r -> B.equals(r.get("first_user_id"))));
  }

  @Test
  void anotherLiveUsersEmailMarkerCannotBeAdoptedWithoutMinimization() {
    UUID tenant = free(A);
    active(tenant);
    emails.put(B, new ConfirmedEmail(B, emails.get(A).canonicalEmail(), "AUTH_EMAIL_V1"));
    transfer(tenant, B);
    assertEquals(
        FreeEligibilityState.FREE_ELIGIBILITY_USED,
        account(B, "getTenantCreationOptions", ledger.options()).block(WAIT).getFreeEligibility());
    reason("FREE_ELIGIBILITY_USED", admit(tenant, 2, UUID.randomUUID().toString()));
    reason("FREE_ELIGIBILITY_USED", create(B, FREE, UUID.randomUUID().toString()));
    assertEquals(A, markers().getFirst().get("first_user_id"));
    assertEquals(1, count("tenants"));
    assertEquals(1, count("idempotency_records"));
  }

  @Test
  void concurrentDispositionHasOneAtomicReceiptAndPreservesVerifiedAlias() {
    UUID tenant = free(A);
    emails.put(A, new ConfirmedEmail(A, "concurrent-latest@example.test", "AUTH_EMAIL_V1"));
    var d =
        decision(
            DispositionAction.MINIMIZE_TENANT, tenant, List.of(emails.get(A)), List.of(), null);
    Mono.when(
            apply(d).subscribeOn(Schedulers.boundedElastic()),
            apply(d).subscribeOn(Schedulers.boundedElastic()))
        .block(WAIT);
    assertEquals(1, count("free_eligibility_dispositions"));
    assertEquals(2, count("free_eligibility_ledger"));
    assertTrue(
        markers().stream()
            .allMatch(
                r ->
                    r.get("first_user_id") == null
                        && r.get("bound_tenant_id") == null
                        && r.get("operation_id") == null));
    emails.put(B, new ConfirmedEmail(B, "concurrent-latest@example.test", "AUTH_EMAIL_V1"));
    assertEquals(
        FreeEligibilityState.FREE_ELIGIBILITY_USED,
        account(B, "getTenantCreationOptions", ledger.options()).block(WAIT).getFreeEligibility());
    reason("FREE_ELIGIBILITY_USED", create(B, FREE, UUID.randomUUID().toString()));
  }

  @Test
  void keyRotationRetainsMatchingAndMissingOldKeyFailsClosed() {
    UUID tenant = free(A);
    active(tenant);
    activeKey.set(2);
    matchingKeys.set(Set.of(1, 2));
    emails.put(B, new ConfirmedEmail(B, emails.get(A).canonicalEmail(), "AUTH_EMAIL_V1"));
    reason("FREE_ELIGIBILITY_USED", create(B, FREE, UUID.randomUUID().toString()));
    emails.put(A, new ConfirmedEmail(A, "rotation@example.test", "AUTH_EMAIL_V1"));
    admit(tenant, 1, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(2, count("free_eligibility_ledger"));
    matchingKeys.set(Set.of(2));
    reason("POLICY_NOT_CONFIGURED", create(C, FREE, UUID.randomUUID().toString()));
    assertEquals(1, count("tenants"));
  }

  @Test
  void overdueReviewStopsFreeUntilExplicitReviewWithoutChangingUsedAt() {
    UUID tenant = free(A);
    active(tenant);
    var row = markers().getFirst();
    Instant due = ((java.time.OffsetDateTime) row.get("review_due_at")).toInstant();
    time.set(due);
    assessment.set(
        new PurposeAssessment("ES_V1", due.minusSeconds(1), due.plusSeconds(86400L * 300)));
    reason("POLICY_NOT_CONFIGURED", admit(tenant, 1, UUID.randomUUID().toString()));
    reason("POLICY_NOT_CONFIGURED", account(A, "getTenantCreationOptions", ledger.options()));
    var review =
        decision(
            DispositionAction.REVIEW, null, List.of(), markerKeys(), due.plusSeconds(86400L * 200));
    apply(review).block(WAIT);
    admit(tenant, 1, UUID.randomUUID().toString()).block(WAIT);
    assertEquals(row.get("consumed_at"), markers().getFirst().get("consumed_at"));
    var reviewed = markers().getFirst().get("review_due_at");
    account(A, "getTenantCreationOptions", ledger.options()).block(WAIT);
    assertEquals(reviewed, markers().getFirst().get("review_due_at"));
    reason("FREE_ELIGIBILITY_USED", create(A, FREE, UUID.randomUUID().toString()));
  }

  @Test
  void lawfulErasureEndsMatchingAndReplayDoesNotEraseAFreshClaim() {
    free(A);
    var erase = decision(DispositionAction.ERASE, null, List.of(), markerKeys(), null);
    apply(erase).block(WAIT);
    assertEquals(0, count("free_eligibility_ledger"));
    emails.put(B, new ConfirmedEmail(B, emails.get(A).canonicalEmail(), "AUTH_EMAIL_V1"));
    free(B);
    assertEquals(1, count("free_eligibility_ledger"));
    apply(erase).block(WAIT);
    assertEquals(1, count("free_eligibility_ledger"));
    var changed =
        new Disposition(
            erase.caseId(),
            "changed-decision",
            erase.action(),
            null,
            List.of(),
            markerKeys(),
            "ES_V1",
            null);
    cases.put(changed.caseId(), changed);
    reason("IDEMPOTENCY_CONFLICT", apply(changed));
  }

  @Test
  void ordinaryCredentialsCannotAdmitOrEraseAndSqlCannotResetHistory() {
    UUID tenant = free(A);
    active(tenant);
    var req =
        AdmitFreeTenantRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setExpectedOwnerVersion("1")
            .setOperationId(UUID.randomUUID().toString())
            .build();
    reason("SCOPE_DENIED", account(A, "admitFreeTenant", ledger.admit(req)));
    reason("SCOPE_DENIED", backend("auth-service", "admitFreeTenant", tenant, ledger.admit(req)));
    var d = decision(DispositionAction.ERASE, null, List.of(), markerKeys(), null);
    reason(
        "SCOPE_DENIED",
        account(A, "applyFreeEligibilityDisposition", ledger.disposition(d.caseId().toString())));
    assertThrows(
        Exception.class,
        () ->
            db.sql("DELETE FROM taska.free_eligibility_ledger").fetch().rowsUpdated().block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "UPDATE taska.free_eligibility_ledger SET"
                        + " bound_tenant_id=NULL,first_user_id=NULL,operation_id=NULL")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            db.sql(
                    "UPDATE taska.free_eligibility_ledger SET review_due_at=review_due_at+interval"
                        + " '1 year'")
                .fetch()
                .rowsUpdated()
                .block(WAIT));
    assertEquals(1, count("free_eligibility_ledger"));
  }

  @Test
  void dispositionFailureRollsBackBothMinimalizationAndReceipt() {
    UUID tenant = free(A);
    var d =
        decision(
            DispositionAction.MINIMIZE_TENANT, tenant, List.of(emails.get(A)), List.of(), null);
    sql(
        "ALTER TABLE taska.free_eligibility_dispositions ADD CONSTRAINT free_case_test_failure"
            + " CHECK(action<>'MINIMIZE_TENANT')");
    try {
      assertThrows(Exception.class, () -> apply(d).block(WAIT));
    } finally {
      sql("ALTER TABLE taska.free_eligibility_dispositions DROP CONSTRAINT free_case_test_failure");
    }
    assertEquals(tenant, markers().getFirst().get("bound_tenant_id"));
    assertEquals(A, markers().getFirst().get("first_user_id"));
    assertEquals(0, count("free_eligibility_dispositions"));
  }

  @Test
  void confirmedIdentityPolicyAndKmsFailuresNeverCreateAFreeCompany() {
    emails.put(A, new ConfirmedEmail(B, "another@example.test", "AUTH_EMAIL_V1"));
    reason("POLICY_NOT_CONFIGURED", create(A, FREE, UUID.randomUUID().toString()));
    emails.put(A, new ConfirmedEmail(A, "UPPER@example.test", "AUTH_EMAIL_V1"));
    reason("POLICY_NOT_CONFIGURED", create(A, FREE, UUID.randomUUID().toString()));
    emails.put(A, new ConfirmedEmail(A, "proper@example.test", "UNSUPPORTED"));
    reason("POLICY_NOT_CONFIGURED", create(A, FREE, UUID.randomUUID().toString()));
    emails.put(A, new ConfirmedEmail(A, "proper@example.test", "AUTH_EMAIL_V1"));
    secretKeys.remove(1);
    reason("DEPENDENCY_UNAVAILABLE", create(A, FREE, UUID.randomUUID().toString()));
    assertEquals(0, count("tenants"));
    assertEquals(0, count("free_eligibility_ledger"));
  }

  @Test
  void ownerVersionIsRecheckedAfterExternalPreparation() {
    UUID tenant = free(A);
    active(tenant);
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var keys =
        new FreeEligibilityDependencies.HmacAuthority() {
          public Mono<KeySet> keys() {
            return Mono.just(new KeySet(1, Set.of(1)));
          }

          public Mono<byte[]> sign(int v, byte[] message) {
            return Mono.fromCallable(
                () -> {
                  var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                  mac.init(new javax.crypto.spec.SecretKeySpec(secretKeys.get(1), "HmacSHA256"));
                  return mac.doFinal(message);
                });
          }
        };
    var raced =
        new FreeEligibilityLedger(
            db,
            TransactionalOperator.create(new R2dbcTransactionManager(pool)),
            transactions,
            actor ->
                Mono.fromCallable(
                        () -> {
                          transfer(tenant, B);
                          return emails.get(actor);
                        })
                    .subscribeOn(Schedulers.boundedElastic()),
            keys,
            () -> Mono.just(assessment.get()),
            (caller, id) -> Mono.empty(),
            () -> Mono.just(published),
            clock);
    var r =
        AdmitFreeTenantRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setExpectedOwnerVersion("1")
            .setOperationId(UUID.randomUUID().toString())
            .build();
    reason(
        "VERSION_CONFLICT", backend("billing-service", "admitFreeTenant", tenant, raced.admit(r)));
    assertEquals(1, count("free_eligibility_ledger"));
  }

  @Test
  void externalPreparationDoesNotHoldTheSingleDatabaseConnection() {
    var single =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP_PASSWORD))
                .maxSize(1)
                .initialSize(1)
                .maxAcquireTime(Duration.ofSeconds(2))
                .build());
    try {
      var probe = DatabaseClient.create(single);
      var executor = new TenantTransactionExecutor(single);
      var clock = Clock.fixed(NOW, ZoneOffset.UTC);
      var keys =
          new FreeEligibilityDependencies.HmacAuthority() {
            public Mono<KeySet> keys() {
              return probe.sql("SELECT 1").fetch().all().then(Mono.just(new KeySet(1, Set.of(1))));
            }

            public Mono<byte[]> sign(int version, byte[] message) {
              return probe
                  .sql("SELECT 1")
                  .fetch()
                  .all()
                  .then(
                      Mono.fromCallable(
                          () -> {
                            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
                            mac.init(
                                new javax.crypto.spec.SecretKeySpec(
                                    secretKeys.get(1), "HmacSHA256"));
                            return mac.doFinal(message);
                          }));
            }
          };
      var local =
          new FreeEligibilityLedger(
              probe,
              TransactionalOperator.create(new R2dbcTransactionManager(single)),
              executor,
              actor -> probe.sql("SELECT 1").fetch().all().then(Mono.just(emails.get(actor))),
              keys,
              () -> Mono.just(assessment.get()),
              (caller, id) -> Mono.empty(),
              () -> Mono.just(published),
              clock);
      var creator =
          new TenantRegistryService(
              executor,
              id -> Mono.just(new RegistryDependencies.PublishedPlan(id, true)),
              local,
              clock,
              Duration.ofDays(30));
      account(
              A,
              "createTenant",
              creator.create(
                  TenantRegistryIntegrationTest.request(
                      UUID.randomUUID().toString(), FREE, "single connection")))
          .block(WAIT);
      assertEquals(1, count("free_eligibility_ledger"));
    } finally {
      single.disposeLater().block(WAIT);
    }
  }

  @Test
  void realMtlsProtectsCreationOptionsAndBillingAdmission(@TempDir Path certs) throws Exception {
    TenantRegistryIntegrationTest.openssl(
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
        "/CN=flags-ca");
    for (String name : List.of("api-gateway", "billing-service", "tenant-service")) {
      TenantRegistryIntegrationTest.openssl(
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
      Files.writeString(
          certs.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      TenantRegistryIntegrationTest.openssl(
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
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return java.util.Set.of("api-gateway", "billing-service", "tenant-service")
                        .contains(issuer)
                    && kid.equals("test")
                ? pair.getPublic()
                : null;
          }
        };
    var allowed = new AtomicBoolean(true);
    RpcContextSecurity.Authority authority = (context, rpc, policy) -> Mono.just(allowed.get());
    var consumed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + jti));
    var recipient =
        new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, authority);
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton("security", recipient);
    var interceptor =
        new TenantConfiguration()
            .registrySecurity(beans.getBeanProvider(RpcContextSecurity.class), "test");
    var ssl =
        io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                certs.resolve("tenant-service.crt").toFile(),
                certs.resolve("tenant-service.key").toFile())
            .trustManager(certs.resolve("ca.crt").toFile())
            .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
            .build();
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new FreeEligibilityGrpcService(ledger), interceptor))
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new TenantGrpcService(
                        registry, org.mockito.Mockito.mock(TenantMembershipService.class)),
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
    var billingChannel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("billing-service.crt").toFile(),
                        certs.resolve("billing-service.key").toFile())
                    .build())
            .build();
    var tenantChannel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("tenant-service.crt").toFile(),
                        certs.resolve("tenant-service.key").toFile())
                    .build())
            .build();
    try {
      var gateway =
          new RpcContextSecurity("api-gateway", Clock.systemUTC(), keys, replay, authority);
      var reader =
          ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
      var option =
          ru.taska.api.b2b.v1.TenantCreationOptionsRequest.newBuilder()
              .setContext(wire(A, null, "getTenantCreationOptions", "api-gateway", false))
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> reader.getTenantCreationOptions(option))
              .getStatus()
              .getCode());
      var signed =
          reader.withInterceptors(
              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                  TenantRegistryIntegrationTest.signed(
                      gateway,
                      option,
                      new RpcContextSecurity.Policy(false, false),
                      ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc
                          .getGetTenantCreationOptionsMethod()
                          .getFullMethodName())));
      assertEquals(
          FreeEligibilityState.FREE_ELIGIBILITY_AVAILABLE,
          signed.getTenantCreationOptions(option).getFreeEligibility());
      var create =
          TenantRegistryIntegrationTest.request(UUID.randomUUID().toString(), FREE, "mTLS FREE")
              .toBuilder()
              .setContext(wire(A, null, "createTenant", "api-gateway", false))
              .build();
      var creator =
          ru.taska.api.b2b.v1.TenantServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(
                          gateway,
                          create,
                          new RpcContextSecurity.Policy(false, true),
                          ru.taska.api.b2b.v1.TenantServiceGrpc.getCreateTenantMethod()
                              .getFullMethodName())));
      UUID tenant = UUID.fromString(creator.createTenant(create).getTenant().getId());
      active(tenant);
      var billing =
          new RpcContextSecurity("billing-service", Clock.systemUTC(), keys, replay, authority);
      var request =
          AdmitFreeTenantRequest.newBuilder()
              .setTenantId(tenant.toString())
              .setOperationId(UUID.randomUUID().toString())
              .setExpectedOwnerVersion("1")
              .setContext(wire(null, tenant, "admitFreeTenant", "billing-service", true))
              .build();
      var bill =
          ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.newBlockingStub(billingChannel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(
                          billing,
                          request,
                          new RpcContextSecurity.Policy(true, true),
                          ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.getAdmitFreeTenantMethod()
                              .getFullMethodName())));
      assertTrue(bill.admitFreeTenant(request).getAdmitted());
      assertEquals(1, count("free_eligibility_ledger"));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> bill.admitFreeTenant(request))
              .getStatus()
              .getCode());
      var d =
          decision(DispositionAction.MINIMIZE_ACCOUNT, A, List.of(emails.get(A)), List.of(), null);
      cases.put(d.caseId(), d);
      var dispositionRequest =
          ru.taska.api.b2b.v1.ApplyFreeEligibilityDispositionRequest.newBuilder()
              .setCaseId(d.caseId().toString())
              .setContext(
                  wire(null, null, "applyFreeEligibilityDisposition", "tenant-service", true))
              .build();
      var retention =
          new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, authority);
      var cleaner =
          ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc.newBlockingStub(tenantChannel)
              .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
              .withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(
                          retention,
                          dispositionRequest,
                          new RpcContextSecurity.Policy(false, true),
                          ru.taska.api.b2b.v1.FreeEligibilityServiceGrpc
                              .getApplyFreeEligibilityDispositionMethod()
                              .getFullMethodName())));
      assertTrue(cleaner.applyFreeEligibilityDisposition(dispositionRequest).getApplied());
      assertEquals(null, markers().getFirst().get("first_user_id"));
      assertEquals(tenant, markers().getFirst().get("bound_tenant_id"));
      allowed.set(false);
      assertEquals(
          io.grpc.Status.Code.PERMISSION_DENIED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () -> signed.getTenantCreationOptions(option))
              .getStatus()
              .getCode());
    } finally {
      channel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      billingChannel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      tenantChannel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  static ru.taska.api.b2b.v1.RequestContext wire(
      UUID actor, UUID tenant, String action, String issuer, boolean service) {
    var now = Instant.now();
    var c =
        TrustedTenantContext.newBuilder()
            .setContextVersion(1)
            .setActorGeneration("1")
            .setAction(action)
            .setIssuerService(issuer)
            .setAudienceService("tenant-service")
            .setIssuedAt(now.toString())
            .setExpiresAt(now.plusSeconds(30).toString())
            .setAssertionId(UUID.randomUUID().toString())
            .addScopes("read")
            .addScopes("write");
    if (actor != null) c.setActorUserId(actor.toString());
    if (tenant != null) c.setTenantId(tenant.toString());
    if (service)
      c.setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
          .addScopes(action.equals("admitFreeTenant") ? "free:admit" : "free:retention");
    else
      c.setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
          .setSessionId(SESSION.toString());
    return ru.taska.api.b2b.v1.RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(c)
        .build();
  }
}
