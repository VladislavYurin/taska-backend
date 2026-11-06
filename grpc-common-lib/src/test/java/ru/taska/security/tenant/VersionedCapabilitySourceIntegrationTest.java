package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.api.b2b.v1.ErrorCode.*;
import static ru.taska.capability.Action.*;
import static ru.taska.capability.CapabilityFacts.*;
import static ru.taska.security.tenant.TenantIsolationFixture.*;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.*;
import ru.taska.configuration.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.event.*;
import ru.taska.exception.*;
import tools.jackson.databind.json.JsonMapper;

class VersionedCapabilitySourceIntegrationTest {
  static TenantIsolationFixture fixture;
  static final Instant START = PlatformConfigurationSnapshotCacheTest.START;
  static final UUID ACTOR = UUID.randomUUID();
  static final UUID RESOURCE = UUID.randomUUID();
  static final String FREE_PLAN = "30000000-0000-0000-0000-000000000002";
  static final String SESSION = UUID.randomUUID().toString(),
      CREDENTIAL = UUID.randomUUID().toString();
  final AtomicReference<Instant> time = new AtomicReference<>(START);
  final Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return time.get();
        }
      };
  final Map<UUID, Entitlement> primary = new HashMap<>();
  final Map<UUID, CapabilityTestFacts> actors = new HashMap<>();
  final AtomicInteger billingCalls = new AtomicInteger(),
      flagCalls = new AtomicInteger(),
      authorityCalls = new AtomicInteger();
  final AtomicBoolean online = new AtomicBoolean(true);
  final AtomicReference<PlatformConfigurationSnapshot> flags =
      new AtomicReference<>(PlatformConfigurationSnapshotCacheTest.initial());
  final AtomicReference<VersionedCapabilitySource.Inputs> last = new AtomicReference<>();
  EntitlementProjection projection;
  PlatformConfigurationSnapshotCache cache;
  VersionedCapabilitySource source;
  CapabilityEvaluator preview, commands;

  @BeforeAll
  static void database() throws Exception {
    fixture = new TenantIsolationFixture(3);
    String sql =
        "SET ROLE taska_project_migrator;" + resource("db/tenant/entitlement-projection.sql");
    for (String table : List.of("entitlement_projections", "entitlement_projection_events")) {
      sql +=
          "SELECT set_config('taska.guard_service','project',false),"
              + "set_config('taska.guard_schema','taska',false),set_config('taska.guard_table','"
              + table
              + "',false),set_config('taska.guard_column','tenant_id',false),"
              + "set_config('taska.guard_app_access','"
              + (table.endsWith("events") ? "append_only" : "read_write")
              + "',false);";
      sql += resource("db/tenant/protect-table.sql");
    }
    var result =
        fixture.postgres.execInContainer(
            "psql",
            "-X",
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            fixture.postgres.getUsername(),
            "-d",
            fixture.postgres.getDatabaseName(),
            "-c",
            sql);
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  static String resource(String name) throws Exception {
    try (var in = EntitlementProjection.class.getClassLoader().getResourceAsStream(name)) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @AfterAll
  static void close() {
    if (fixture != null) fixture.close();
  }

  @BeforeEach
  void setup() {
    fixture
        .operator
        .sql("TRUNCATE taska.entitlement_projection_events,taska.entitlement_projections")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    for (UUID tenant : List.of(A, B)) {
      var facts = new CapabilityTestFacts(ACTOR, tenant, clock, SESSION, CREDENTIAL);
      actors.put(tenant, facts);
      primary.put(tenant, snapshot(tenant, tenant.equals(A) ? 1 : 8));
    }
    projection =
        new EntitlementProjection(
            fixture.executor,
            (tenant, minimum) ->
                Mono.defer(
                    () -> {
                      billingCalls.incrementAndGet();
                      return online.get()
                          ? Mono.just(primary.get(tenant))
                          : Mono.error(new IllegalStateException());
                    }),
            (context, use) -> {
              var current = actors.get(UUID.fromString(context.tenantId()));
              return current.membershipState == Membership.StateValue.STATE_ACTIVE
                  ? Mono.just(current.restrictions)
                  : Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "revoked"));
            },
            (context, event) -> Mono.just(true),
            clock,
            Duration.ofSeconds(10),
            2);
    cache =
        new PlatformConfigurationSnapshotCache(
            () ->
                Mono.defer(
                    () -> {
                      flagCalls.incrementAndGet();
                      return online.get()
                          ? Mono.just(flags.get())
                          : Mono.error(new IllegalStateException());
                    }),
            (context, event) -> Mono.just(true),
            clock,
            Duration.ofSeconds(30));
    source =
        new VersionedCapabilitySource(
            (context, target, actions) -> {
              authorityCalls.incrementAndGet();
              var current = actors.get(target.tenant());
              if (!current.active
                  || !current.credentialActive
                  || current.membershipState != Membership.StateValue.STATE_ACTIVE)
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "current authority denied"));
              return Mono.just(
                  inputs -> {
                    last.set(inputs);
                    // Test catalog: kind belongs to the supplied immutable plan version.
                    current.plan =
                        inputs.entitlement().view().snapshot().getPlanVersionId().equals(FREE_PLAN)
                            ? PlanKind.FREE
                            : PlanKind.PAID;
                    return current.current(context, target, actions);
                  });
            },
            cache,
            projection,
            clock);
    preview = new CapabilityEvaluator(source.observations(), clock);
    commands = new CapabilityEvaluator(source, clock);
  }

  static TrustedTenantContext.Builder claims(UUID tenant) {
    return TrustedTenantContext.newBuilder()
        .setContextVersion(1)
        .setActorUserId(ACTOR.toString())
        .setTenantId(tenant.toString())
        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
        .setSessionId(SESSION)
        .setCredentialId(CREDENTIAL)
        .setActorGeneration("1")
        .setMembershipGeneration("2")
        .addScopes("read")
        .addScopes("write")
        .setIssuerService("api-gateway")
        .setAudienceService("project-service")
        .setIssuedAt(START.toString())
        .setExpiresAt(START.plusSeconds(30).toString())
        .setAssertionId(UUID.randomUUID().toString())
        .setAction("evaluateCapabilities");
  }

  static Entitlement snapshot(UUID tenant, long version) {
    var result =
        Entitlement.newBuilder()
            .setTenantId(tenant.toString())
            .setVersion(Long.toString(version))
            .setPlanVersionId("30000000-0000-0000-0000-000000000001")
            .setCoveragePeriodId("40000000-0000-0000-0000-000000000001")
            .setValidFrom(START.minusSeconds(100).toString())
            .setIssuedAt(START.toString())
            .setPaidThrough(START.plusSeconds(3600).toString());
    for (var kind :
        List.of(
            QuotaLimit.KindValue.KIND_PROJECT,
            QuotaLimit.KindValue.KIND_SEAT,
            QuotaLimit.KindValue.KIND_STORAGE_BYTES,
            QuotaLimit.KindValue.KIND_TASK_LIFETIME))
      result.addLimits(QuotaLimit.newBuilder().setKind(kind).setLimit("50"));
    return result.build();
  }

  CapabilityEvaluator.Decision decide(CapabilityEvaluator evaluator, UUID tenant, Action action) {
    return CapabilityEvaluatorTest.as(
            claims(tenant).build(),
            evaluator.evaluate(
                action,
                new Target(tenant, tenant.equals(A) ? PROJECT_A : PROJECT_B, RESOURCE, null)))
        .block(WAIT);
  }

  @Test
  void warmedOwnerFactsNeverBecomeMemberOrReadOnlyGrantAuthority() {
    assertTrue(decide(preview, A, TENANT_UPDATE).capability().getAllowed());
    int reads = billingCalls.get();
    var current = actors.get(A);
    current.owner = false;
    current.tenantAdmin = false;
    assertFalse(decide(preview, A, TENANT_UPDATE).capability().getAllowed());
    assertEquals(reads, billingCalls.get());
    assertEquals(2, authorityCalls.get());
    current.owner = true;
    current.tenantAdmin = true;
    current.globalAdmin = true;
    var scope =
        AdminScope.newBuilder()
            .setKind(AdminScope.KindValue.KIND_TENANT_SET)
            .addTenantIds(A.toString());
    current.grant =
        AdminGrant.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setActorUserId(ACTOR.toString())
            .setVersion("1")
            .setKind(AdminGrant.KindValue.KIND_PAT)
            .setLevel(AdminGrant.LevelValue.LEVEL_READ)
            .setScope(scope)
            .setExpiresAt(START.plusSeconds(600).toString())
            .build();
    var readGrant =
        claims(A)
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_ADMIN_PAT)
            .setGrantId(current.grant.getId())
            .setGrantGeneration("1")
            .setAdministrativeScope(scope)
            .setAdministrativeLevel(AdminGrant.LevelValue.LEVEL_READ)
            .build();
    var denied =
        CapabilityEvaluatorTest.as(
                readGrant, preview.evaluate(TENANT_UPDATE, new Target(A, PROJECT_A, null, null)))
            .block(WAIT);
    assertFalse(denied.capability().getAllowed());
  }

  @Test
  void membershipAndCredentialRevocationAreCheckedBeforeAnyCacheOrBillingRead() {
    assertTrue(decide(preview, A, ISSUE_READ).capability().getAllowed());
    int flagsBefore = flagCalls.get(), billingBefore = billingCalls.get();
    actors.get(A).membershipState = Membership.StateValue.STATE_SUSPENDED;
    assertThrows(DomainException.class, () -> decide(preview, A, ISSUE_READ));
    actors.get(A).membershipState = Membership.StateValue.STATE_ACTIVE;
    actors.get(A).credentialActive = false;
    assertThrows(DomainException.class, () -> decide(preview, A, ISSUE_READ));
    assertEquals(flagsBefore, flagCalls.get());
    assertEquals(billingBefore, billingCalls.get());
  }

  @Test
  void sourceKeysIncludeTenantAndBothVersionsWithoutCrossCompanyState() throws Exception {
    decide(preview, A, ISSUE_READ);
    var a = last.get().key();
    decide(preview, B, ISSUE_READ);
    var b = last.get().key();
    assertEquals(a.flagsVersion(), b.flagsVersion());
    assertNotEquals(a, b);
    assertEquals("1", a.entitlementVersion());
    assertEquals("8", b.entitlementVersion());
    var newer = snapshot(A, 2);
    primary.put(A, newer);
    var event =
        TaskaEvent.builder()
            .id(UUID.randomUUID())
            .scopeKind(EventScopeKind.TENANT)
            .tenantId(A)
            .scopeId(A)
            .aggregateType("Entitlement")
            .aggregateId(A)
            .eventType("EntitlementSnapshotChanged")
            .producerService("billing-service")
            .schemaVersion("2")
            .occurredAt(START)
            .payload(JsonMapper.builder().build().readTree(JsonFormat.printer().print(newer)))
            .build();
    CapabilityEvaluatorTest.as(claims(A).build(), projection.apply(event)).block(WAIT);
    decide(preview, A, ISSUE_READ);
    assertEquals("2", last.get().key().entitlementVersion());
    decide(preview, B, ISSUE_READ);
    assertEquals(b, last.get().key());
  }

  @Test
  void hardCommercialLeaseAndExpiryCannotBeExtendedByCapabilityReads() {
    primary.put(
        A, snapshot(A, 1).toBuilder().setPaidThrough(START.plusSeconds(12).toString()).build());
    decide(preview, A, ISSUE_READ);
    assertEquals(START.plusSeconds(10), last.get().validUntil());
    time.set(START.plusSeconds(9));
    decide(preview, A, ISSUE_READ);
    assertEquals(START.plusSeconds(10), last.get().validUntil());
    assertEquals(1, billingCalls.get());
    time.set(START.plusSeconds(11));
    decide(preview, A, ISSUE_READ);
    assertEquals(START.plusSeconds(12), last.get().validUntil());
    time.set(START.plusSeconds(12));
    var expired = decide(preview, A, ISSUE_UPDATE).capability();
    assertFalse(expired.getAllowed());
    assertTrue(expired.getDenialCodesList().contains(ERROR_CODE_READ_ONLY));
  }

  @Test
  void criticalDecisionsReconcileAndOutageNeverUsesWarmEntitlementOrFlags() {
    decide(preview, A, ISSUE_READ);
    decide(preview, A, ISSUE_READ);
    online.set(false);
    assertThrows(RuntimeException.class, () -> decide(commands, A, ISSUE_CREATE));
    online.set(true);
    flags.set(
        PlatformConfigurationSnapshotCacheTest.change(flags.get(), "PROJECT_CREATION", "2", false));
    var decision = decide(commands, A, PROJECT_CREATE).capability();
    assertFalse(decision.getAllowed());
    assertTrue(decision.getDenialCodesList().contains(ERROR_CODE_CAPABILITY_DISABLED));
  }

  @Test
  void ordinaryNotificationFlagsNeverDisableFinancialOrSecurityControlMessages() {
    flags.set(PlatformConfigurationSnapshotCacheTest.change(flags.get(), "INBOX", "2", false));
    assertFalse(decide(preview, A, NOTIFICATION_READ).capability().getAllowed());
    actors.get(A).controlNotification = true;
    assertTrue(decide(preview, A, NOTIFICATION_READ).capability().getAllowed());
    assertEquals(2, authorityCalls.get());
  }

  @Test
  void globalVersionInvalidationChangesBothTenantKeysWithoutReplacingCommercialVersions() {
    decide(preview, A, PROJECT_CREATE);
    var before = last.get().key();
    flags.set(
        PlatformConfigurationSnapshotCacheTest.change(flags.get(), "PROJECT_CREATION", "2", false));
    var worker =
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setAction("applyPlatformConfiguration")
            .addScopes("configuration:invalidate")
            .build();
    CapabilityEvaluatorTest.as(
            worker,
            cache.invalidate(new PlatformConfigurationSnapshotCache.Change("PROJECT_CREATION", 2)))
        .block(WAIT);
    assertFalse(decide(preview, A, PROJECT_CREATE).capability().getAllowed());
    var a = last.get().key();
    assertNotEquals(before.flagsVersion(), a.flagsVersion());
    assertEquals(before.entitlementVersion(), a.entitlementVersion());
    assertFalse(decide(preview, B, PROJECT_CREATE).capability().getAllowed());
    assertEquals(a.flagsVersion(), last.get().key().flagsVersion());
    assertEquals("8", last.get().key().entitlementVersion());
  }

  @Test
  void credentialExpiryBoundsFactsAndTenantMismatchNeverReadsSources() {
    time.set(START.plusSeconds(25));
    var bound =
        CapabilityEvaluatorTest.as(
                claims(A).build(),
                VerifiedTenantContext.current()
                    .flatMap(
                        context ->
                            source
                                .observations()
                                .current(
                                    context,
                                    new Target(A, PROJECT_A, RESOURCE, null),
                                    Set.of(ISSUE_READ))))
            .block(WAIT);
    assertEquals(START.plusSeconds(30), bound.validUntil());
    int authBefore = authorityCalls.get(),
        flagsBefore = flagCalls.get(),
        billingBefore = billingCalls.get();
    var mismatch =
        CapabilityEvaluatorTest.as(
                claims(A).build(),
                preview.evaluate(ISSUE_READ, new Target(B, PROJECT_B, RESOURCE, null)))
            .block(WAIT);
    assertFalse(mismatch.capability().getAllowed());
    assertTrue(mismatch.capability().getDenialCodesList().contains(ERROR_CODE_TENANT_MISMATCH));
    assertEquals(authBefore, authorityCalls.get());
    assertEquals(flagsBefore, flagCalls.get());
    assertEquals(billingBefore, billingCalls.get());
  }

  @Test
  void paidCompanyDoesNotDonateItsCachedMcpRightsAfterAnotherCompanyBecomesFree() throws Exception {
    for (UUID tenant : List.of(A, B)) actors.get(tenant).channel = Channel.MCP;
    java.util.function.Function<UUID, CapabilityEvaluator.Decision> mcp =
        tenant ->
            CapabilityEvaluatorTest.as(
                    claims(tenant)
                        .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
                        .build(),
                    preview.evaluate(
                        ISSUE_READ,
                        new Target(
                            tenant, tenant.equals(A) ? PROJECT_A : PROJECT_B, RESOURCE, null)))
                .block(WAIT);
    assertTrue(mcp.apply(A).capability().getAllowed());
    assertTrue(mcp.apply(B).capability().getAllowed());
    var free =
        snapshot(A, 2).toBuilder()
            .setPlanVersionId(FREE_PLAN)
            .clearCoveragePeriodId()
            .clearPaidThrough()
            .build();
    primary.put(A, free);
    var event =
        TaskaEvent.builder()
            .id(UUID.randomUUID())
            .scopeKind(EventScopeKind.TENANT)
            .tenantId(A)
            .scopeId(A)
            .aggregateType("Entitlement")
            .aggregateId(A)
            .eventType("EntitlementSnapshotChanged")
            .producerService("billing-service")
            .schemaVersion("2")
            .occurredAt(START)
            .payload(JsonMapper.builder().build().readTree(JsonFormat.printer().print(free)))
            .build();
    CapabilityEvaluatorTest.as(claims(A).build(), projection.apply(event)).block(WAIT);
    var denied = mcp.apply(A).capability();
    assertFalse(denied.getAllowed());
    assertTrue(denied.getDenialCodesList().contains(ERROR_CODE_CAPABILITY_DISABLED));
    assertTrue(mcp.apply(B).capability().getAllowed());
  }
}
