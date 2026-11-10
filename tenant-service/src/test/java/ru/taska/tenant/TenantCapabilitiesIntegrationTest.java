package ru.taska.tenant;

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
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.CapabilityFacts.PlanKind;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

@Testcontainers
@Timeout(120)
class TenantCapabilitiesIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final Duration WAIT = Duration.ofSeconds(20);
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static final UUID OWNER = UUID.randomUUID(),
      MEMBER = UUID.randomUUID(),
      A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      PLAN = UUID.randomUUID(),
      SESSION = UUID.randomUUID();
  static DatabaseClient operator;
  static ConnectionPool pool;
  static TenantTransactionExecutor tx;
  final Clock clock = Clock.systemUTC();
  final AtomicReference<List<PlatformFlag>> configuration = new AtomicReference<>();
  final Map<UUID, Entitlement> commercial = new HashMap<>();
  final AtomicBoolean identityOffline = new AtomicBoolean(),
      flagsOffline = new AtomicBoolean(),
      billingOffline = new AtomicBoolean();
  final AtomicInteger identityReads = new AtomicInteger(), sourceReads = new AtomicInteger();
  final AtomicReference<Instant> credentialEnd = new AtomicReference<>();
  TenantCapabilities capabilities;

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

  static void sql(String query) {
    operator.sql(query).then().block(WAIT);
  }

  @BeforeAll
  static void database() throws Exception {
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    var boot =
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
    assertEquals(0, boot.getExitCode(), boot.getStderr());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA tenant_liquibase AUTHORIZATION taska_tenant_migrator");
    sql("REVOKE ALL ON SCHEMA tenant_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_tenant_app PASSWORD '" + APP + "'");
    sql("ALTER ROLE taska_tenant_deployer PASSWORD '" + DEPLOY + "'");
    new TenantConfiguration()
        .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY)
        .afterPropertiesSet();
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP))
                .initialSize(1)
                .maxSize(2)
                .build());
    tx = new TenantTransactionExecutor(pool);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void setup() {
    sql(
        "TRUNCATE taska.entitlement_projections,taska.entitlement_projection_events,taska.tenants"
            + " CASCADE");
    for (UUID tenant : List.of(A, B))
      sql(
          "BEGIN;INSERT INTO"
              + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
              + " VALUES('"
              + tenant
              + "','Owned','"
              + OWNER
              + "','ACTIVE','FREE','"
              + PLAN
              + "','"
              + OWNER
              + "',decode('00','hex'),'"
              + UUID.randomUUID()
              + "');INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status) VALUES('"
              + tenant
              + "','"
              + OWNER
              + "','TENANT_ADMIN','ACTIVE'),('"
              + tenant
              + "','"
              + MEMBER
              + "','MEMBER','ACTIVE');COMMIT");
    var values = new ArrayList<PlatformFlag>();
    for (String key : PlatformConfigurationSnapshotCache.keys()) {
      var flag =
          PlatformFlag.newBuilder()
              .setKey(key)
              .setOwner("tenant-service")
              .setVersion("1")
              .setEffectiveAt(Instant.now().minusSeconds(5).toString());
      if (key.endsWith("_SECONDS"))
        flag.setType(PlatformFlag.TypeValue.TYPE_DURATION_SECONDS)
            .setIntegerValue("86400")
            .setDefaultIntegerValue("86400")
            .setMinimumValue("1")
            .setMaximumValue("31536000");
      else
        flag.setType(PlatformFlag.TypeValue.TYPE_BOOLEAN)
            .setBooleanValue(true)
            .setDefaultBooleanValue(true);
      values.add(flag.build());
    }
    configuration.set(values);
    commercial.put(A, entitlement(A, "1", false));
    commercial.put(B, entitlement(B, "1", true));
    var identities =
        new RpcTrustBackend() {
          public Mono<Boolean> allowed(RequestContext c, String r, RpcContextSecurity.Policy p) {
            return Mono.just(true);
          }

          public Mono<Boolean> consume(String r, String i, String j, Instant t) {
            return Mono.just(true);
          }

          public Mono<Boolean> currentKey(String r, String i, String k, String f) {
            return Mono.just(true);
          }

          public Mono<RpcActorFacts> actorFacts(
              RequestContext request, String rpc, RpcContextSecurity.Policy policy) {
            identityReads.incrementAndGet();
            if (identityOffline.get())
              return Mono.error(new IllegalStateException("owned primary outage"));
            var c = request.getContext();
            Instant end =
                credentialEnd.get() == null ? Instant.parse(c.getExpiresAt()) : credentialEnd.get();
            return Mono.just(
                RpcActorFacts.newBuilder()
                    .setActorUserId(c.getActorUserId())
                    .setActorGeneration(c.getActorGeneration())
                    .setCredentialId(c.getCredentialId())
                    .setSessionId(c.getSessionId())
                    .addAllScopes(c.getScopesList())
                    .setExpiresAt(end.toString())
                    .build());
          }
        };
    var flags =
        new PlatformConfigurationSnapshotCache(
            () -> {
              sourceReads.incrementAndGet();
              return flagsOffline.get()
                  ? Mono.error(new IllegalStateException("owned flags outage"))
                  : Mono.just(PlatformConfigurationSnapshotCache.snapshot(configuration.get()));
            },
            (context, event) -> Mono.just(false),
            clock,
            Duration.ofSeconds(30));
    capabilities =
        new TenantCapabilities(
            tx,
            identities,
            flags,
            (tenant, minimum) ->
                billingOffline.get()
                    ? Mono.error(new IllegalStateException("owned billing outage"))
                    : Mono.just(commercial.get(tenant)),
            plan -> Mono.just(PLAN.toString().equals(plan) ? PlanKind.PAID : PlanKind.UNKNOWN),
            clock);
  }

  static Entitlement entitlement(UUID tenant, String version, boolean expired) {
    Instant now = Instant.now();
    var result =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(version)
            .setPlanVersionId(PLAN.toString())
            .setValidFrom(now.minusSeconds(3600).toString())
            .setIssuedAt(now.toString())
            .setPaidThrough(now.plusSeconds(expired ? -1 : 3600).toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      result.addLimits(QuotaLimit.newBuilder().setKind(kind).setLimit("50"));
    return result.build();
  }

  TrustedTenantContext claims(UUID actor, UUID tenant, String generation) {
    Instant now = Instant.now();
    return TrustedTenantContext.newBuilder()
        .setContextVersion(1)
        .setActorUserId(actor.toString())
        .setTenantId(tenant.toString())
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
        .setSessionId(SESSION.toString())
        .setCredentialId(SESSION.toString())
        .setActorGeneration("1")
        .setMembershipGeneration(generation)
        .addScopes("read")
        .addScopes("write")
        .setAction("getTenantCapabilities")
        .setIssuerService("api-gateway")
        .setAudienceService("tenant-service")
        .setIssuedAt(now.toString())
        .setExpiresAt(now.plusSeconds(30).toString())
        .setAssertionId(UUID.randomUUID().toString())
        .build();
  }

  Capabilities get(UUID actor, UUID tenant) {
    return RegistryTestContext.withClaims(
            claims(actor, tenant, "1"), capabilities.get(tenant.toString()))
        .block(WAIT);
  }

  static Capability item(Capabilities c, Action action) {
    return c.getItemsList().stream()
        .filter(i -> i.getAction().equals(action.wire()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void primaryTenantRolesAndIndependentCommercialStatesProduceDifferentDecisions() {
    var ownerA = get(OWNER, A);
    var memberA = get(MEMBER, A);
    var ownerB = get(OWNER, B);
    assertTrue(item(ownerA, Action.TENANT_PAUSE).getAllowed());
    assertFalse(item(memberA, Action.TENANT_PAUSE).getAllowed());
    assertTrue(item(ownerA, Action.TENANT_TRANSFER_OWNERSHIP).getAllowed());
    assertFalse(item(ownerB, Action.TENANT_TRANSFER_OWNERSHIP).getAllowed());
    assertEquals(ownerA.getFlagVersion(), ownerB.getFlagVersion());
    assertNotEquals(ownerA.getTenantId(), ownerB.getTenantId());
    assertEquals(86400L, ownerA.getDurationsSecondsOrThrow("WIRE_RECONCILIATION_TARGET_SECONDS"));
    assertEquals(CapabilityEvaluator.tenantActions().size(), ownerA.getItemsCount());
    assertTrue(
        item(ownerA, Action.MEMBERSHIP_LEAVE)
            .getDenialCodesList()
            .contains(ErrorCode.ERROR_CODE_OWNER_MEMBERSHIP_PROTECTED));
  }

  @Test
  void everyReadRechecksMembershipAndCredentialBeforeReadingWarmSources() {
    get(MEMBER, A);
    int before = sourceReads.get();
    sql(
        "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=version+1 WHERE tenant_id='"
            + A
            + "' AND user_id='"
            + MEMBER
            + "'");
    assertThrows(DomainException.class, () -> get(MEMBER, A));
    assertEquals(before, sourceReads.get());
    identityOffline.set(true);
    assertThrows(DomainException.class, () -> get(OWNER, A));
    assertEquals(before, sourceReads.get());
    assertTrue(identityReads.get() >= 3);
  }

  @Test
  void flagAndEntitlementVersionsRefreshWithoutExtendingAnOldLease() {
    var first = get(OWNER, A);
    var next =
        configuration.get().stream()
            .map(
                f ->
                    f.getKey().equals("OWNER_PAUSE")
                        ? f.toBuilder().setVersion("2").setBooleanValue(false).build()
                        : f)
            .toList();
    configuration.set(next);
    commercial.put(A, entitlement(A, "2", true));
    var current = get(OWNER, A);
    assertNotEquals(first.getFlagVersion(), current.getFlagVersion());
    assertEquals("2", current.getEntitlementVersion());
    assertFalse(item(current, Action.TENANT_PAUSE).getAllowed());
    assertTrue(
        item(current, Action.TENANT_PAUSE)
            .getDenialCodesList()
            .contains(ErrorCode.ERROR_CODE_CAPABILITY_DISABLED));
    assertFalse(item(current, Action.TENANT_TRANSFER_OWNERSHIP).getAllowed());
    assertEquals("1", get(OWNER, B).getEntitlementVersion());
  }

  @Test
  void actualCredentialExpiryBoundsPublicLeaseAndFailuresNeverUseWarmAllows() {
    credentialEnd.set(Instant.now().plusSeconds(3));
    var shortLease = get(OWNER, A);
    assertFalse(Instant.parse(shortLease.getExpiresAt()).isAfter(credentialEnd.get()));
    flagsOffline.set(true);
    assertThrows(DomainException.class, () -> get(OWNER, A));
    flagsOffline.set(false);
    billingOffline.set(true);
    assertThrows(DomainException.class, () -> get(OWNER, A));
  }

  @Test
  void wrongTenantCannotQueryAnySourceOrModifyDomainState() {
    int before = sourceReads.get();
    assertThrows(
        DomainException.class,
        () ->
            RegistryTestContext.withClaims(claims(OWNER, A, "1"), capabilities.get(B.toString()))
                .block(WAIT));
    assertEquals(before, sourceReads.get());
    assertEquals(
        2L,
        operator
            .sql("SELECT count(*) AS n FROM taska.tenants")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) AS n FROM taska.outbox_events")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }
}
