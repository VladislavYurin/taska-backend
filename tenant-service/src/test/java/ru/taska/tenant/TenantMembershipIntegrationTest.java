package ru.taska.tenant;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.api.b2b.v1.CredentialKind.*;

import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

@Testcontainers
@Timeout(90)
class TenantMembershipIntegrationTest {
  static final Duration WAIT = Duration.ofSeconds(15);
  static final UUID A = id(1),
      B = id(2),
      OWNER = id(101),
      SHARED = id(102),
      SUSPENDED = id(103),
      REMOVED = id(104),
      ADMIN = id(105),
      FOREIGN = id(106),
      OWNER_B = id(107);
  static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
  static final String APP_PASSWORD = UUID.randomUUID().toString(),
      DEPLOY_PASSWORD = UUID.randomUUID().toString();
  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  @Container static final PostgreSQLContainer<?> pg = postgres();
  static ConnectionPool pool;
  static DatabaseClient operator, raw;
  static ConnectionFactory operatorFactory;
  static TenantTransactionExecutor transactions;
  TenantMembershipService service;
  MembershipCursor cursor;
  AtomicReference<Instant> time;
  Clock clock;
  TrustedTenantContext owner;

  static UUID id(int value) {
    return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
  }

  static PostgreSQLContainer<?> postgres() {
    var root = java.nio.file.Path.of("").toAbsolutePath();
    while (root != null && !java.nio.file.Files.exists(root.resolve("infra/tenant/init.sql")))
      root = root.getParent();
    if (root == null) throw new IllegalStateException("Repository fixture missing");
    var result =
        new PostgreSQLContainer<>("postgres:16")
            .withEnv("TENANT_APP_PASSWORD", APP_PASSWORD)
            .withEnv("TENANT_DEPLOY_PASSWORD", DEPLOY_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(root.resolve("infra/tenant/init.sql")),
                "/docker-entrypoint-initdb.d/10-tenant.sql");
    for (String ext : List.of("sql", "psql"))
      result.withCopyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/opt/taska/templates/bootstrap." + ext);
    return result;
  }

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
    new TenantConfiguration()
        .registryLiquibase(pg.getJdbcUrl(), "taska_tenant_deployer", DEPLOY_PASSWORD)
        .afterPropertiesSet();
    operatorFactory = factory(pg.getUsername(), pg.getPassword());
    operator = DatabaseClient.create(operatorFactory);
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_tenant_app", APP_PASSWORD))
                .initialSize(1)
                .maxSize(1)
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
  void seed() {
    operator
        .sql(
            "TRUNCATE"
                + " taska.tenant_creation_receipts,taska.tenant_provisioning_dispatch,taska.tenant_operations,taska.tenants,taska.tenant_memberships,taska.outbox_events,taska.idempotency_records")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    Mono<Void> seed =
        Flux.fromIterable(List.of(A, B))
            .concatMap(
                t ->
                    operator
                        .sql(
                            "INSERT INTO"
                                + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,membership_version,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                                + " VALUES"
                                + " (:id,'fixture',:owner,'ACTIVE','FREE',:generation,:plan,:owner,decode(repeat('00',32),'hex'),:operation)")
                        .bind("id", t)
                        .bind("owner", t.equals(A) ? OWNER : OWNER_B)
                        .bind("generation", t.equals(A) ? 10L : 20L)
                        .bind("plan", id(500))
                        .bind("operation", UUID.randomUUID())
                        .fetch()
                        .rowsUpdated())
            .then();
    record Seed(UUID tenant, UUID user, String role, String state, long version, int order) {}
    var members =
        List.of(
            new Seed(A, OWNER, "TENANT_ADMIN", "ACTIVE", 1, 0),
            new Seed(A, SHARED, "MEMBER", "ACTIVE", 3, 1),
            new Seed(A, SUSPENDED, "MEMBER", "SUSPENDED", 5, 2),
            new Seed(A, REMOVED, "MEMBER", "REMOVED", 7, 3),
            new Seed(A, ADMIN, "TENANT_ADMIN", "ACTIVE", 11, 4),
            new Seed(B, OWNER_B, "TENANT_ADMIN", "ACTIVE", 2, 0),
            new Seed(B, SHARED, "MEMBER", "SUSPENDED", 19, 1),
            new Seed(B, FOREIGN, "MEMBER", "ACTIVE", 99, 2));
    seed =
        seed.then(
            Flux.fromIterable(members)
                .concatMap(
                    m ->
                        operator
                            .sql(
                                "INSERT INTO"
                                    + " taska.tenant_memberships(tenant_id,user_id,role,status,version,joined_at)"
                                    + " VALUES (:t,:u,:role,:state,:version,:joined)")
                            .bind("t", m.tenant())
                            .bind("u", m.user())
                            .bind("role", m.role())
                            .bind("state", m.state())
                            .bind("version", m.version())
                            .bind("joined", NOW.plusSeconds(m.order()).atOffset(ZoneOffset.UTC))
                            .fetch()
                            .rowsUpdated())
                .then());
    TransactionalOperator.create(new R2dbcTransactionManager(operatorFactory))
        .transactional(seed)
        .block(WAIT);
    time = new AtomicReference<>(NOW);
    clock =
        new Clock() {
          public Instant instant() {
            return time.get();
          }

          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }
        };
    cursor = new MembershipCursor(KEY, clock);
    service =
        new TenantMembershipService(
            transactions, cursor, Set.of("auth-service", "project-service"));
    owner = human(OWNER, A, "1", "session-owner");
  }

  TrustedTenantContext human(UUID actor, UUID tenant, String generation, String session) {
    return TrustedTenantContext.newBuilder()
        .setActorUserId(actor.toString())
        .setTenantId(tenant.toString())
        .setActorGeneration("3")
        .setMembershipGeneration(generation)
        .setCredentialKind(CREDENTIAL_KIND_WEB_TENANT)
        .setSessionId(
            UUID.nameUUIDFromBytes(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString())
        .setIssuerService("api-gateway")
        .setAudienceService("tenant-service")
        .addScopes("read")
        .setAction("listTenantMembers")
        .build();
  }

  TrustedTenantContext backend(UUID tenant, String action) {
    return TrustedTenantContext.newBuilder()
        .setTenantId(tenant.toString())
        .setActorGeneration("service-1")
        .setCredentialKind(CREDENTIAL_KIND_SERVICE)
        .setIssuerService("auth-service")
        .setAudienceService("tenant-service")
        .addScopes("memberships:read")
        .setAction(action)
        .build();
  }

  static <T> Mono<T> as(TrustedTenantContext c, Mono<T> work) {
    return RegistryTestContext.withClaims(c, work);
  }

  static ListTenantMembersRpcRequest listRequest(UUID tenant, int limit, String token) {
    var page = PageRequest.newBuilder().setLimit(limit);
    if (token != null) page.setCursor(token);
    return ListTenantMembersRpcRequest.newBuilder()
        .setTenantId(tenant.toString())
        .setPage(page)
        .build();
  }

  Mono<MembershipPage> list(TrustedTenantContext c, int limit, String token) {
    return as(c, service.list(listRequest(UUID.fromString(c.getTenantId()), limit, token)));
  }

  Mono<MembershipValidationBatch> batch(UUID tenant, UUID... users) {
    return as(
        backend(tenant, "batchValidateMemberships"),
        service.batch(
            BatchValidateMembershipsRpcRequest.newBuilder()
                .setTenantId(tenant.toString())
                .addAllUserIds(Arrays.stream(users).map(UUID::toString).toList())
                .build()));
  }

  Mono<MembershipSnapshot> get(UUID tenant, UUID user) {
    return as(
        backend(tenant, "getMembership"),
        service.get(
            GetMembershipRpcRequest.newBuilder()
                .setTenantId(tenant.toString())
                .setUserId(user.toString())
                .build()));
  }

  static void rejects(DomainStatus status, Mono<?> work) {
    StepVerifier.create(work)
        .expectErrorSatisfies(
            e -> assertEquals(status, assertInstanceOf(DomainException.class, e).getStatus()))
        .verify(WAIT);
  }

  @AfterEach
  void clean() {
    var state =
        raw.sql(
                "SELECT current_user::text role,NULLIF(current_setting('app.tenant_id',true),'')"
                    + " tenant,NULLIF(current_setting('app.actor_id',true),'') actor")
            .fetch()
            .one()
            .block(WAIT);
    assertEquals("taska_tenant_app", state.get("role"));
    assertNull(state.get("tenant"));
    assertNull(state.get("actor"));
    assertEquals(
        0L,
        raw.sql("SELECT count(*) n FROM taska.tenant_memberships")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void paginatedMetadataIsStableTenantScopedAndExcludesRemoved() {
    var first = list(owner, 2, null).block(WAIT);
    assertEquals(
        List.of(OWNER.toString(), SHARED.toString()),
        first.getItemsList().stream().map(Membership::getUserId).toList());
    assertTrue(first.hasNextCursor());
    var second = list(owner, 2, first.getNextCursor()).block(WAIT);
    assertEquals(
        List.of(SUSPENDED.toString(), ADMIN.toString()),
        second.getItemsList().stream().map(Membership::getUserId).toList());
    assertFalse(second.hasNextCursor());
    assertEquals(first, list(owner, 2, null).block(WAIT));
    var all = new ArrayList<Membership>();
    all.addAll(first.getItemsList());
    all.addAll(second.getItemsList());
    assertEquals(4, all.stream().map(Membership::getId).distinct().count());
    for (var m : all) {
      assertEquals(A.toString(), m.getTenantId());
      assertTrue(m.getDisplayName().isEmpty());
      assertTrue(m.getEmail().isEmpty());
      assertFalse(m.hasAvatarPath());
    }
    assertTrue(first.getItems(0).getIsOwner());
    assertEquals("3", first.getItems(1).getVersion());
    assertEquals(Membership.StateValue.STATE_SUSPENDED, second.getItems(0).getState());
  }

  @Test
  void activeOrdinaryMemberCanReadButSuspendedRemovedAndStaleGenerationsCannot() {
    assertEquals(
        4, list(human(SHARED, A, "3", "member-session"), 0, null).block(WAIT).getItemsCount());
    for (var claims :
        List.of(
            human(SUSPENDED, A, "5", "s"),
            human(REMOVED, A, "7", "r"),
            human(SHARED, A, "2", "stale"),
            human(FOREIGN, A, "99", "foreign")))
      rejects(DomainStatus.NOT_FOUND, list(claims, 2, null));
    operator
        .sql("UPDATE taska.tenants SET lifecycle='AWAITING_PAYMENT' WHERE id=:id")
        .bind("id", A)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var pending = assertThrows(DomainException.class, () -> list(owner, 2, null).block(WAIT));
    assertEquals(DomainStatus.FAILED_PRECONDITION, pending.getStatus());
    assertEquals("PAYMENT_PENDING", pending.getDetails().get("code"));
    assertEquals("PENDING_FIRST_PAYMENT", pending.getDetails().get("reason"));
    operator
        .sql("UPDATE taska.tenants SET lifecycle='PROVISIONING' WHERE id=:id")
        .bind("id", A)
        .then()
        .block(WAIT);
    var preparing = assertThrows(DomainException.class, () -> list(owner, 2, null).block(WAIT));
    assertEquals("INVALID_STATE", preparing.getDetails().get("code"));
  }

  @Test
  void publicCredentialScopeAndTenantChecksRejectBeforeDisclosure() {
    rejects(DomainStatus.UNAUTHENTICATED, service.list(listRequest(A, 2, null)));
    for (var kind :
        List.of(CREDENTIAL_KIND_WEB_GLOBAL, CREDENTIAL_KIND_ADMIN_PAT, CREDENTIAL_KIND_SERVICE))
      rejects(
          DomainStatus.PERMISSION_DENIED,
          list(owner.toBuilder().setCredentialKind(kind).build(), 2, null));
    rejects(DomainStatus.PERMISSION_DENIED, list(owner.toBuilder().clearScopes().build(), 2, null));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        list(owner.toBuilder().clearMembershipGeneration().build(), 2, null));
    rejects(DomainStatus.PERMISSION_DENIED, as(owner, service.list(listRequest(B, 2, null))));
    rejects(
        DomainStatus.PERMISSION_DENIED,
        list(owner.toBuilder().setAction("renameTenant").build(), 2, null));
  }

  @Test
  void cursorBindsSessionActorTenantScopesGenerationAndPageSize() {
    String token = list(owner, 1, null).block(WAIT).getNextCursor();
    for (var changed :
        List.of(
            owner.toBuilder().setSessionId(UUID.randomUUID().toString()).build(),
            human(SHARED, A, "3", "other"),
            owner.toBuilder().setTenantId(B.toString()).build(),
            owner.toBuilder().addScopes("write").build(),
            owner.toBuilder().setActorGeneration("4").build(),
            owner.toBuilder().setGrantGeneration("2").build()))
      rejects(DomainStatus.INVALID_ARGUMENT, list(changed, 1, token));
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 2, token));
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        list(owner, 1, token.substring(0, token.length() - 2) + "xx"));
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 1, "x".repeat(1025)));
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 1, ""));
  }

  @Test
  void cursorExpiresAndDoesNotBecomeAuthorityAfterRevocationOrRejoin() {
    String token = list(owner, 1, null).block(WAIT).getNextCursor();
    time.set(NOW.plusSeconds(900));
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 1, token));
    time.set(NOW);
    operator
        .sql(
            "UPDATE taska.tenant_memberships SET status='REMOVED',version=2 WHERE tenant_id=:t AND"
                + " user_id=:u")
        .bind("t", A)
        .bind("u", OWNER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, list(owner, 1, token));
    operator
        .sql(
            "UPDATE taska.tenant_memberships SET status='ACTIVE',version=3 WHERE tenant_id=:t AND"
                + " user_id=:u")
        .bind("t", A)
        .bind("u", OWNER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, list(owner, 1, token));
    var rejoined = owner.toBuilder().setMembershipGeneration("3").build();
    rejects(DomainStatus.INVALID_ARGUMENT, list(rejoined, 1, token));
    assertEquals(4, list(rejoined, 10, null).block(WAIT).getItemsCount());
  }

  @Test
  void emptyLateralPagePreservesHeaderAndStillChecksGeneration() {
    String token = list(owner, 1, null).block(WAIT).getNextCursor();
    // Deliberately keep the generation in this fixture to exercise an empty LATERAL result.
    // Real membership mutations must advance it; that case is checked below as well.
    operator
        .sql(
            "UPDATE taska.tenant_memberships SET status='REMOVED' WHERE tenant_id=:t AND"
                + " user_id<>:owner")
        .bind("t", A)
        .bind("owner", OWNER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var empty = list(owner, 1, token).block(WAIT);
    assertEquals(0, empty.getItemsCount());
    assertFalse(empty.hasNextCursor());
    operator
        .sql("UPDATE taska.tenants SET membership_version=11 WHERE id=:t")
        .bind("t", A)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 1, token));
  }

  @Test
  void aggregateGenerationInvalidatesOldPageAndBoundsAreEnforced() {
    String token = list(owner, 1, null).block(WAIT).getNextCursor();
    operator
        .sql("UPDATE taska.tenants SET membership_version=11 WHERE id=:t")
        .bind("t", A)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 1, token));
    assertEquals(4, list(owner, 100, null).block(WAIT).getItemsCount());
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, 101, null));
    rejects(DomainStatus.INVALID_ARGUMENT, list(owner, -1, null));
  }

  @Test
  void batchedChecksReturnAllCurrentStatesAndOnlyRequestedSubjects() {
    var result = batch(A, REMOVED, SHARED, SUSPENDED, FOREIGN).block(WAIT);
    assertEquals("10", result.getTenantMembershipVersion());
    assertEquals(Tenant.LifecycleValue.LIFECYCLE_ACTIVE, result.getTenantLifecycle());
    assertEquals(
        List.of(REMOVED.toString(), SHARED.toString(), SUSPENDED.toString(), FOREIGN.toString()),
        result.getItemsList().stream().map(MembershipValidation::getUserId).toList());
    assertEquals(
        Membership.StateValue.STATE_REMOVED, result.getItems(0).getMembership().getState());
    assertEquals("7", result.getItems(0).getMembership().getVersion());
    assertEquals(Membership.StateValue.STATE_ACTIVE, result.getItems(1).getMembership().getState());
    assertEquals(
        Membership.StateValue.STATE_SUSPENDED, result.getItems(2).getMembership().getState());
    assertFalse(result.getItems(3).getFound());
    assertFalse(result.getItems(3).hasMembership());
    for (var item : result.getItemsList())
      if (item.getFound()) {
        assertEquals(A.toString(), item.getMembership().getTenantId());
        assertEquals("10", item.getMembership().getTenantMembershipVersion());
      }
  }

  @Test
  void sameIdentityHasIndependentMembershipAndVersionInEachTenant() {
    var a = get(A, SHARED).block(WAIT);
    var b = get(B, SHARED).block(WAIT);
    assertEquals(Membership.StateValue.STATE_ACTIVE, a.getState());
    assertEquals("3", a.getVersion());
    assertEquals(Membership.StateValue.STATE_SUSPENDED, b.getState());
    assertEquals("19", b.getVersion());
    assertEquals("20", b.getTenantMembershipVersion());
    var aId =
        list(owner, 50, null).block(WAIT).getItemsList().stream()
            .filter(m -> m.getUserId().equals(SHARED.toString()))
            .findFirst()
            .orElseThrow()
            .getId();
    var bId =
        list(human(OWNER_B, B, "2", "owner-b"), 50, null).block(WAIT).getItemsList().stream()
            .filter(m -> m.getUserId().equals(SHARED.toString()))
            .findFirst()
            .orElseThrow()
            .getId();
    assertNotEquals(aId, bId);
    rejects(DomainStatus.NOT_FOUND, get(A, FOREIGN));
    assertEquals("99", get(B, FOREIGN).block(WAIT).getVersion());
  }

  @Test
  void backendRequiresServiceAllowlistReadPurposeAndExactTenant() {
    var req =
        BatchValidateMembershipsRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .addUserIds(SHARED.toString())
            .build();
    for (var c :
        List.of(
            owner.toBuilder()
                .setAction("batchValidateMemberships")
                .addScopes("memberships:read")
                .build(),
            backend(A, "batchValidateMemberships").toBuilder()
                .setIssuerService("untrusted")
                .build(),
            backend(A, "batchValidateMemberships").toBuilder().clearScopes().build(),
            backend(B, "batchValidateMemberships"),
            backend(A, "getMembership")))
      rejects(DomainStatus.PERMISSION_DENIED, as(c, service.batch(req)));
    rejects(DomainStatus.UNAUTHENTICATED, service.batch(req));
  }

  @Test
  void batchRejectsMalformedDuplicateAndUnboundedInputs() {
    rejects(DomainStatus.INVALID_ARGUMENT, batch(A));
    rejects(DomainStatus.INVALID_ARGUMENT, batch(A, OWNER, OWNER));
    var many =
        java.util.stream.IntStream.range(0, 101).mapToObj(i -> id(i + 1000)).toArray(UUID[]::new);
    rejects(DomainStatus.INVALID_ARGUMENT, batch(A, many));
    var bad =
        BatchValidateMembershipsRpcRequest.newBuilder()
            .setTenantId(A.toString())
            .addUserIds("bad")
            .build();
    rejects(
        DomainStatus.INVALID_ARGUMENT,
        as(backend(A, "batchValidateMemberships"), service.batch(bad)));
    assertEquals(100, batch(A, Arrays.copyOf(many, 100)).block(WAIT).getItemsCount());
  }

  @Test
  void readsAreFreshAndCannotMutateMembershipOrEmitEvents() {
    var before =
        operator
            .sql(
                "SELECT tenant_id,user_id,role,status,version,joined_at FROM"
                    + " taska.tenant_memberships ORDER BY tenant_id,user_id")
            .fetch()
            .all()
            .collectList()
            .block(WAIT);
    for (int i = 0; i < 3; i++) {
      get(A, SHARED).block(WAIT);
      batch(A, SHARED, REMOVED).block(WAIT);
      list(owner, 50, null).block(WAIT);
    }
    assertEquals(
        before,
        operator
            .sql(
                "SELECT tenant_id,user_id,role,status,version,joined_at FROM"
                    + " taska.tenant_memberships ORDER BY tenant_id,user_id")
            .fetch()
            .all()
            .collectList()
            .block(WAIT));
    assertEquals(
        0L,
        operator
            .sql("SELECT count(*) n FROM taska.outbox_events")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    operator
        .sql(
            "UPDATE taska.tenant_memberships SET status='REMOVED',version=4 WHERE tenant_id=:t AND"
                + " user_id=:u")
        .bind("t", A)
        .bind("u", SHARED)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    assertEquals("4", get(A, SHARED).block(WAIT).getVersion());
    assertEquals(Membership.StateValue.STATE_REMOVED, get(A, SHARED).block(WAIT).getState());
  }

  @Test
  void missingDeletedTenantAndMissingCursorPolicyFailClosed() {
    rejects(DomainStatus.NOT_FOUND, get(id(9999), SHARED));
    operator
        .sql("UPDATE taska.tenants SET lifecycle='DELETED' WHERE id=:t")
        .bind("t", A)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejects(DomainStatus.NOT_FOUND, batch(A, OWNER));
    var disabled =
        new TenantMembershipService(
            transactions, new MembershipCursor("", clock), Set.of("auth-service"));
    rejects(DomainStatus.UNAVAILABLE, as(owner, disabled.list(listRequest(A, 1, null))));
    assertThrows(IllegalArgumentException.class, () -> new MembershipCursor("bad", clock));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantMembershipService(transactions, cursor, Set.of("*")));
  }

  @Test
  void equalJoinTimesHaveStableUuidTieBreakAcrossPages() {
    operator
        .sql("UPDATE taska.tenant_memberships SET joined_at=:at WHERE tenant_id=:t")
        .bind("at", NOW.atOffset(ZoneOffset.UTC))
        .bind("t", A)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var seen = new ArrayList<String>();
    String token = null;
    do {
      var page = list(owner, 1, token).block(WAIT);
      assertEquals(1, page.getItemsCount());
      seen.add(page.getItems(0).getUserId());
      token = page.hasNextCursor() ? page.getNextCursor() : null;
    } while (token != null);
    assertEquals(
        List.of(OWNER.toString(), SHARED.toString(), SUSPENDED.toString(), ADMIN.toString()), seen);
  }

  @Test
  void personalCredentialsKeepMemberReadScopeAndIndependentCursorBinding() {
    String token = list(owner, 1, null).block(WAIT).getNextCursor();
    for (var kind : List.of(CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_USER_OAUTH)) {
      var claims =
          owner.toBuilder()
              .setCredentialKind(kind)
              .clearSessionId()
              .setCredentialId(UUID.randomUUID().toString())
              .build();
      assertEquals(4, list(claims, 10, null).block(WAIT).getItemsCount());
      rejects(DomainStatus.INVALID_ARGUMENT, list(claims, 1, token));
    }
  }

  @Test
  void versionAndAggregateGenerationShareOneSnapshotDuringConcurrentUpdates() {
    operator
        .sql("UPDATE taska.tenant_memberships SET version=10 WHERE tenant_id=:t AND user_id=:u")
        .bind("t", A)
        .bind("u", SHARED)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var tx = TransactionalOperator.create(new R2dbcTransactionManager(operatorFactory));
    var writes =
        Flux.range(11, 20)
            .concatMap(
                version ->
                    tx.transactional(
                        operator
                            .sql(
                                "UPDATE taska.tenant_memberships SET version=:v WHERE tenant_id=:t"
                                    + " AND user_id=:u")
                            .bind("v", (long) version)
                            .bind("t", A)
                            .bind("u", SHARED)
                            .fetch()
                            .rowsUpdated()
                            .then(Mono.delay(Duration.ofMillis(2)))
                            .then(
                                operator
                                    .sql(
                                        "UPDATE taska.tenants SET membership_version=:v WHERE"
                                            + " id=:t")
                                    .bind("v", (long) version)
                                    .bind("t", A)
                                    .fetch()
                                    .rowsUpdated())))
            .then();
    var reads =
        Flux.range(0, 60)
            .concatMap(
                i ->
                    batch(A, SHARED)
                        .doOnNext(
                            result ->
                                assertEquals(
                                    result.getTenantMembershipVersion(),
                                    result.getItems(0).getMembership().getVersion())))
            .then();
    Mono.when(writes, reads).block(WAIT);
    assertEquals("30", get(A, SHARED).block(WAIT).getVersion());
  }

  RequestContext wire(TrustedTenantContext claims) {
    Instant now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(
            ru.taska.api.common.v1.Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            claims.toBuilder()
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }

  @Test
  void realMtlsBindsNewMethodsToPoliciesAndReadsFreshDatabaseState(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path certs) throws Exception {
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
        "/CN=membership-test-ca");
    for (String name : List.of("api-gateway", "auth-service", "tenant-service")) {
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
      java.nio.file.Files.writeString(
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
    var pairs =
        Map.of(
            "api-gateway",
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            "auth-service",
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test", pairs.get(issuer).getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return pairs.containsKey(issuer) && kid.equals("test")
                ? pairs.get(issuer).getPublic()
                : null;
          }
        };
    var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    RpcContextSecurity.Authority authority = (context, rpc, policy) -> Mono.just(allowed.get());
    RpcContextSecurity.ReplayGuard replay = (issuer, jti, until) -> Mono.just(true);
    var recipient =
        new RpcContextSecurity("tenant-service", Clock.systemUTC(), keys, replay, authority);
    var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    factory.registerSingleton("security", recipient);
    var interceptor =
        new TenantConfiguration()
            .registrySecurity(factory.getBeanProvider(RpcContextSecurity.class), "test");
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
                        org.mockito.Mockito.mock(TenantRegistryService.class), service),
                    interceptor))
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new MembershipGrpcService(service), interceptor))
            .build()
            .start();
    var channels = new ArrayList<io.grpc.ManagedChannel>();
    try {
      for (String issuer : List.of("api-gateway", "auth-service")) {
        var channel =
            io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(
                    "localhost", server.getPort())
                .sslContext(
                    io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                        .trustManager(certs.resolve("ca.crt").toFile())
                        .keyManager(
                            certs.resolve(issuer + ".crt").toFile(),
                            certs.resolve(issuer + ".key").toFile())
                        .build())
                .build();
        channels.add(channel);
        var sender = new RpcContextSecurity(issuer, Clock.systemUTC(), keys, replay, authority);
        var policy = new RpcContextSecurity.Policy(true, false);
        if (issuer.equals("api-gateway")) {
          var request = listRequest(A, 1, null).toBuilder().setContext(wire(owner)).build();
          String rpc = TenantServiceGrpc.getListTenantMembersMethod().getFullMethodName();
          var plain =
              TenantServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(
              io.grpc.Status.Code.UNAUTHENTICATED,
              assertThrows(
                      io.grpc.StatusRuntimeException.class, () -> plain.listTenantMembers(request))
                  .getStatus()
                  .getCode());
          var signed =
              plain.withInterceptors(
                  io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                      TenantRegistryIntegrationTest.signed(sender, request, policy, rpc)));
          assertEquals(
              io.grpc.Status.Code.UNAUTHENTICATED,
              assertThrows(
                      io.grpc.StatusRuntimeException.class,
                      () ->
                          signed.listTenantMembers(
                              request.toBuilder().setTenantId(B.toString()).build()))
                  .getStatus()
                  .getCode());
          var first = signed.listTenantMembers(request);
          assertEquals(OWNER.toString(), first.getItems(0).getUserId());
          var next =
              listRequest(A, 1, first.getNextCursor()).toBuilder().setContext(wire(owner)).build();
          assertEquals(
              SHARED.toString(),
              plain
                  .withInterceptors(
                      io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                          TenantRegistryIntegrationTest.signed(sender, next, policy, rpc)))
                  .listTenantMembers(next)
                  .getItems(0)
                  .getUserId());
        } else {
          var request =
              GetMembershipRpcRequest.newBuilder()
                  .setTenantId(A.toString())
                  .setUserId(SHARED.toString())
                  .setContext(wire(backend(A, "getMembership")))
                  .build();
          String rpc = TenantMembershipServiceGrpc.getGetMembershipMethod().getFullMethodName();
          var stub =
              TenantMembershipServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                  .withInterceptors(
                      io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                          TenantRegistryIntegrationTest.signed(sender, request, policy, rpc)));
          assertEquals("3", stub.getMembership(request).getVersion());
          operator
              .sql(
                  "UPDATE taska.tenant_memberships SET status='SUSPENDED',version=4 WHERE"
                      + " tenant_id=:t AND user_id=:u")
              .bind("t", A)
              .bind("u", SHARED)
              .fetch()
              .rowsUpdated()
              .block(WAIT);
          assertEquals("4", stub.getMembership(request).getVersion());
          assertEquals(
              Membership.StateValue.STATE_SUSPENDED, stub.getMembership(request).getState());
          var batch =
              BatchValidateMembershipsRpcRequest.newBuilder()
                  .setTenantId(A.toString())
                  .addUserIds(FOREIGN.toString())
                  .addUserIds(SHARED.toString())
                  .setContext(wire(backend(A, "batchValidateMemberships")))
                  .build();
          String batchRpc =
              TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod().getFullMethodName();
          var result =
              TenantMembershipServiceGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                  .withInterceptors(
                      io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(
                          TenantRegistryIntegrationTest.signed(sender, batch, policy, batchRpc)))
                  .batchValidateMemberships(batch);
          assertFalse(result.getItems(0).getFound());
          assertEquals("4", result.getItems(1).getMembership().getVersion());
          allowed.set(false);
          assertEquals(
              io.grpc.Status.Code.PERMISSION_DENIED,
              assertThrows(io.grpc.StatusRuntimeException.class, () -> stub.getMembership(request))
                  .getStatus()
                  .getCode());
        }
      }
    } finally {
      for (var channel : channels)
        channel.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  void membershipReadIndexSupportsNaturalKeysetPlanOnPopulatedTable() {
    operator
        .sql(
            "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status,version,joined_at)"
                + " SELECT"
                + " :tenant,md5('member-fixture-'||n)::uuid,'MEMBER','ACTIVE',1,:created+n*interval"
                + " '1 second' FROM generate_series(100,8099) n")
        .bind("tenant", A)
        .bind("created", NOW.atOffset(ZoneOffset.UTC))
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    operator.sql("ANALYZE taska.tenant_memberships").fetch().rowsUpdated().block(WAIT);
    String plan =
        as(
                owner,
                transactions.execute(
                    db ->
                        db.sql(
                                "EXPLAIN (FORMAT JSON) SELECT user_id,role,status,version,joined_at"
                                    + " FROM taska.tenant_memberships WHERE tenant_id=:tenant AND"
                                    + " status IN ('ACTIVE','SUSPENDED') ORDER BY joined_at,user_id"
                                    + " LIMIT 2")
                            .bind("tenant", A)
                            .map(
                                (row, metadata) ->
                                    row.get(0, io.r2dbc.postgresql.codec.Json.class).asString())
                            .one()))
            .block(WAIT);
    assertTrue(plan.contains("tenant_memberships_page_idx"), plan);
    assertEquals(50, list(owner, 0, null).block(WAIT).getItemsCount());
  }

  @Test
  void sharedCursorKeyWorksAcrossReplicaClockSkew() {
    String encoded = cursor.encode("scope", 10, NOW, OWNER);
    var peer = new MembershipCursor(KEY, Clock.fixed(NOW.minusSeconds(2), ZoneOffset.UTC));
    assertEquals(new MembershipCursor.Position(10, NOW, OWNER), peer.decode(encoded, "scope"));
  }
}
