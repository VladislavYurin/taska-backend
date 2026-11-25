package ru.taska.tenant;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_ADMIN_PAT;
import static ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_SERVICE;
import static ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_USER_PAT;
import static ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL;
import static ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_WEB_TENANT;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.CreateTenantRequest;
import ru.taska.api.b2b.v1.CreateTenantResponse;
import ru.taska.api.b2b.v1.CreateTenantRpcRequest;
import ru.taska.api.b2b.v1.GetTenantRpcRequest;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantServiceGrpc;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RegistryTestContext;
import ru.taska.tenant.RegistryDependencies.FreeAdmission;
import ru.taska.tenant.RegistryDependencies.PublishedPlan;
import ru.taska.tenant.RegistryDependencies.PublishedPlans;

@Testcontainers
@Timeout(90)
class TenantRegistryIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static ConnectionPool pool;
  static DatabaseClient operator, raw;
  static TenantTransactionExecutor transactions;
  static final UUID OWNER = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      PAID = UUID.randomUUID(),
      FREE = UUID.randomUUID();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  AtomicInteger admissions;
  AtomicBoolean catalog;
  AtomicReference<Instant> time;
  TenantRegistryService service;

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
            "service=tenant",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, result.getExitCode(), result.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    operator
        .sql("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    operator
        .sql("REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    for (var entry : Map.of("app", APP_PASSWORD, "deployer", DEPLOY_PASSWORD).entrySet())
      operator
          .sql("ALTER ROLE taska_tenant_" + entry.getKey() + " PASSWORD '" + entry.getValue() + "'")
          .fetch()
          .rowsUpdated()
          .block(WAIT);
    var lb =
        new TenantConfiguration()
            .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY_PASSWORD);
    lb.afterPropertiesSet();
    // Re-running the actual Liquibase log must not change history or duplicate tables.
    lb.afterPropertiesSet();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP_PASSWORD))
                .maxSize(4)
                .initialSize(1)
                .maxAcquireTime(WAIT)
                .build());
    raw = DatabaseClient.create(pool);
    transactions = new TenantTransactionExecutor(pool);
  }

  @AfterAll
  static void stop() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void reset() {
    operator
        .sql(
            "TRUNCATE"
                + " taska.quota_effects,taska.quota_reservations,taska.quota_counters,taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.free_admission_subjects,taska.free_plan_admissions,taska.tenant_restrictions,taska.tenant_restriction_audit,taska.tenants,taska.tenant_memberships,taska.idempotency_records,taska.outbox_events,taska.global_outbox_events,taska.processed_events")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    admissions = new AtomicInteger();
    catalog = new AtomicBoolean(true);
    time = new AtomicReference<>(NOW);
    service =
        registry(
            id ->
                catalog.get()
                    ? Mono.just(new PublishedPlan(id, id.equals(FREE)))
                    : Mono.error(
                        TenantRegistryService.fail(
                            DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")),
            (db, actor, tenant) -> {
              admissions.incrementAndGet();
              return Mono.empty();
            });
  }

  TenantRegistryService registry(PublishedPlans plans, FreeAdmission free) {
    return new TenantRegistryService(
        transactions,
        plans,
        free,
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
        },
        Duration.ofDays(30));
  }

  @AfterEach
  void clean() {
    var state =
        raw.sql(
                "SELECT current_user::text role,session_user::text"
                    + " login,NULLIF(current_setting('app.tenant_id',true),'')"
                    + " tenant,NULLIF(current_setting('app.actor_id',true),'') actor")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals("taska_tenant_app", state.get("role"));
    assertEquals(state.get("role"), state.get("login"));
    assertNull(state.get("tenant"));
    assertNull(state.get("actor"));
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.tenants")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  static CreateTenantRpcRequest request(String key, UUID plan, String name) {
    return CreateTenantRpcRequest.newBuilder()
        .setPayload(
            CreateTenantRequest.newBuilder().setName(name).setPlanVersionId(plan.toString()))
        .setMutation(
            MutationOptions.newBuilder()
                .setIdempotencyKey(
                    UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString()))
        .build();
  }

  Mono<CreateTenantResponse> create(UUID actor, String key, UUID plan) {
    return RegistryTestContext.as(
        actor,
        null,
        "createTenant",
        CREDENTIAL_KIND_WEB_GLOBAL,
        true,
        service.create(request(key, plan, "Компания")));
  }

  Mono<Tenant> get(UUID actor, UUID tenant) {
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor.toString())
            .setTenantId(tenant.toString())
            .setActorGeneration("1")
            .setMembershipGeneration("1")
            .setCredentialKind(CREDENTIAL_KIND_WEB_TENANT)
            .setSessionId(UUID.randomUUID().toString())
            .setIssuerService("api-gateway")
            .setAudienceService("tenant-service")
            .addScopes("read")
            .setAction("getTenant")
            .build();
    return RegistryTestContext.withClaims(
        claims,
        service.get(GetTenantRpcRequest.newBuilder().setTenantId(tenant.toString()).build()));
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  static void rejects(DomainStatus expected, Mono<?> work) {
    StepVerifier.create(work)
        .expectErrorSatisfies(
            e -> assertEquals(expected, assertInstanceOf(DomainException.class, e).getStatus()))
        .verify(WAIT);
  }

  @Test
  void paidCreateCommitsOwnerMembershipDeadlineAndSingleOutbox() {
    var result = create(OWNER, "paid", PAID).block(WAIT);
    var t = result.getTenant();
    assertEquals(OWNER.toString(), t.getOwnerId());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT, t.getLifecycle());
    assertEquals(NOW.plus(Duration.ofDays(30)), Instant.parse(t.getCreationDeadline()));
    assertEquals("1", t.getVersion());
    assertEquals("1", t.getOwnerVersion());
    assertEquals(Operation.StateValue.STATE_PENDING, result.getOperation().getState());
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PENDING_FIRST_PAYMENT), t.getRestrictionsList());
    assertEquals(1, count("tenants"));
    assertEquals(1, count("tenant_memberships"));
    assertEquals(1, count("outbox_events"));
    assertEquals(1, count("idempotency_records"));
    assertEquals(0, admissions.get());
    var member = operator.sql("SELECT * FROM taska.tenant_memberships").fetch().one().block(WAIT);
    assertEquals("TENANT_ADMIN", member.get("role"));
    assertEquals("ACTIVE", member.get("status"));
    String payload =
        operator
            .sql("SELECT payload::text p FROM taska.outbox_events")
            .map((r, m) -> r.get("p", String.class))
            .one()
            .block(WAIT);
    assertFalse(payload.contains("Компания"));
    assertTrue(payload.contains(result.getOperation().getId()));
  }

  @Test
  void parallelSameKeyReturnsOneCompanyAndOperation() {
    var responses =
        Flux.range(0, 12)
            .flatMap(i -> create(OWNER, "race", PAID).subscribeOn(Schedulers.parallel()), 4)
            .collectList()
            .block(WAIT);
    assertEquals(12, responses.size());
    assertEquals(1, responses.stream().map(x -> x.getTenant().getId()).distinct().count());
    assertEquals(1, responses.stream().map(x -> x.getOperation().getId()).distinct().count());
    assertEquals(1, count("tenants"));
    assertEquals(1, count("tenant_memberships"));
    assertEquals(1, count("outbox_events"));
    assertEquals(1, count("idempotency_records"));
  }

  @Test
  void sameKeyDifferentPayloadConflictsWithoutChangingCompany() {
    var original = create(OWNER, "conflict", PAID).block(WAIT);
    rejects(
        DomainStatus.ALREADY_EXISTS,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(request("conflict", PAID, "Другая"))));
    rejects(DomainStatus.ALREADY_EXISTS, create(OWNER, "conflict", FREE));
    assertEquals(original, create(OWNER, "conflict", PAID).block(WAIT));
    assertEquals(1, count("tenants"));
    assertEquals(0, admissions.get());
  }

  @Test
  void replaySurvivesCacheEvictionAndCatalogRetirementWithoutMovingDeadline() {
    var first = create(OWNER, "durable", PAID).block(WAIT);
    operator.sql("DELETE FROM taska.idempotency_records").fetch().rowsUpdated().block(WAIT);
    time.set(NOW.plus(Duration.ofDays(35)));
    catalog.set(false);
    var repeated = create(OWNER, "durable", PAID).block(WAIT);
    assertEquals(first, repeated);
    assertEquals(1, count("tenants"));
    assertEquals(1, count("outbox_events"));
    operator
        .sql("UPDATE taska.tenant_memberships SET status='REMOVED'")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, create(OWNER, "durable", PAID));
  }

  @Test
  void sameKeyIsScopedToOriginalActor() {
    var a = create(OWNER, "shared", PAID).block(WAIT);
    var b = create(OTHER, "shared", PAID).block(WAIT);
    assertNotEquals(a.getTenant().getId(), b.getTenant().getId());
    assertEquals(2, count("tenants"));
    rejects(DomainStatus.NOT_FOUND, get(OWNER, UUID.fromString(b.getTenant().getId())));
  }

  @Test
  void unavailableCatalogAndFreeAdmissionNeverLeavePartialRegistry() {
    catalog.set(false);
    rejects(DomainStatus.UNAVAILABLE, create(OWNER, "offline", PAID));
    assertEquals(0, count("tenants"));
    catalog.set(true);
    service =
        registry(
            id -> Mono.just(new PublishedPlan(id, true)),
            (db, a, t) ->
                Mono.error(
                    TenantRegistryService.fail(
                        DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")));
    rejects(DomainStatus.UNAVAILABLE, create(OWNER, "free-offline", FREE));
    for (String table :
        List.of("tenants", "tenant_memberships", "outbox_events", "idempotency_records"))
      assertEquals(0, count(table));
  }

  @Test
  void freeStaysProvisioningAndAdmissionIsNotRepeated() {
    var first = create(OWNER, "free", FREE).block(WAIT);
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_PROVISIONING, first.getTenant().getLifecycle());
    assertFalse(first.getTenant().hasCreationDeadline());
    assertEquals(0, first.getTenant().getRestrictionsCount());
    assertEquals(first, create(OWNER, "free", FREE).block(WAIT));
    assertEquals(1, admissions.get());
  }

  @Test
  void getChecksMembershipAndDoesNotGrantWorkAccessToPaid() {
    var created = create(OWNER, "read", PAID).block(WAIT);
    UUID id = UUID.fromString(created.getTenant().getId());
    var read = get(OWNER, id).block(WAIT);
    assertEquals(created.getTenant(), read.toBuilder().clearMembership().build());
    assertTrue(read.getMembership().getIsOwner());
    rejects(DomainStatus.NOT_FOUND, get(OTHER, id));
    operator
        .sql(
            "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES"
                + " (:t,:u,'MEMBER','ACTIVE')")
        .bind("t", id)
        .bind("u", OTHER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    assertEquals(id.toString(), get(OTHER, id).block(WAIT).getId());
    operator
        .sql("UPDATE taska.tenant_memberships SET status='SUSPENDED' WHERE user_id=:u")
        .bind("u", OTHER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, get(OTHER, id));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        RegistryTestContext.as(
            OWNER,
            UUID.randomUUID(),
            "getTenant",
            CREDENTIAL_KIND_WEB_TENANT,
            true,
            service.get(GetTenantRpcRequest.newBuilder().setTenantId(id.toString()).build())));
  }

  @Test
  void creationRequiresGlobalWebSessionNotPatOrTenantContext() {
    for (var kind :
        List.of(
            CREDENTIAL_KIND_USER_PAT,
            CREDENTIAL_KIND_ADMIN_PAT,
            CREDENTIAL_KIND_SERVICE,
            CREDENTIAL_KIND_WEB_TENANT))
      rejects(
          DomainStatus.PERMISSION_DENIED,
          RegistryTestContext.as(
              OWNER,
              null,
              "createTenant",
              kind,
              true,
              service.create(request("forged", PAID, "Company"))));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            false,
            service.create(request("forged", PAID, "Company"))));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        RegistryTestContext.as(
            OWNER,
            UUID.randomUUID(),
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(request("forged", PAID, "Company"))));
    assertEquals(0, count("tenants"));
  }

  @Test
  void malformedOrUnpublishedPlanDoesNotWrite() {
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(request("bad", PAID, " "))));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(
                request("bad-key", PAID, "Company").toBuilder()
                    .setMutation(MutationOptions.newBuilder().setIdempotencyKey("not-a-uuid"))
                    .build())));
    service = registry(id -> Mono.empty(), (db, a, t) -> Mono.empty());
    rejects(DomainStatus.INVALID_ARGUMENT, create(OWNER, "unknown-plan", PAID));
    assertEquals(0, count("tenants"));
  }

  @Test
  void deferredOwnerFkAndImmutableFactsAreEnforcedByDatabase() {
    var created = create(OWNER, "immutable", PAID).block(WAIT);
    StepVerifier.create(
            operator
                .sql(
                    "UPDATE taska.tenants SET creation_deadline_at=creation_deadline_at+interval '1"
                        + " day'")
                .fetch()
                .rowsUpdated())
        .expectError()
        .verify(WAIT);
    StepVerifier.create(operator.sql("DELETE FROM taska.tenant_memberships").fetch().rowsUpdated())
        .expectError()
        .verify(WAIT);
    assertEquals(
        created.getTenant(),
        get(OWNER, UUID.fromString(created.getTenant().getId())).block(WAIT).toBuilder()
            .clearMembership()
            .build());
    assertEquals(1, count("tenant_memberships"));
  }

  @Test
  void admissionFailureAndCancellationRollbackTenantRow() throws Exception {
    var entered = new CountDownLatch(1);
    service =
        registry(
            id -> Mono.just(new PublishedPlan(id, true)),
            (db, a, t) -> {
              entered.countDown();
              return Mono.never();
            });
    var subscription = create(OWNER, "cancel", FREE).subscribe();
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
    } finally {
      subscription.dispose();
    }
    // A new request for the exact same deterministic ID waits for rollback and can then create it.
    service = registry(id -> Mono.just(new PublishedPlan(id, true)), (db, a, t) -> Mono.empty());
    var done = create(OWNER, "cancel", FREE).block(WAIT);
    assertNotNull(done);
    assertEquals(1, count("tenants"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void grpcWithoutVerifiedInterceptorContextNeverReachesDatabase() {
    var adapter =
        new TenantGrpcService(
            service,
            org.mockito.Mockito.mock(TenantMembershipService.class),
            org.mockito.Mockito.mock(SeatQuotaStore.class),
            org.mockito.Mockito.mock(TenantMembershipMutationService.class));

    StepVerifier.create(adapter.createTenant(Mono.just(request("untrusted", PAID, "Company"))))
        .expectErrorSatisfies(
            e ->
                assertEquals(
                    io.grpc.Status.Code.UNAUTHENTICATED, io.grpc.Status.fromThrowable(e).getCode()))
        .verify(WAIT);
    assertEquals(0, count("tenants"));
  }

  @Test
  void serviceStartsOnMigratedCleanDatabaseAndReportsHealth() throws Exception {
    String database = "tenant_boot_probe";
    operator.sql("CREATE DATABASE " + database).fetch().rowsUpdated().block(WAIT);
    var boot =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            database,
            "-v",
            "service=tenant",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql",
            "-c",
            "CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator; REVOKE ALL ON"
                + " SCHEMA tenant_liquibase FROM PUBLIC");
    assertEquals(0, boot.getExitCode(), boot.getStderr());
    String base = pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
    String jdbc = "jdbc:postgresql://" + base;
    var properties =
        Map.of(
            "spring.r2dbc.url",
            "r2dbc:postgresql://" + base,
            "spring.r2dbc.username",
            "taska_tenant_app",
            "spring.r2dbc.password",
            APP_PASSWORD,
            "spring.liquibase.url",
            jdbc,
            "spring.liquibase.user",
            "taska_tenant_deployer",
            "spring.liquibase.password",
            DEPLOY_PASSWORD,
            "server.port",
            "0",
            "spring.grpc.server.port",
            "0",
            "logging.level.root",
            "WARN");
    try (var context =
        new org.springframework.boot.builder.SpringApplicationBuilder(
                TenantServiceApplication.class)
            // storage-lib is present only for the cross-service quota fixture. Tenant runtime
            // has no S3 dependency; reproduce that runtime rather than activating fixture imports.
            .properties(
                "spring.autoconfigure.exclude=ru.taska.storage.config.StorageAutoConfiguration")
            .initializers(
                c ->
                    org.springframework.boot.test.util.TestPropertyValues.of(properties).applyTo(c))
            .run()) {
      int port =
          ((org.springframework.boot.web.server.context.WebServerApplicationContext) context)
              .getWebServer()
              .getPort();
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
      try (var connection =
              java.sql.DriverManager.getConnection(jdbc, pg.getUsername(), pg.getPassword());
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT (SELECT count(*) FROM taska.tenants)+(SELECT count(*) FROM"
                      + " taska.tenant_memberships)")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getLong(1));
      }
    } finally {
      operator.sql("DROP DATABASE " + database + " WITH (FORCE)").fetch().rowsUpdated().block(WAIT);
    }
  }

  @Test
  void missingTrustProviderClosesGrpcBeforeHandler() {
    var provider =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory()
            .getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class);
    var interceptor = new TenantConfiguration().registrySecurity(provider, "test");
    @SuppressWarnings("unchecked")
    var call =
        (io.grpc.ServerCall<Object, Object>) org.mockito.Mockito.mock(io.grpc.ServerCall.class);
    @SuppressWarnings("unchecked")
    var next =
        (io.grpc.ServerCallHandler<Object, Object>)
            org.mockito.Mockito.mock(io.grpc.ServerCallHandler.class);
    interceptor.interceptCall(call, new io.grpc.Metadata(), next);
    org.mockito.Mockito.verify(call)
        .close(
            org.mockito.ArgumentMatchers.argThat(
                status -> status.getCode() == io.grpc.Status.Code.UNAVAILABLE),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verifyNoInteractions(next);
  }

  @Test
  void unsafeRuntimeRoleAndMembershipFailStartupGuard() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () ->
            new TenantConfiguration()
                .safeRuntimeRole(factory(pg.getUsername(), pg.getPassword()))
                .afterPropertiesSet());
    operator.sql("GRANT taska_tenant_job TO taska_tenant_app").fetch().rowsUpdated().block(WAIT);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> new TenantConfiguration().safeRuntimeRole(pool).afterPropertiesSet());
    } finally {
      operator
          .sql("REVOKE taska_tenant_job FROM taska_tenant_app")
          .fetch()
          .rowsUpdated()
          .block(WAIT);
    }
    new TenantConfiguration().safeRuntimeRole(pool).afterPropertiesSet();
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

  static RequestContext wireContext(UUID actor, UUID tenant, String action) {
    Instant now = Instant.now();
    var c =
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor.toString())
            .setCredentialKind(
                tenant == null ? CREDENTIAL_KIND_WEB_GLOBAL : CREDENTIAL_KIND_WEB_TENANT)
            .setSessionId(UUID.randomUUID().toString())
            .setAction(action)
            .setActorGeneration("1")
            .setIssuerService("gateway")
            .setAudienceService("tenant-service")
            .setIssuedAt(now.toString())
            .setExpiresAt(now.plusSeconds(30).toString())
            .setAssertionId(UUID.randomUUID().toString())
            .setContextVersion(1)
            .addScopes("read")
            .addScopes("write");
    if (tenant != null) c.setTenantId(tenant.toString()).setMembershipGeneration("1");
    return RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(c)
        .build();
  }

  @Test
  void realMtlsSignedGrpcCreateAndGetEnforceReplayPayloadAndMembership(
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
    for (String name : List.of("gateway", "tenant-service")) {
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
                "test", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return issuer.equals("gateway") && kid.equals("test") ? pair.getPublic() : null;
          }
        };
    var consumed = ConcurrentHashMap.<String>newKeySet();
    ru.taska.security.tenant.RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(consumed.add(issuer + ":" + jti));
    ru.taska.security.tenant.RpcContextSecurity.Authority authority =
        (context, rpc, policy) ->
            Mono.just(
                context.getContext().hasSessionId()
                    && context.getContext().getIssuerService().equals("gateway")
                    && context.getContext().hasActorUserId());
    var sender =
        new ru.taska.security.tenant.RpcContextSecurity(
            "gateway", Clock.systemUTC(), keys, replay, authority);
    var recipient =
        new ru.taska.security.tenant.RpcContextSecurity(
            "tenant-service", Clock.systemUTC(), keys, replay, authority);
    var policies =
        Map.of(
            TenantServiceGrpc.getCreateTenantMethod().getFullMethodName(),
            new ru.taska.security.tenant.RpcContextSecurity.Policy(false, true),
            TenantServiceGrpc.getGetTenantMethod().getFullMethodName(),
            new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false));
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
                    new TenantGrpcService(
                        service,
                        org.mockito.Mockito.mock(TenantMembershipService.class),
                        org.mockito.Mockito.mock(SeatQuotaStore.class),
                        org.mockito.Mockito.mock(TenantMembershipMutationService.class)),
                    new ru.taska.security.tenant.TenantServerInterceptor(
                        recipient, policies, "test")))
            .build()
            .start();
    var channel =
        io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                "localhost", server.getPort())
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(certs.resolve("ca.crt").toFile())
                    .keyManager(
                        certs.resolve("gateway.crt").toFile(),
                        certs.resolve("gateway.key").toFile())
                    .build())
            .build();
    try {
      var create =
          request("signed", PAID, "Transport Company").toBuilder()
              .setContext(wireContext(OWNER, null, "createTenant"))
              .build();
      var plain =
          TenantServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
      var error =
          assertThrows(io.grpc.StatusRuntimeException.class, () -> plain.createTenant(create));
      assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
      var meta =
          signed(
              sender,
              create,
              policies.get(TenantServiceGrpc.getCreateTenantMethod().getFullMethodName()),
              TenantServiceGrpc.getCreateTenantMethod().getFullMethodName());
      var stub =
          plain.withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(meta));
      var changed =
          create.toBuilder()
              .setPayload(create.getPayload().toBuilder().setName("tampered"))
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.createTenant(changed))
              .getStatus()
              .getCode());
      var result = stub.createTenant(create);
      assertEquals(OWNER.toString(), result.getTenant().getOwnerId());
      assertEquals(1, count("tenants"));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.createTenant(create))
              .getStatus()
              .getCode());
      for (UUID actor : List.of(OWNER, OTHER)) {
        UUID tenant = UUID.fromString(result.getTenant().getId());
        var read =
            GetTenantRpcRequest.newBuilder()
                .setContext(wireContext(actor, tenant, "getTenant"))
                .setTenantId(tenant.toString())
                .build();
        String rpc = TenantServiceGrpc.getGetTenantMethod().getFullMethodName();
        var readStub =
            plain.withInterceptors(
                io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                    signed(sender, read, policies.get(rpc), rpc)));
        if (actor.equals(OWNER)) {
          var current = readStub.getTenant(read);
          assertEquals(result.getTenant(), current.toBuilder().clearMembership().build());
          assertEquals(
              ru.taska.api.b2b.v1.CallerMembership.newBuilder()
                  .setRole(ru.taska.api.b2b.v1.Membership.RoleValue.ROLE_TENANT_ADMIN)
                  .setState(ru.taska.api.b2b.v1.Membership.StateValue.STATE_ACTIVE)
                  .setIsOwner(true)
                  .setVersion("1")
                  .build(),
              current.getMembership());
        } else
          assertEquals(
              io.grpc.Status.Code.NOT_FOUND,
              assertThrows(io.grpc.StatusRuntimeException.class, () -> readStub.getTenant(read))
                  .getStatus()
                  .getCode());
      }
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
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
  void replayAndGetHideDeletedTenantEvenBeforeMembershipCleanup() {
    var created = create(OWNER, "deleted", PAID).block(WAIT);
    UUID id = UUID.fromString(created.getTenant().getId());
    operator
        .sql("UPDATE taska.tenants SET lifecycle='DELETED',deleted_at=now()")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, get(OWNER, id));
    rejects(DomainStatus.NOT_FOUND, create(OWNER, "deleted", PAID));
    rejects(
        DomainStatus.NOT_FOUND,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(request("deleted", PAID, "different"))));
    assertEquals(1, count("tenants"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void outboxFailureRollsBackRegistryMembershipAndIdempotencyBeforeRetry() {
    operator
        .sql(
            "CREATE FUNCTION public.tas310_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN RAISE EXCEPTION 'fixture outbox failure'; END $$")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    operator
        .sql(
            "CREATE TRIGGER tas310_fail_outbox BEFORE INSERT ON taska.outbox_events FOR EACH ROW"
                + " EXECUTE FUNCTION public.tas310_fail_outbox()")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    try {
      StepVerifier.create(create(OWNER, "outbox-failure", PAID)).expectError().verify(WAIT);
      for (String table :
          List.of("tenants", "tenant_memberships", "outbox_events", "idempotency_records"))
        assertEquals(0, count(table));
    } finally {
      operator
          .sql("DROP TRIGGER tas310_fail_outbox ON taska.outbox_events")
          .fetch()
          .rowsUpdated()
          .block(WAIT);
      operator.sql("DROP FUNCTION public.tas310_fail_outbox()").fetch().rowsUpdated().block(WAIT);
    }
    assertNotNull(create(OWNER, "outbox-failure", PAID).block(WAIT));
    assertEquals(1, count("tenants"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void competingDifferentPayloadsAtTheSameKeyCommitOneAndReturnConflict() {
    var arrivals = new AtomicInteger();
    var barrier = reactor.core.publisher.Sinks.<Boolean>one();
    service =
        registry(
            id ->
                Mono.defer(
                    () -> {
                      if (arrivals.incrementAndGet() == 2) barrier.tryEmitValue(true);
                      return barrier
                          .asMono()
                          .timeout(WAIT)
                          .thenReturn(new PublishedPlan(id, false));
                    }),
            (db, actor, tenant) -> Mono.empty());
    var results =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    RegistryTestContext.as(
                            OWNER,
                            null,
                            "createTenant",
                            CREDENTIAL_KIND_WEB_GLOBAL,
                            true,
                            service.create(
                                request(
                                    "different-race",
                                    PAID,
                                    i == 0 ? "First payload" : "Second payload")))
                        .materialize()
                        .subscribeOn(Schedulers.parallel()),
                2)
            .collectList()
            .block(WAIT);
    assertEquals(2, arrivals.get());
    assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
    var failure =
        results.stream().filter(reactor.core.publisher.Signal::isOnError).findFirst().orElseThrow();
    var error = assertInstanceOf(DomainException.class, failure.getThrowable());
    assertEquals(DomainStatus.ALREADY_EXISTS, error.getStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", error.getDetails().get("code"));
    for (String table :
        List.of("tenants", "tenant_memberships", "outbox_events", "idempotency_records"))
      assertEquals(1, count(table));
    assertEquals(
        "ACTIVE",
        operator
            .sql("SELECT status FROM taska.tenant_memberships")
            .map((row, m) -> row.get("status", String.class))
            .one()
            .block(WAIT));
    // Conflict reporting never outranks visibility after that membership has been revoked.
    operator
        .sql("UPDATE taska.tenant_memberships SET status='REMOVED'")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(
        DomainStatus.NOT_FOUND,
        RegistryTestContext.as(
            OWNER,
            null,
            "createTenant",
            CREDENTIAL_KIND_WEB_GLOBAL,
            true,
            service.create(request("different-race", PAID, "third payload"))));
  }

  @Test
  void sqlBootstrapHookStartsFreshPostgresAndProvisionsRuntimeLogin() throws Exception {
    java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
    while (root != null && !java.nio.file.Files.exists(root.resolve("infra/tenant/init.sql")))
      root = root.getParent();
    assertNotNull(root, "Repository SQL init fixture missing");
    String appPassword = UUID.randomUUID().toString(),
        deployerPassword = UUID.randomUUID().toString();
    try (var fresh = new PostgreSQLContainer<>("postgres:16")) {
      fresh
          .withEnv("TENANT_APP_PASSWORD", appPassword)
          .withEnv("TENANT_DEPLOY_PASSWORD", deployerPassword)
          .withCopyFileToContainer(
              MountableFile.forHostPath(root.resolve("infra/tenant/init.sql")),
              "/docker-entrypoint-initdb.d/10-tenant.sql");
      for (String extension : List.of("sql", "psql"))
        fresh.withCopyFileToContainer(
            MountableFile.forClasspathResource("db/tenant/bootstrap." + extension),
            "/opt/taska/templates/bootstrap." + extension);
      fresh.start();
      try (var connection =
              java.sql.DriverManager.getConnection(
                  fresh.getJdbcUrl(), "taska_tenant_app", appPassword);
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT current_user='taska_tenant_app' AND NOT rolsuper AND NOT rolbypassrls AND"
                      + " NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE"
                      + " rolname=current_user")) {
        assertTrue(rows.next());
        assertTrue(rows.getBoolean(1));
      }
      try (var connection =
              java.sql.DriverManager.getConnection(
                  fresh.getJdbcUrl(), "taska_tenant_deployer", deployerPassword);
          var statement = connection.createStatement()) {
        statement.execute("SET ROLE taska_tenant_migrator");
        try (var rows =
            statement.executeQuery(
                "SELECT nspowner='taska_tenant_migrator'::regrole FROM pg_namespace WHERE"
                    + " nspname='tenant_liquibase'")) {
          assertTrue(rows.next());
          assertTrue(rows.getBoolean(1));
        }
      }
    }
  }

  static TrustedTenantContext restrictionClaims(
      UUID actor, UUID tenant, String action, boolean admin) {
    var session =
        UUID.nameUUIDFromBytes(actor.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    var c =
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor.toString())
            .setTenantId(tenant.toString())
            .setAction(action)
            .setActorGeneration("1")
            .setSessionId(session)
            .setCredentialId(session)
            .addScopes("read")
            .addScopes("write");
    if (admin)
      c.setCredentialKind(CREDENTIAL_KIND_WEB_GLOBAL)
          .setAdministrativeScope(
              ru.taska.api.b2b.v1.AdminScope.newBuilder()
                  .setKind(ru.taska.api.b2b.v1.AdminScope.KindValue.KIND_TENANT_SET)
                  .addTenantIds(tenant.toString()))
          .setAdministrativeLevel(ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY);
    else c.setCredentialKind(CREDENTIAL_KIND_WEB_TENANT).setMembershipGeneration("1");
    return c.build();
  }

  Tenant workingRestrictionTenant(String key) {
    var tenant = create(OWNER, key, FREE).block(WAIT).getTenant();
    operator
        .sql("UPDATE taska.tenants SET lifecycle='ACTIVE' WHERE id=:t")
        .bind("t", UUID.fromString(tenant.getId()))
        .then()
        .block(WAIT);
    return tenant;
  }

  TenantRestrictionService restrictions() {
    return new TenantRestrictionService(transactions, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  Mono<Tenant> pause(UUID tenant, long version, String key) {
    var q =
        ru.taska.api.b2b.v1.PauseTenantRpcRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setMutation(
                MutationOptions.newBuilder()
                    .setIdempotencyKey(key)
                    .setExpectedVersion(Long.toString(version)))
            .setPayload(ru.taska.api.b2b.v1.ConfirmedAction.newBuilder().setConfirmed(true))
            .build();
    return RegistryTestContext.withClaims(
        restrictionClaims(OWNER, tenant, "pauseTenant", false), restrictions().pause(q));
  }

  Mono<Tenant> resume(UUID tenant, long version, String key) {
    var q =
        ru.taska.api.b2b.v1.ResumeTenantRpcRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setMutation(
                MutationOptions.newBuilder()
                    .setIdempotencyKey(key)
                    .setExpectedVersion(Long.toString(version)))
            .build();
    return RegistryTestContext.withClaims(
        restrictionClaims(OWNER, tenant, "resumeTenant", false), restrictions().resume(q));
  }

  Mono<Tenant> block(UUID tenant, long version, boolean enabled, String reason, String key) {
    var q =
        ru.taska.api.b2b.v1.SetPlatformBlockRpcRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setPayload(
                ru.taska.api.b2b.v1.AdminRestrictionRequest.newBuilder()
                    .setEnabled(enabled)
                    .setReason(reason)
                    .setConfirmed(true))
            .setMutation(
                MutationOptions.newBuilder()
                    .setIdempotencyKey(key)
                    .setExpectedVersion(Long.toString(version)))
            .build();
    return RegistryTestContext.withClaims(
        restrictionClaims(OTHER, tenant, "setPlatformBlock", true), restrictions().block(q));
  }

  @Test
  void pauseAndPlatformBlockRemainIndependentAndReplayDoesNotReapplyEffect() {
    var tenant = UUID.fromString(workingRestrictionTenant("restrictions").getId());
    var key = UUID.randomUUID().toString();
    var paused = pause(tenant, 1, key).block(WAIT);
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE), paused.getRestrictionsList());
    var blocked =
        block(tenant, 2, true, "private operator evidence", UUID.randomUUID().toString())
            .block(WAIT);
    assertTrue(
        blocked
            .getRestrictionsList()
            .containsAll(
                List.of(
                    RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE,
                    RestrictionCode.RESTRICTION_CODE_PLATFORM_BLOCK)));
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () -> resume(tenant, 3, UUID.randomUUID().toString()).block(WAIT))
            .getStatus());
    var unblocked =
        block(tenant, 3, false, "closed operator evidence", UUID.randomUUID().toString())
            .block(WAIT);
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE), unblocked.getRestrictionsList());
    var resumed = resume(tenant, 4, UUID.randomUUID().toString()).block(WAIT);
    assertTrue(resumed.getRestrictionsList().isEmpty());
    assertEquals("5", resumed.getVersion());
    assertEquals(paused, pause(tenant, 1, key).block(WAIT));
    assertTrue(get(OWNER, tenant).block(WAIT).getRestrictionsList().isEmpty());
    assertEquals(
        4L,
        operator
            .sql(
                "SELECT count(*) n FROM taska.outbox_events WHERE"
                    + " event_type='TenantRestrictionChanged'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0L,
        operator
            .sql(
                "SELECT count(*) n FROM taska.outbox_events WHERE payload::text LIKE '%operator"
                    + " evidence%'")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertThrows(
        Exception.class,
        () ->
            RegistryTestContext.withClaims(
                    restrictionClaims(OWNER, tenant, "pauseTenant", false),
                    transactions.execute(
                        db -> db.sql("DELETE FROM taska.tenant_restriction_audit").then()))
                .block(WAIT));
  }

  @Test
  void parallelPauseUsesCasAndDisabledFlagDoesNotPreventResumeOrReplay() {
    var tenant = UUID.fromString(workingRestrictionTenant("restriction-race").getId());
    var keys = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    var results =
        Flux.merge(
                pause(tenant, 1, keys.get(0)).materialize(),
                pause(tenant, 1, keys.get(1)).materialize())
            .filter(s -> !s.isOnComplete())
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
    assertEquals(1, results.stream().filter(reactor.core.publisher.Signal::isOnError).count());
    operator
        .sql(
            "INSERT INTO"
                + " taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at)"
                + " SELECT key,999,value_type,'false'::jsonb,:now,:actor,:now FROM"
                + " taska.platform_configuration_definitions WHERE key='OWNER_PAUSE'")
        .bind("now", NOW.atOffset(ZoneOffset.UTC))
        .bind("actor", OWNER)
        .then()
        .block(WAIT);
    try {
      String winner = results.get(0).isOnNext() ? keys.get(0) : keys.get(1);
      // Identify the winning receipt independently of emission ordering.
      winner =
          operator
              .sql(
                  "SELECT key FROM taska.idempotency_records WHERE tenant_id=:t AND operation_code"
                      + " LIKE 'tenantRestriction/%'")
              .bind("t", tenant)
              .map((r, m) -> r.get("key", String.class))
              .one()
              .block(WAIT);
      assertEquals("2", pause(tenant, 1, winner).block(WAIT).getVersion());
      assertEquals(
          DomainStatus.PERMISSION_DENIED,
          assertThrows(
                  DomainException.class,
                  () -> pause(tenant, 2, UUID.randomUUID().toString()).block(WAIT))
              .getStatus());
      assertTrue(
          resume(tenant, 2, UUID.randomUUID().toString())
              .block(WAIT)
              .getRestrictionsList()
              .isEmpty());
    } finally {
      operator
          .sql(
              "INSERT INTO"
                  + " taska.platform_configuration_versions(key,version,value_type,value,effective_at,changed_by,created_at)"
                  + " SELECT key,1000,value_type,'true'::jsonb,:now,:actor,:now FROM"
                  + " taska.platform_configuration_definitions WHERE key='OWNER_PAUSE'")
          .bind("now", NOW.atOffset(ZoneOffset.UTC))
          .bind("actor", OWNER)
          .then()
          .block(WAIT);
    }
  }

  @Test
  void restrictionScopeAndReceiptFailurePreserveStateAndOtherReasons() {
    var tenant = UUID.fromString(workingRestrictionTenant("restriction-rollback").getId());
    var key = UUID.randomUUID().toString();
    var q =
        ru.taska.api.b2b.v1.SetPlatformBlockRpcRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setPayload(
                ru.taska.api.b2b.v1.AdminRestrictionRequest.newBuilder()
                    .setEnabled(true)
                    .setReason("private")
                    .setConfirmed(true))
            .setMutation(
                MutationOptions.newBuilder().setIdempotencyKey(key).setExpectedVersion("1"))
            .build();
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    RegistryTestContext.withClaims(
                            restrictionClaims(OWNER, tenant, "setPlatformBlock", false),
                            restrictions().block(q))
                        .block(WAIT))
            .getStatus());
    var pause =
        ru.taska.api.b2b.v1.PauseTenantRpcRequest.newBuilder()
            .setTenantId(tenant.toString())
            .setPayload(ru.taska.api.b2b.v1.ConfirmedAction.newBuilder().setConfirmed(true))
            .setMutation(
                MutationOptions.newBuilder().setIdempotencyKey(key).setExpectedVersion("1"))
            .build();
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        assertThrows(
                DomainException.class,
                () ->
                    RegistryTestContext.withClaims(
                            restrictionClaims(OWNER, UUID.randomUUID(), "pauseTenant", false),
                            restrictions().pause(pause))
                        .block(WAIT))
            .getStatus());
    assertEquals(
        DomainStatus.UNAUTHENTICATED,
        assertThrows(
                DomainException.class,
                () ->
                    RegistryTestContext.withClaims(
                            restrictionClaims(OWNER, tenant, "pauseTenant", false).toBuilder()
                                .setMembershipGeneration("99")
                                .build(),
                            restrictions().pause(pause))
                        .block(WAIT))
            .getStatus());
    operator
        .sql(
            "ALTER TABLE taska.idempotency_records ADD CONSTRAINT injected_restriction_receipt"
                + " CHECK(key<>'"
                + key
                + "')")
        .then()
        .block(WAIT);
    try {
      assertThrows(Exception.class, () -> pause(tenant, 1, key).block(WAIT));
    } finally {
      operator
          .sql("ALTER TABLE taska.idempotency_records DROP CONSTRAINT injected_restriction_receipt")
          .then()
          .block(WAIT);
    }
    assertEquals("1", get(OWNER, tenant).block(WAIT).getVersion());
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.tenant_restrictions")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.tenant_restriction_audit")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    var paused = pause(tenant, 1, key).block(WAIT);
    operator
        .sql(
            "INSERT INTO"
                + " taska.tenant_restrictions(tenant_id,kind,source_id,active,version,reason_code,created_at)"
                + " VALUES(:t,'NONPAYMENT',:id,true,1,'NONPAYMENT',:now)")
        .bind("t", tenant)
        .bind("id", UUID.randomUUID())
        .bind("now", NOW.atOffset(ZoneOffset.UTC))
        .then()
        .block(WAIT);
    var resumed =
        resume(tenant, Long.parseLong(paused.getVersion()), UUID.randomUUID().toString())
            .block(WAIT);
    assertEquals(
        List.of(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED), resumed.getRestrictionsList());
    assertEquals(
        resumed,
        resume(tenant, Long.parseLong(resumed.getVersion()), UUID.randomUUID().toString())
            .block(WAIT));
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.tenant_restrictions")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }
}
