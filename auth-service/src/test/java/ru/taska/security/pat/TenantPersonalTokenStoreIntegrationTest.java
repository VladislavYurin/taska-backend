package ru.taska.security.pat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.session.WebSessionStore;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL behaviour of TAS-463 grants, issuance receipts, binding and revocation. */
@Testcontainers
@Timeout(120)
class TenantPersonalTokenStoreIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final UUID USER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
  @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
  static ConnectionFactory factory;
  static DatabaseClient db;
  static TransactionalOperator tx;
  final AtomicReference<Instant> time = new AtomicReference<>();
  final Clock clock =
      new Clock() {
        public Instant instant() {
          return time.get() == null ? Instant.now() : time.get();
        }

        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }
      };
  WebSessionStore sessions;
  TenantPersonalTokenStore tokens;
  PersonalTokenProperties policy;
  byte[] receiptKey;
  WebSessionStore.Issued sessionA, sessionB;

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
    factory =
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
            "TRUNCATE taska.personal_token_issuance_receipts,taska.personal_access_tokens,"
                + "taska.integration_grants,taska.outbox_events,taska.session_issuance_receipts,"
                + "taska.refresh_rotation_receipts,taska.refresh_tokens,taska.tenant_sessions,"
                + "taska.web_sessions,taska.users CASCADE")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql(
            "INSERT INTO taska.users(id,email,display_name,status,global_role)"
                + " VALUES (:id,'pat-test@example.invalid','Pat Test','ACTIVE','USER')")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    receiptKey = new byte[32];
    new SecureRandom().nextBytes(receiptKey);
    sessions =
        new WebSessionStore(
            db,
            tx,
            Clock.systemUTC(),
            new SecureRandom(),
            Duration.ofHours(8),
            (tenant, user) -> USER.equals(user) ? Mono.just(2L) : Mono.empty(),
            receiptKey);
    var global = sessions.openGlobal(USER, "PASSWORD").block(WAIT);
    sessionA = sessions.openTenant(USER, global.webSessionId(), A).block(WAIT);
    sessionB = sessions.openTenant(USER, global.webSessionId(), B).block(WAIT);
    policy = new PersonalTokenProperties();
    time.set(null);
    tokens = store();
  }

  TenantPersonalTokenStore store() {
    return new TenantPersonalTokenStore(
        db, tx, clock, new SecureRandom(), policy, receiptKey, new JsonMapper());
  }

  Mono<TenantPersonalTokenStore.Issued> issue(
      WebSessionStore.Issued session, UUID tenant, String name, List<String> scopes, UUID key) {
    return tokens.issue(USER, session.sessionId(), tenant, name, scopes, 30, key, "req-1");
  }

  static void rejected(Mono<?> operation, DomainStatus status, String code) {
    StepVerifier.create(operation)
        .expectErrorMatches(
            error ->
                error instanceof DomainException domain
                    && domain.getStatus() == status
                    && code.equals(domain.getDetails().get("code")))
        .verify(WAIT);
  }

  long count(String sql) {
    return db.sql(sql).map((r, m) -> r.get(0, Long.class)).one().block(WAIT);
  }

  Map<String, Object> grant(UUID token) {
    return db.sql(
            "SELECT g.* FROM taska.integration_grants g JOIN taska.personal_access_tokens p ON"
                + " p.integration_grant_id=g.id WHERE p.id=:token")
        .bind("token", token)
        .fetch()
        .one()
        .block(WAIT);
  }

  @Test
  void issuanceFixesSessionTenantAndAuthenticationReturnsThatBindingOnly() {
    var issued = issue(sessionA, A, " CI bot ", List.of("write"), UUID.randomUUID()).block(WAIT);
    assertEquals(A, issued.tenantId());
    assertEquals("CI bot", issued.name());
    assertEquals(List.of("read", "write"), issued.scopes());
    assertTrue(issued.secret().startsWith(PersonalAccessTokenService.PREFIX));

    var grant = grant(issued.tokenId());
    assertEquals("USER", grant.get("kind"));
    assertEquals("ONE_TENANT", grant.get("scope_type"));
    assertEquals(A, grant.get("tenant_id"));
    assertEquals("MODIFY", grant.get("access_level"));
    assertEquals(1L, ((Number) grant.get("version")).longValue());
    assertEquals(
        DigestUtils.sha256Hex(issued.secret()),
        db.sql("SELECT token_hash FROM taska.personal_access_tokens WHERE id=:id")
            .bind("id", issued.tokenId())
            .map((r, m) -> r.get(0, String.class))
            .one()
            .block(WAIT));
    String event =
        db.sql("SELECT payload::text FROM taska.outbox_events WHERE event_type=:type")
            .bind("type", ru.taska.event.EventType.PERSONAL_ACCESS_TOKEN_CREATED.getValue())
            .map((r, m) -> r.get(0, String.class))
            .one()
            .block(WAIT);
    assertTrue(event.contains(A.toString()));
    assertFalse(event.contains(issued.secret()));
    assertFalse(event.contains(DigestUtils.sha256Hex(issued.secret())));

    // GLOBAL_ADMIN on the account never turns the ordinary grant into an administrative one.
    db.sql("UPDATE taska.users SET global_role='GLOBAL_ADMIN' WHERE id=:id")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var binding = tokens.authenticate(issued.secret()).block(WAIT);
    assertEquals(issued.tokenId(), binding.tokenId());
    assertEquals(A, binding.tenantId());
    assertEquals(USER, binding.userId());
    assertEquals(1L, binding.grantVersion());
    assertEquals(List.of("read", "write"), binding.scopes());
    assertEquals(issued.expiresAt(), binding.expiresAt());
    assertEquals("USER", grant(issued.tokenId()).get("kind"));
  }

  @Test
  void tenantSessionCannotIssueForAnotherTenantOrAfterRevocation() {
    rejected(
        issue(sessionA, B, "cross", List.of("read"), UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    sessions.revokeTenant(USER, sessionA.sessionId()).block(WAIT);
    rejected(
        issue(sessionA, A, "revoked", List.of("read"), UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    db.sql("UPDATE taska.users SET auth_version=auth_version+1 WHERE id=:id")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejected(
        issue(sessionB, B, "old generation", List.of("read"), UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    assertEquals(0L, count("SELECT count(*) FROM taska.integration_grants"));
    assertEquals(0L, count("SELECT count(*) FROM taska.personal_access_tokens"));
  }

  @Test
  void sameKeyReplaysOneSecretThenExpiresWhileDifferentRequestConflicts() {
    UUID key = UUID.randomUUID();
    var responses =
        Flux.merge(
                issue(sessionA, A, "replay", List.of("read"), key)
                    .subscribeOn(Schedulers.parallel()),
                issue(sessionA, A, "replay", List.of("read"), key)
                    .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(2, responses.size());
    assertEquals(responses.get(0), responses.get(1));
    assertEquals(1L, count("SELECT count(*) FROM taska.personal_access_tokens"));
    assertEquals(responses.get(0), store().issue(USER, sessionA.sessionId(), A, "replay",
        List.of("read"), 30, key, "retry").block(WAIT));
    rejected(
        issue(sessionA, A, "other name", List.of("read"), key),
        DomainStatus.ABORTED,
        "IDEMPOTENCY_CONFLICT");
    time.set(Instant.now().plusSeconds(61));
    rejected(
        issue(sessionA, A, "replay", List.of("read"), key), DomainStatus.ABORTED, "INVALID_STATE");
    assertEquals(1L, count("SELECT count(*) FROM taska.personal_access_tokens"));
  }

  @Test
  void activeLimitCountsLegacyAndTenantTokensUnderAccountLock() {
    policy.setMaxActiveTokens(2);
    db.sql(
            "INSERT INTO taska.personal_access_tokens(user_id,name,token_prefix,token_hash,scopes,"
                + "created_at,expires_at) VALUES (:user,'legacy','tsk_pat_abcd',:hash,"
                + "ARRAY['read'],now(),now()+interval '1 day')")
        .bind("user", USER)
        .bind("hash", "a".repeat(64))
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    issue(sessionA, A, "first", List.of("read"), UUID.randomUUID()).block(WAIT);
    rejected(
        issue(sessionB, B, "second", List.of("read"), UUID.randomUUID()),
        DomainStatus.FAILED_PRECONDITION,
        "QUOTA_EXCEEDED");
    assertEquals(1L, count("SELECT count(*) FROM taska.integration_grants"));
  }

  @Test
  void concurrentIssuanceWithDifferentKeysCannotExceedActiveLimit() {
    policy.setMaxActiveTokens(1);
    var results =
        Flux.merge(
                issue(sessionA, A, "first", List.of("read"), UUID.randomUUID())
                    .map(issued -> "OK")
                    .onErrorResume(DomainException.class, e -> Mono.just(e.getDetails().get("code")))
                    .subscribeOn(Schedulers.parallel()),
                issue(sessionB, B, "second", List.of("read"), UUID.randomUUID())
                    .map(issued -> "OK")
                    .onErrorResume(DomainException.class, e -> Mono.just(e.getDetails().get("code")))
                    .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(Set.of("OK", "QUOTA_EXCEEDED"), Set.copyOf(results));
    assertEquals(1L, count("SELECT count(*) FROM taska.personal_access_tokens"));
    assertEquals(1L, count("SELECT count(*) FROM taska.integration_grants"));
  }

  @Test
  void invalidRequestsWriteNothing() {
    for (var scopes : List.of(List.<String>of(), List.of("admin"), List.of("read", "read")))
      rejected(
          issue(sessionA, A, "scopes", scopes, UUID.randomUUID()),
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED");
    rejected(
        issue(sessionA, A, "  ", List.of("read"), UUID.randomUUID()),
        DomainStatus.INVALID_ARGUMENT,
        "VALIDATION_FAILED");
    for (long days : new long[] {0, 91})
      rejected(
          tokens.issue(USER, sessionA.sessionId(), A, "days", List.of("read"), days,
              UUID.randomUUID(), "req"),
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED");
    assertEquals(0L, count("SELECT count(*) FROM taska.integration_grants"));
    assertEquals(0L, count("SELECT count(*) FROM taska.outbox_events"));
  }

  @Test
  void revocationExpiryAndAccountStateStopAuthentication() {
    var repository =
        new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory))
            .getRepository(PersonalAccessTokenRepository.class);
    var revoked = issue(sessionA, A, "revoked", List.of("read"), UUID.randomUUID()).block(WAIT);
    var expiring = issue(sessionB, B, "expiring", List.of("read"), UUID.randomUUID()).block(WAIT);
    var blocked = issue(sessionA, A, "blocked", List.of("read"), UUID.randomUUID()).block(WAIT);

    var row =
        repository.revokeOwned(revoked.tokenId(), USER, Instant.now(), "OWNER_REVOKED").block(WAIT);
    assertEquals(revoked.tokenId(), row.getId());
    var grant = grant(revoked.tokenId());
    assertEquals(2L, ((Number) grant.get("version")).longValue());
    assertTrue(grant.get("revoked_at") != null);
    rejected(tokens.authenticate(revoked.secret()), DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED");

    time.set(expiring.expiresAt());
    rejected(tokens.authenticate(expiring.secret()), DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED");
    time.set(null);

    assertEquals(A, tokens.authenticate(blocked.secret()).block(WAIT).tenantId());
    db.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejected(tokens.authenticate(blocked.secret()), DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED");

    repository.revokeAll(USER, Instant.now(), "ACCOUNT_BLOCKED").collectList().block(WAIT);
    assertEquals(
        0L, count("SELECT count(*) FROM taska.integration_grants WHERE revoked_at IS NULL"));
  }

  @Test
  void legacyAndMalformedSecretsAreNotTenantCredentials() {
    String legacy = PersonalAccessTokenService.PREFIX + "L".repeat(43);
    db.sql(
            "INSERT INTO taska.personal_access_tokens(user_id,name,token_prefix,token_hash,scopes,"
                + "created_at,expires_at) VALUES (:user,'legacy','tsk_pat_LLLL',:hash,"
                + "ARRAY['read','write'],now(),now()+interval '1 day')")
        .bind("user", USER)
        .bind("hash", DigestUtils.sha256Hex(legacy))
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    for (String secret :
        new String[] {legacy, PersonalAccessTokenService.PREFIX + "x".repeat(43), "junk", null})
      rejected(tokens.authenticate(secret), DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED");
    assertEquals(
        0L,
        count("SELECT count(*) FROM taska.personal_access_tokens WHERE last_used_at IS NOT NULL"));
  }

  @Test
  void authenticationTouchesUsageAtMostOncePerWindow() {
    var issued = issue(sessionA, A, "touch", List.of("read"), UUID.randomUUID()).block(WAIT);
    Instant start = Instant.now();
    time.set(start);
    tokens.authenticate(issued.secret()).block(WAIT);
    var first = lastUsed(issued.tokenId());
    time.set(start.plusSeconds(60));
    tokens.authenticate(issued.secret()).block(WAIT);
    assertEquals(first, lastUsed(issued.tokenId()));
    time.set(start.plusSeconds(301));
    tokens.authenticate(issued.secret()).block(WAIT);
    assertNotEquals(first, lastUsed(issued.tokenId()));
    assertEquals(Set.of(A), Set.of(tokens.authenticate(issued.secret()).block(WAIT).tenantId()));
  }

  Instant lastUsed(UUID token) {
    return db.sql("SELECT last_used_at FROM taska.personal_access_tokens WHERE id=:id")
        .bind("id", token)
        .map((r, m) -> r.get(0, java.time.OffsetDateTime.class).toInstant())
        .one()
        .block(WAIT);
  }
}
