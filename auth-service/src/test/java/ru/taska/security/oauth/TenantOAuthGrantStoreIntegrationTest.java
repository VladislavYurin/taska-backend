package ru.taska.security.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.pat.PersonalTokenProperties;
import ru.taska.security.pat.TenantPersonalTokenStore;
import ru.taska.security.session.WebSessionStore;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL behaviour of TAS-498 approvals, single claim, validation and revocation. */
@Testcontainers
@Timeout(120)
class TenantOAuthGrantStoreIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final UUID USER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
  private static final String CLIENT = "https://clients.example.test/client.json",
      REQUEST = "pending-state_A1~b2.c3=",
      OTHER_REQUEST = "pending-state_Z9";
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
  TenantOAuthGrantStore grants;
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
            "TRUNCATE taska.oauth_grant_approvals,taska.personal_token_issuance_receipts,"
                + "taska.personal_access_tokens,taska.integration_grants,taska.outbox_events,"
                + "taska.session_issuance_receipts,taska.refresh_rotation_receipts,"
                + "taska.refresh_tokens,taska.tenant_sessions,taska.web_sessions,taska.users"
                + " CASCADE")
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql(
            "INSERT INTO taska.users(id,email,display_name,status,global_role)"
                + " VALUES (:id,'oauth-test@example.invalid','OAuth Test','ACTIVE','USER')")
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
    time.set(null);
    grants = new TenantOAuthGrantStore(db, tx, clock);
  }

  Mono<TenantOAuthGrantStore.Approved> approve(
      WebSessionStore.Issued session, UUID tenant, List<String> scopes, String request, UUID key) {
    return grants.approve(USER, session.sessionId(), 2, tenant, CLIENT, scopes, request, key);
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

  Map<String, Object> grant(UUID id) {
    return db.sql("SELECT * FROM taska.integration_grants WHERE id=:id")
        .bind("id", id)
        .fetch()
        .one()
        .block(WAIT);
  }

  @Test
  void approvalFixesSessionTenantAndClaimBindsOnlyThatRequestOnce() {
    var approved =
        approve(sessionA, A, List.of("read", "write"), REQUEST, UUID.randomUUID()).block(WAIT);
    assertEquals(A, approved.tenantId());
    assertEquals(List.of("read", "write"), approved.scopes());
    var row = grant(approved.grantId());
    assertEquals("USER", row.get("kind"));
    assertEquals("ONE_TENANT", row.get("scope_type"));
    assertEquals(A, row.get("tenant_id"));
    assertEquals("MODIFY", row.get("access_level"));
    assertEquals(1L, ((Number) row.get("version")).longValue());
    var created = ((OffsetDateTime) row.get("created_at")).toInstant();
    assertEquals(
        created.plus(TenantOAuthGrantStore.MAX_LIFETIME_DAYS, ChronoUnit.DAYS),
        ((OffsetDateTime) row.get("expires_at")).toInstant());
    // The authorization-server state is kept only as a digest.
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM taska.oauth_grant_approvals WHERE"
                + " position(convert_to('pending-state','UTF8') IN authorization_request_sha256)>0"));

    // Before the claim the grant is not usable by the token endpoint.
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 1, null),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
    rejected(
        grants.claim(approved.grantId(), USER, "https://evil.example/client.json", REQUEST),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
    rejected(
        grants.claim(approved.grantId(), USER, CLIENT, OTHER_REQUEST),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
    rejected(
        grants.claim(approved.grantId(), UUID.randomUUID(), CLIENT, REQUEST),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");

    var claimed = grants.claim(approved.grantId(), USER, CLIENT, REQUEST).block(WAIT);
    assertEquals(A, claimed.tenantId());
    assertEquals(1, claimed.grantVersion());
    assertEquals(List.of("read", "write"), claimed.scopes());
    rejected(
        grants.claim(approved.grantId(), USER, CLIENT, REQUEST),
        DomainStatus.ABORTED,
        "INVALID_STATE");

    var valid = grants.validate(approved.grantId(), USER, CLIENT, 1, A).block(WAIT);
    assertEquals(A, valid.tenantId());
    assertEquals(USER, valid.userId());
    // A token or refresh can never present the grant for company B.
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 1, B),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
    rejected(
        grants.validate(approved.grantId(), USER, "https://evil.example/client.json", 1, null),
        DomainStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND");
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 2, null),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
  }

  @Test
  void readConsentStaysReadAndConsentForBIsASeparateGrant() {
    var readOnly = approve(sessionA, A, List.of("read"), REQUEST, UUID.randomUUID()).block(WAIT);
    assertEquals(List.of("read"), readOnly.scopes());
    assertEquals("READ", grant(readOnly.grantId()).get("access_level"));
    var other = approve(sessionB, B, List.of("read"), OTHER_REQUEST, UUID.randomUUID()).block(WAIT);
    assertEquals(B, other.tenantId());
    assertTrue(!readOnly.grantId().equals(other.grantId()));
    assertEquals(2, count("SELECT count(*) FROM taska.integration_grants"));
  }

  @Test
  void onlyACurrentSessionOfTheSelectedTenantCanApprove() {
    // The B tenant session cannot approve for A, and a stale membership generation is refused.
    rejected(
        approve(sessionB, A, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    rejected(
        grants.approve(
            USER, sessionA.sessionId(), 3, A, CLIENT, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    sessions.revokeTenant(USER, sessionA.sessionId()).block(WAIT);
    rejected(
        approve(sessionA, A, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    db.sql("UPDATE taska.users SET status='BLOCKED' WHERE id=:id")
        .bind("id", USER)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    rejected(
        approve(sessionB, B, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.UNAUTHENTICATED,
        "AUTHENTICATION_REQUIRED");
    assertEquals(0, count("SELECT count(*) FROM taska.integration_grants"));
  }

  @Test
  void idempotentApprovalAndOneTenantPerAuthorizationRequest() {
    UUID key = UUID.randomUUID();
    var first = approve(sessionA, A, List.of("read"), REQUEST, key).block(WAIT);
    var replay = approve(sessionA, A, List.of("read"), REQUEST, key).block(WAIT);
    assertEquals(first.grantId(), replay.grantId());
    rejected(
        approve(sessionA, A, List.of("read", "write"), REQUEST, key),
        DomainStatus.ABORTED,
        "IDEMPOTENCY_CONFLICT");
    // Approving the same request again, even for another company, never adds a second grant.
    rejected(
        approve(sessionA, A, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.ABORTED,
        "INVALID_STATE");
    rejected(
        approve(sessionB, B, List.of("read"), REQUEST, UUID.randomUUID()),
        DomainStatus.ABORTED,
        "INVALID_STATE");
    grants.claim(first.grantId(), USER, CLIENT, REQUEST).block(WAIT);
    rejected(
        approve(sessionA, A, List.of("read"), REQUEST, key),
        DomainStatus.ABORTED,
        "INVALID_STATE");
    assertEquals(1, count("SELECT count(*) FROM taska.integration_grants"));
  }

  @Test
  void concurrentApprovalsOfOneRequestYieldOneGrant() {
    var outcomes =
        Flux.range(0, 6)
            .flatMap(
                n ->
                    approve(
                            n % 2 == 0 ? sessionA : sessionB,
                            n % 2 == 0 ? A : B,
                            List.of("read"),
                            REQUEST,
                            UUID.randomUUID())
                        .map(approved -> "ok")
                        .onErrorResume(
                            DomainException.class,
                            error -> Mono.just(String.valueOf(error.getDetails().get("code"))))
                        .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(1, outcomes.stream().filter("ok"::equals).count(), outcomes.toString());
    assertTrue(outcomes.stream().allMatch(o -> o.equals("ok") || o.equals("INVALID_STATE")));
    assertEquals(1, count("SELECT count(*) FROM taska.integration_grants"));
    assertEquals(1, count("SELECT count(*) FROM taska.oauth_grant_approvals"));
  }

  @Test
  void concurrentClaimsHaveOneWinner() {
    var approved = approve(sessionA, A, List.of("read"), REQUEST, UUID.randomUUID()).block(WAIT);
    var outcomes =
        Flux.range(0, 6)
            .flatMap(
                n ->
                    grants
                        .claim(approved.grantId(), USER, CLIENT, REQUEST)
                        .map(binding -> "ok")
                        .onErrorResume(
                            DomainException.class,
                            error -> Mono.just(String.valueOf(error.getDetails().get("code"))))
                        .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block(WAIT);
    assertEquals(1, outcomes.stream().filter("ok"::equals).count(), outcomes.toString());
  }

  @Test
  void claimWindowRevocationBlockAndExpiryFailClosed() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    time.set(now);
    var late = approve(sessionA, A, List.of("read"), REQUEST, UUID.randomUUID()).block(WAIT);
    time.set(now.plus(TenantOAuthGrantStore.CLAIM_WINDOW).plusSeconds(1));
    rejected(grants.claim(late.grantId(), USER, CLIENT, REQUEST), DomainStatus.ABORTED, "INVALID_STATE");

    time.set(now);
    var approved =
        approve(sessionA, A, List.of("read"), OTHER_REQUEST, UUID.randomUUID()).block(WAIT);
    grants.claim(approved.grantId(), USER, CLIENT, OTHER_REQUEST).block(WAIT);
    grants.validate(approved.grantId(), USER, CLIENT, 1, A).block(WAIT);

    // Any change to the grant bumps its generation; the authorization keeps the old one.
    update("UPDATE taska.integration_grants SET version=version+1 WHERE id=:id", approved.grantId());
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 1, A),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    grants.validate(approved.grantId(), USER, CLIENT, 2, A).block(WAIT);

    update("UPDATE taska.users SET status='BLOCKED' WHERE id=:id", USER);
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 2, A),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
    update("UPDATE taska.users SET status='ACTIVE' WHERE id=:id", USER);
    time.set(now.plus(TenantOAuthGrantStore.MAX_LIFETIME_DAYS, ChronoUnit.DAYS));
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 2, A),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");

    // Revocation (account-wide, or TAS-323 membership removal) is final.
    time.set(now);
    update("UPDATE taska.integration_grants SET revoked_at=now() WHERE id=:id", approved.grantId());
    rejected(
        grants.validate(approved.grantId(), USER, CLIENT, 2, A),
        DomainStatus.UNAUTHENTICATED,
        "TOKEN_REVOKED");
  }

  void update(String sql, UUID id) {
    db.sql(sql).bind("id", id).fetch().rowsUpdated().block(WAIT);
  }

  @Test
  void personalTokenGrantIsNeverAnOAuthGrant() {
    var pat =
        new TenantPersonalTokenStore(
                db,
                tx,
                clock,
                new SecureRandom(),
                new PersonalTokenProperties(),
                receiptKey,
                new JsonMapper())
            .issue(USER, sessionA.sessionId(), A, "CI", List.of("read"), 30, UUID.randomUUID(), "r")
            .block(WAIT);
    UUID patGrant =
        db.sql("SELECT integration_grant_id FROM taska.personal_access_tokens WHERE id=:id")
            .bind("id", pat.tokenId())
            .map((r, m) -> r.get(0, UUID.class))
            .one()
            .block(WAIT);
    rejected(grants.claim(patGrant, USER, CLIENT, REQUEST), DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    rejected(
        grants.validate(patGrant, USER, CLIENT, 1, A), DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
  }

  @Test
  void malformedConsentIsRejectedBeforeAnyWrite() {
    for (var scopes : List.of(List.<String>of(), List.of("write"), List.of("read", "read"), List.of("admin")))
      rejected(
          approve(sessionA, A, scopes, REQUEST, UUID.randomUUID()),
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED");
    for (String request : List.of("", "has space", "a/b", "x".repeat(257)))
      rejected(
          approve(sessionA, A, List.of("read"), request, UUID.randomUUID()),
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED");
    for (String client : List.of("", " " + CLIENT, "c".repeat(2049)))
      rejected(
          grants.approve(
              USER, sessionA.sessionId(), 2, A, client, List.of("read"), REQUEST, UUID.randomUUID()),
          DomainStatus.INVALID_ARGUMENT,
          "VALIDATION_FAILED");
    assertEquals(0, count("SELECT count(*) FROM taska.integration_grants"));
    assertNull(
        db.sql("SELECT grant_id FROM taska.oauth_grant_approvals")
            .map((r, m) -> r.get(0, UUID.class))
            .one()
            .block(WAIT));
  }
}
