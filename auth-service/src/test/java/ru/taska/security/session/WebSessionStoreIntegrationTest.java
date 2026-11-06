package ru.taska.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

@Testcontainers
@Timeout(90)
class WebSessionStoreIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final UUID USER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
  @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
  static DatabaseClient db;
  static TransactionalOperator tx;
  WebSessionStore store;
  Set<UUID> active;
  AtomicLong aGeneration;
  byte[] receiptKey;

  @BeforeAll
  static void setup() throws Exception {
    try (var jdbc =
        DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      new liquibase.Liquibase(
              "db/changelog/db.changelog-master.yaml",
              new liquibase.resource.ClassLoaderResourceAccessor(),
              new liquibase.database.jvm.JdbcConnection(jdbc))
          .update("");
    }
    var factory =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, PG.getHost())
                .option(ConnectionFactoryOptions.PORT, PG.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, PG.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, PG.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, PG.getPassword())
                .build());
    db = DatabaseClient.create(factory);
    tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
  }

  @BeforeEach
  void seed() {
    db.sql(
            "TRUNCATE"
                + " taska.session_issuance_receipts,taska.refresh_rotation_receipts,taska.refresh_tokens,taska.tenant_sessions,taska.web_sessions,taska.users"
                + " CASCADE")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql(
            "INSERT INTO taska.users(id,email,display_name,status,global_role)"
                + " VALUES (:id,'session-test@example.invalid','Session Test','ACTIVE','USER')")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    active = new HashSet<>(Set.of(A, B));
    aGeneration = new AtomicLong(3);
    receiptKey = new byte[32];
    new SecureRandom().nextBytes(receiptKey);
    store = createStore();
  }

  WebSessionStore createStore() {
    return new WebSessionStore(
        db,
        tx,
        Clock.systemUTC(),
        new SecureRandom(),
        Duration.ofHours(8),
        (tenant, user) ->
            active.contains(tenant) && USER.equals(user)
                ? Mono.just(tenant.equals(A) ? aGeneration.get() : 9L)
                : Mono.empty(),
        receiptKey);
  }

  private static void rejected(Mono<?> operation) {
    StepVerifier.create(operation).expectError(DomainException.class).verify(WAIT);
  }

  @Test
  void persistedIssuanceAndRotationReceiptsSurviveStoreRestart() {
    UUID loginKey = UUID.randomUUID(),
        selectionKey = UUID.randomUUID(),
        rotationKey = UUID.randomUUID();
    var global = store.openGlobal(USER, "PASSWORD", loginKey).block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A, selectionKey).block(WAIT);
    var rotated = store.rotate(a.refreshToken(), rotationKey).block(WAIT);
    var restarted = createStore();
    assertEquals(global, restarted.openGlobal(USER, "PASSWORD", loginKey).block(WAIT));
    assertEquals(a, restarted.openTenant(USER, global.webSessionId(), A, selectionKey).block(WAIT));
    assertEquals(rotated, restarted.rotate(a.refreshToken(), rotationKey).block(WAIT));
  }

  @Test
  void concurrentRotationWithSameKeyReturnsOneSuccessor() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    UUID key = UUID.randomUUID();
    var responses =
        reactor.core.publisher.Flux.merge(
                store
                    .rotate(global.refreshToken(), key)
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                store
                    .rotate(global.refreshToken(), key)
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(2, responses.size());
    assertEquals(responses.get(0), responses.get(1));
    assertEquals(
        2L,
        db.sql("SELECT count(*) AS n FROM taska.refresh_tokens WHERE family_id=:family")
            .bind("family", global.familyId())
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertEquals(
        1L,
        db.sql(
                "SELECT count(*) AS n FROM taska.refresh_tokens"
                    + " WHERE family_id=:family AND revoked_at IS NULL")
            .bind("family", global.familyId())
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void twoTenantsRotateAndRevokeIndependently() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    var b = store.openTenant(USER, global.webSessionId(), B).block(WAIT);
    assertNotEquals(a.sessionId(), b.sessionId());
    assertNotEquals(a.familyId(), b.familyId());
    assertEquals(A, a.tenantId());
    assertEquals(B, b.tenantId());
    UUID key = UUID.randomUUID();
    var rotatedA = store.rotate(a.refreshToken(), key).block(WAIT);
    assertEquals(A, rotatedA.tenantId());
    assertEquals(a.sessionId(), rotatedA.sessionId());
    assertEquals(rotatedA, store.rotate(a.refreshToken(), key).block(WAIT));
    rejected(store.rotate(a.refreshToken(), UUID.randomUUID()));
    rejected(store.rotate(rotatedA.refreshToken(), UUID.randomUUID()));
    assertEquals(B, store.rotate(b.refreshToken(), UUID.randomUUID()).block(WAIT).tenantId());
  }

  @Test
  void inactiveMembershipAndBlockedAccountCannotMintSession() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    active.remove(A);
    rejected(store.openTenant(USER, global.webSessionId(), A));
    rejected(store.rotate(a.refreshToken(), UUID.randomUUID()));
    db.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejected(store.openGlobal(USER, "PASSWORD"));
    rejected(store.rotate(global.refreshToken(), UUID.randomUUID()));
  }

  @Test
  void changedMembershipGenerationRejectsOldTenantRefreshWithoutConsumingIt() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    aGeneration.incrementAndGet();
    rejected(store.rotate(a.refreshToken(), UUID.randomUUID()));
    assertEquals(
        0L,
        db.sql(
                "SELECT count(*) AS n FROM taska.refresh_tokens"
                    + " WHERE family_id=:family AND revoked_at IS NOT NULL")
            .bind("family", a.familyId())
            .fetch()
            .one()
            .map(row -> ((Number) row.get("n")).longValue())
            .block(WAIT));
    var fresh = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    assertEquals(
        4L, store.rotate(fresh.refreshToken(), UUID.randomUUID()).block(WAIT).membershipVersion());
  }

  @Test
  void inactiveMembershipAndAuthorityOutageKeepTheirDistinctErrorsAndPreserveRefresh() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    for (var status : java.util.List.of(DomainStatus.PERMISSION_DENIED, DomainStatus.UNAVAILABLE)) {
      var authority =
          new WebSessionStore(
              db,
              tx,
              Clock.systemUTC(),
              new SecureRandom(),
              Duration.ofHours(8),
              (t, u) ->
                  Mono.error(
                      new DomainException(
                          status,
                          status == DomainStatus.PERMISSION_DENIED
                              ? "MEMBERSHIP_INACTIVE"
                              : "Authority unavailable")),
              receiptKey);
      StepVerifier.create(authority.openTenant(USER, global.webSessionId(), A))
          .expectErrorMatches(e -> e instanceof DomainException d && d.getStatus() == status)
          .verify(WAIT);
      StepVerifier.create(authority.rotate(a.refreshToken(), UUID.randomUUID()))
          .expectErrorMatches(e -> e instanceof DomainException d && d.getStatus() == status)
          .verify(WAIT);
    }
    assertNotNull(store.rotate(a.refreshToken(), UUID.randomUUID()).block(WAIT));
  }

  @Test
  void aRotationReceiptCannotUpgradeTheOriginalMembershipBinding() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    UUID key = UUID.randomUUID();
    var rotated = store.rotate(a.refreshToken(), key).block(WAIT);
    aGeneration.incrementAndGet();
    StepVerifier.create(store.rotate(a.refreshToken(), key))
        .expectErrorMatches(
            e ->
                e instanceof DomainException d
                    && d.getStatus() == DomainStatus.UNAUTHENTICATED
                    && d.getMessage().contains("TOKEN_REVOKED"))
        .verify(WAIT);
    rejected(store.rotate(rotated.refreshToken(), UUID.randomUUID()));
    assertEquals(
        2L,
        db.sql("SELECT count(*) AS n FROM taska.refresh_tokens WHERE family_id=:family")
            .bind("family", a.familyId())
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    var fresh = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    assertEquals(4L, fresh.membershipVersion());
  }

  @Test
  void logoutRevokesParentAndChildrenButPreservesAnotherLogin() {
    var first = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var childA = store.openTenant(USER, first.webSessionId(), A).block(WAIT);
    var childB = store.openTenant(USER, first.webSessionId(), B).block(WAIT);
    var second = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var secondChild = store.openTenant(USER, second.webSessionId(), A).block(WAIT);
    store.revokeWeb(USER, first.webSessionId()).block(WAIT);
    store.revokeWeb(USER, first.webSessionId()).block(WAIT);
    rejected(store.rotate(first.refreshToken(), UUID.randomUUID()));
    rejected(store.rotate(childA.refreshToken(), UUID.randomUUID()));
    rejected(store.rotate(childB.refreshToken(), UUID.randomUUID()));
    assertNotNull(store.rotate(second.refreshToken(), UUID.randomUUID()).block(WAIT));
    assertNotNull(store.rotate(secondChild.refreshToken(), UUID.randomUUID()).block(WAIT));
    assertEquals(1L, second.authVersion());
    assertEquals(
        1L,
        db.sql("SELECT auth_version AS n FROM taska.users WHERE id=:user")
            .bind("user", USER)
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void unknownSessionsAndTenantSelectionsAreNotAuthenticationFailures() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    for (var operation :
        java.util.List.of(
            store.revokeTenant(USER, UUID.randomUUID()),
            store.revokeWeb(USER, UUID.randomUUID()),
            store.revokeWeb(UUID.randomUUID(), global.webSessionId()),
            store.openTenant(USER, global.webSessionId(), UUID.randomUUID()))) {
      StepVerifier.create(operation)
          .expectErrorMatches(
              e -> e instanceof DomainException d && d.getStatus() == DomainStatus.NOT_FOUND)
          .verify(WAIT);
    }
    assertNotNull(store.rotate(global.refreshToken(), UUID.randomUUID()).block(WAIT));
  }

  @Test
  void revokeOneTenantKeepsOtherAndRevokeAllEndsBoth() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    var b = store.openTenant(USER, global.webSessionId(), B).block(WAIT);
    store.revokeTenant(USER, a.sessionId()).block(WAIT);
    store.revokeTenant(USER, a.sessionId()).block(WAIT);
    rejected(store.rotate(a.refreshToken(), UUID.randomUUID()));
    assertNotNull(store.rotate(b.refreshToken(), UUID.randomUUID()).block(WAIT));
    store.revokeAll(USER).block(WAIT);
    rejected(store.rotate(global.refreshToken(), UUID.randomUUID()));
    rejected(store.rotate(b.refreshToken(), UUID.randomUUID()));
  }

  @Test
  void accountGenerationChangeInvalidatesEveryOlderSession() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var tenant = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    db.sql("UPDATE taska.users SET auth_version=auth_version+1 WHERE id=:user")
        .bind("user", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejected(store.rotate(global.refreshToken(), UUID.randomUUID()));
    rejected(store.rotate(tenant.refreshToken(), UUID.randomUUID()));
    rejected(store.openTenant(USER, global.webSessionId(), A));
    assertEquals(2L, store.openGlobal(USER, "PASSWORD").block(WAIT).authVersion());
  }

  @Test
  void issuanceRetryReturnsSameSecretAndKeyCannotSelectAnotherTenant() {
    UUID globalKey = UUID.randomUUID(), tenantKey = UUID.randomUUID();
    var first = store.openGlobal(USER, "PASSWORD", globalKey).block(WAIT);
    var retry = store.openGlobal(USER, "PASSWORD", globalKey).block(WAIT);
    assertEquals(first, retry);
    var a = store.openTenant(USER, first.webSessionId(), A, tenantKey).block(WAIT);
    assertEquals(a, store.openTenant(USER, first.webSessionId(), A, tenantKey).block(WAIT));
    rejected(store.openTenant(USER, first.webSessionId(), B, tenantKey));
    store.revokeTenant(USER, a.sessionId()).block(WAIT);
    rejected(store.openTenant(USER, first.webSessionId(), A, tenantKey));
  }

  @Test
  void expiredReceiptsEraseSecretsWithoutRevokingValidFamily() {
    UUID issueKey = UUID.randomUUID(), rotateKey = UUID.randomUUID();
    var initial = store.openGlobal(USER, "PASSWORD", issueKey).block(WAIT);
    var next = store.rotate(initial.refreshToken(), rotateKey).block(WAIT);
    db.sql(
            "UPDATE taska.session_issuance_receipts SET expires_at=created_at+interval '1"
                + " microsecond'")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql(
            "UPDATE taska.refresh_rotation_receipts SET expires_at=created_at+interval '1"
                + " microsecond'")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    new SessionReceiptCleanup(db).purge().block(WAIT);
    assertEquals(
        0L,
        db.sql(
                "SELECT count(*) AS n FROM taska.session_issuance_receipts"
                    + " WHERE ciphertext IS NOT NULL")
            .fetch()
            .one()
            .map(row -> ((Number) row.get("n")).longValue())
            .block(WAIT));
    assertEquals(
        0L,
        db.sql(
                "SELECT count(*) AS n FROM taska.refresh_rotation_receipts"
                    + " WHERE ciphertext IS NOT NULL")
            .fetch()
            .one()
            .map(row -> ((Number) row.get("n")).longValue())
            .block(WAIT));
    StepVerifier.create(store.openGlobal(USER, "PASSWORD", issueKey))
        .expectErrorMatches(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.ABORTED)
        .verify(WAIT);
    StepVerifier.create(store.rotate(initial.refreshToken(), rotateKey))
        .expectErrorMatches(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == DomainStatus.ABORTED)
        .verify(WAIT);
    assertNotNull(store.rotate(next.refreshToken(), UUID.randomUUID()).block(WAIT));
  }
}
