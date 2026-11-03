package ru.taska.security.session;

import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.spi.*;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
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

  @BeforeAll
  static void setup() throws Exception {
    try (var jdbc = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
      new liquibase.Liquibase("db/changelog/db.changelog-master.yaml",
          new liquibase.resource.ClassLoaderResourceAccessor(),
          new liquibase.database.jvm.JdbcConnection(jdbc)).update("");
    }
    var factory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, PG.getHost())
        .option(ConnectionFactoryOptions.PORT, PG.getMappedPort(5432))
        .option(ConnectionFactoryOptions.DATABASE, PG.getDatabaseName())
        .option(ConnectionFactoryOptions.USER, PG.getUsername())
        .option(ConnectionFactoryOptions.PASSWORD, PG.getPassword()).build());
    db = DatabaseClient.create(factory);
    tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
  }

  @BeforeEach
  void seed() {
    db.sql("TRUNCATE taska.session_issuance_receipts,taska.refresh_rotation_receipts,taska.refresh_tokens,"
        + "taska.tenant_sessions,taska.web_sessions,taska.users CASCADE")
        .fetch().rowsUpdated().block(WAIT);
    db.sql("INSERT INTO taska.users(id,email,display_name,status,global_role)"
        + " VALUES (:id,'session-test@example.invalid','Session Test','ACTIVE','USER')")
        .bind("id", USER).fetch().rowsUpdated().block(WAIT);
    active = new HashSet<>(Set.of(A, B));
    aGeneration = new AtomicLong(3);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    store = new WebSessionStore(db, tx, Clock.systemUTC(), new SecureRandom(),
        Duration.ofHours(8), (tenant, user) -> active.contains(tenant) && USER.equals(user)
            ? Mono.just(tenant.equals(A) ? aGeneration.get() : 9L) : Mono.empty(), key);
  }

  private static void rejected(Mono<?> operation) {
    StepVerifier.create(operation).expectError(DomainException.class).verify(WAIT);
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
        .bind("id", USER).fetch().rowsUpdated().block(WAIT);
    rejected(store.openGlobal(USER, "PASSWORD"));
    rejected(store.rotate(global.refreshToken(), UUID.randomUUID()));
  }

  @Test
  void changedMembershipGenerationRejectsOldTenantRefreshWithoutConsumingIt() {
    var global = store.openGlobal(USER, "PASSWORD").block(WAIT);
    var a = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    aGeneration.incrementAndGet();
    rejected(store.rotate(a.refreshToken(), UUID.randomUUID()));
    assertEquals(0L, db.sql("SELECT count(*) AS n FROM taska.refresh_tokens"
        + " WHERE family_id=:family AND revoked_at IS NOT NULL")
        .bind("family", a.familyId()).fetch().one()
        .map(row -> ((Number) row.get("n")).longValue()).block(WAIT));
    var fresh = store.openTenant(USER, global.webSessionId(), A).block(WAIT);
    assertEquals(4L, store.rotate(fresh.refreshToken(), UUID.randomUUID())
        .block(WAIT).membershipVersion());
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
        .bind("user", USER).fetch().rowsUpdated().block(WAIT);
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
    db.sql("UPDATE taska.session_issuance_receipts SET expires_at=created_at+interval '1 microsecond'")
        .fetch().rowsUpdated().block(WAIT);
    db.sql("UPDATE taska.refresh_rotation_receipts SET expires_at=created_at+interval '1 microsecond'")
        .fetch().rowsUpdated().block(WAIT);
    new SessionReceiptCleanup(db).purge().block(WAIT);
    assertEquals(0L, db.sql("SELECT count(*) AS n FROM taska.session_issuance_receipts"
        + " WHERE ciphertext IS NOT NULL")
        .fetch().one().map(row -> ((Number) row.get("n")).longValue()).block(WAIT));
    assertEquals(0L, db.sql("SELECT count(*) AS n FROM taska.refresh_rotation_receipts"
        + " WHERE ciphertext IS NOT NULL")
        .fetch().one().map(row -> ((Number) row.get("n")).longValue()).block(WAIT));
    StepVerifier.create(store.openGlobal(USER, "PASSWORD", issueKey))
        .expectErrorMatches(error -> error instanceof DomainException domain
            && domain.getStatus() == DomainStatus.ABORTED).verify(WAIT);
    StepVerifier.create(store.rotate(initial.refreshToken(), rotateKey))
        .expectErrorMatches(error -> error instanceof DomainException domain
            && domain.getStatus() == DomainStatus.ABORTED).verify(WAIT);
    assertNotNull(store.rotate(next.refreshToken(), UUID.randomUUID()).block(WAIT));
  }
}
