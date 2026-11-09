package ru.taska.security.gateway;

import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.spi.*;
import java.security.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.repository.UserRepository;
import ru.taska.security.pat.*;
import ru.taska.security.pat.PersonalTokenProperties;
import ru.taska.security.session.*;

@Testcontainers
@Timeout(90)
class GatewayCredentialResolverIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static DatabaseClient db;
  static ConnectionFactory factory;
  static UserRepository users;
  static TransactionalOperator tx;
  final UUID user = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
  WebSessionStore sessions;
  TenantAccessIssuer issuer;
  GatewayCredentialResolver resolver;
  KeyPair pair;
  WebSessionStore.Issued global, sessionA, sessionB;
  volatile boolean offline;
  static final Duration WAIT = Duration.ofSeconds(15);

  @BeforeAll
  static void setup() throws Exception {
    try (var jdbc =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
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
                .option(ConnectionFactoryOptions.HOST, pg.getHost())
                .option(ConnectionFactoryOptions.PORT, pg.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, pg.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, pg.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, pg.getPassword())
                .build());
    db = DatabaseClient.create(factory);
    tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
    users =
        new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory))
            .getRepository(UserRepository.class);
    sql(
        "CREATE TABLE fixture_memberships(tenant_id uuid,user_id uuid,generation bigint,active"
            + " boolean,PRIMARY KEY(tenant_id,user_id))");
  }

  static void sql(String s) {
    db.sql(s).fetch().rowsUpdated().block(WAIT);
  }

  @BeforeEach
  void seed() throws Exception {
    sql("TRUNCATE taska.users,fixture_memberships CASCADE");
    db.sql(
            "INSERT INTO"
                + " taska.users(id,email,display_name,status,global_role)VALUES(:id,'credential@example.invalid','Credential','ACTIVE','USER')")
        .bind("id", user)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    for (UUID company : List.of(a, b))
      db.sql("INSERT INTO fixture_memberships VALUES(:tenant,:user,2,true)")
          .bind("tenant", company)
          .bind("user", user)
          .fetch()
          .rowsUpdated()
          .block(WAIT);
    WebSessionStore.MembershipAuthority membership =
        (tenant, actor) ->
            Mono.defer(
                () ->
                    offline
                        ? Mono.error(new IllegalStateException("offline"))
                        : db.sql(
                                "SELECT generation FROM fixture_memberships WHERE tenant_id=:tenant"
                                    + " AND user_id=:user AND active")
                            .bind("tenant", tenant)
                            .bind("user", actor)
                            .map((r, m) -> r.get("generation", Long.class))
                            .one());
    sessions =
        new WebSessionStore(
            db,
            tx,
            Clock.systemUTC(),
            new SecureRandom(),
            Duration.ofHours(8),
            membership,
            new byte[32]);
    pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    issuer =
        new TenantAccessIssuer(
            pair.getPrivate(), "test-key", Clock.systemUTC(), Duration.ofMinutes(15));
    var tokens =
        new TenantPersonalTokenStore(
            db,
            tx,
            Clock.systemUTC(),
            new SecureRandom(),
            new PersonalTokenProperties(),
            new byte[32],
            new tools.jackson.databind.ObjectMapper());
    resolver =
        new GatewayCredentialResolver(
            db, users, membership, tokens, pair.getPublic(), "test-key", Clock.systemUTC());
    global = sessions.openGlobal(user, "PASSWORD").block(WAIT);
    sessionA = sessions.openTenant(user, global.webSessionId(), a, UUID.randomUUID()).block(WAIT);
    sessionB = sessions.openTenant(user, global.webSessionId(), b, UUID.randomUUID()).block(WAIT);
  }

  GatewayCredential resolve(WebSessionStore.Issued session) {
    return resolver.resolve(issuer.issue(session)).block(WAIT);
  }

  DomainException denied(Mono<?> work) {
    return assertThrows(DomainException.class, () -> work.block(WAIT));
  }

  @Test
  void signedSessionWithoutRequiredBindingClaimsIsUnauthenticated() {
    for (String missing : List.of("membership_generation", "sid")) {
      var claims =
          new HashMap<String, Object>(
              io.jsonwebtoken.Jwts.parser()
                  .verifyWith(pair.getPublic())
                  .build()
                  .parseSignedClaims(issuer.issue(sessionA))
                  .getPayload());
      claims.remove(missing);
      String malformed =
          io.jsonwebtoken.Jwts.builder()
              .header()
              .keyId("test-key")
              .and()
              .claims(claims)
              .signWith(pair.getPrivate(), io.jsonwebtoken.Jwts.SIG.EdDSA)
              .compact();
      assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(malformed)).getStatus());
    }
  }

  @Test
  void signedScopeNarrowingSurvivesResolutionAndVerifiedSessionOwnerUsesThatActor() {
    var signed =
        new java.util.HashMap<String, Object>(
            io.jsonwebtoken.Jwts.parser()
                .verifyWith(pair.getPublic())
                .build()
                .parseSignedClaims(issuer.issue(sessionA))
                .getPayload());
    signed.put("scopes", List.of("read"));
    String narrow =
        io.jsonwebtoken.Jwts.builder()
            .header()
            .keyId("test-key")
            .and()
            .claims(signed)
            .signWith(pair.getPrivate(), io.jsonwebtoken.Jwts.SIG.EdDSA)
            .compact();
    var resolved = resolver.resolve(narrow).block(WAIT);
    assertEquals(List.of("read"), resolved.getBinding().getScopesList());
    var now = Instant.now();
    var request =
        ru.taska.api.b2b.v1.RequestContext.newBuilder()
            .setHeader(
                ru.taska.api.common.v1.Header.newBuilder()
                    .setRequestId(UUID.randomUUID().toString()))
            .setContext(
                resolved.getBinding().toBuilder()
                    .setContextVersion(1)
                    .setIssuerService("api-gateway")
                    .setAudienceService("api-gateway")
                    .setAction("gatewayRequest")
                    .setIssuedAt(now.toString())
                    .setExpiresAt(now.plusSeconds(30).toString())
                    .setAssertionId(UUID.randomUUID().toString()))
            .build();
    var security =
        new ru.taska.security.tenant.RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            new ru.taska.security.tenant.RpcContextSecurity.Keys() {
              public ru.taska.security.tenant.RpcContextSecurity.SigningKey signingKey(
                  String issuer) {
                return new ru.taska.security.tenant.RpcContextSecurity.SigningKey(
                    "owned", pair.getPrivate());
              }

              public PublicKey verificationKey(String issuer, String kid) {
                return pair.getPublic();
              }
            },
            (i, j, e) -> Mono.just(true),
            (c, r, p) ->
                Mono.just(c.getContext().getSessionId().equals(sessionA.sessionId().toString())));
    var verified =
        security
            .authorize(
                request,
                "gatewayRequest",
                new ru.taska.security.tenant.RpcContextSecurity.Policy(true, false))
            .block(WAIT);
    assertEquals(
        user,
        ru.taska.security.tenant.RpcContextBridge.install(
                VerifiedWebSessionActor.owner(
                    () ->
                        Mono.error(
                            new AssertionError(
                                "Legacy HMAC validation must not reinterpret verified B2B JWT"))),
                verified)
            .block(WAIT));
    signed.put("scopes", List.of("read", "platform:admin"));
    String expanded =
        io.jsonwebtoken.Jwts.builder()
            .header()
            .keyId("test-key")
            .and()
            .claims(signed)
            .signWith(pair.getPrivate(), io.jsonwebtoken.Jwts.SIG.EdDSA)
            .compact();
    assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(expanded)).getStatus());
  }

  @Test
  void realSignedSessionBindsCompanyAndGlobalCannotSelectItByHeader() {
    var first = resolve(sessionA);
    assertEquals(a.toString(), first.getBinding().getTenantId());
    assertEquals(user.toString(), first.getUser().getUserId());
    assertEquals(sessionA.sessionId().toString(), first.getBinding().getSessionId());
    assertEquals(b.toString(), resolve(sessionB).getBinding().getTenantId());
    assertFalse(resolve(global).getBinding().hasTenantId());
    assertEquals(
        CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL,
        resolve(global).getBinding().getCredentialKind());
  }

  @Test
  void authoritativeMembershipChangesDenyImmediatelyAndKeepOtherCompany() {
    resolve(sessionA);
    db.sql("UPDATE fixture_memberships SET generation=3,active=false WHERE tenant_id=:id")
        .bind("id", a)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    assertEquals(
        DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(issuer.issue(sessionA))).getStatus());
    assertEquals(b.toString(), resolve(sessionB).getBinding().getTenantId());
  }

  @Test
  void parentRevocationAndAccountGenerationInvalidateExistingAccess() {
    String token = issuer.issue(sessionA);
    sessions.revokeWeb(user, global.webSessionId()).block(WAIT);
    assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(token)).getStatus());
  }

  @Test
  void primaryMembershipOutageFailsClosedWithoutMintingContext() {
    offline = true;
    assertEquals(
        DomainStatus.UNAVAILABLE, denied(resolver.resolve(issuer.issue(sessionA))).getStatus());
  }

  @Test
  void wrongSignatureMissingExpiryAndForgedTenantNeverBecomeBinding() throws Exception {
    var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var now = Instant.now();
    for (var signing : List.of(other.getPrivate(), pair.getPrivate())) {
      String forged =
          io.jsonwebtoken.Jwts.builder()
              .header()
              .keyId("test-key")
              .and()
              .issuer("taska-auth")
              .audience()
              .add("taska-api")
              .and()
              .subject(user.toString())
              .claim("sid", sessionA.sessionId().toString())
              .claim("kind", "WEB_TENANT")
              .claim("account_generation", "1")
              .claim("membership_generation", "2")
              .claim("tenant_id", b.toString())
              .issuedAt(Date.from(now))
              .expiration(Date.from(now.plusSeconds(30)))
              .signWith(signing, io.jsonwebtoken.Jwts.SIG.EdDSA)
              .compact();
      assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(forged)).getStatus());
    }
    String noExpiry =
        io.jsonwebtoken.Jwts.builder()
            .header()
            .keyId("test-key")
            .and()
            .issuer("taska-auth")
            .audience()
            .add("taska-api")
            .and()
            .subject(user.toString())
            .signWith(pair.getPrivate(), io.jsonwebtoken.Jwts.SIG.EdDSA)
            .compact();
    assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(noExpiry)).getStatus());
  }

  @Test
  void accountGenerationAndInactiveAccountAreCheckedInPrimaryDatabase() {
    String token = issuer.issue(sessionA);
    db.sql("UPDATE taska.users SET auth_version=auth_version+1 WHERE id=:id")
        .bind("id", user)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(token)).getStatus());
  }

  @Test
  void boundPatCannotBeUsedAsGlobalOrDifferentCompanyAndRevocationIsImmediate() {
    String secret =
        "tsk_pat_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    UUID grant = UUID.randomUUID(), token = UUID.randomUUID();
    db.sql(
            "INSERT INTO"
                + " taska.integration_grants(id,user_id,kind,scope_type,tenant_id,permissions,access_level,version,created_at,expires_at)VALUES(:id,:user,'USER','ONE_TENANT',:tenant,ARRAY[]::text[],'READ',1,now(),now()+interval"
                + " '1 day')")
        .bind("id", grant)
        .bind("user", user)
        .bind("tenant", a)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql(
            "INSERT INTO"
                + " taska.personal_access_tokens(id,user_id,name,token_prefix,token_hash,scopes,created_at,expires_at,integration_grant_id)VALUES(:id,:user,'fixture','tsk_pat_test',:hash,ARRAY['read'],now(),now()+interval"
                + " '1 day',:grant)")
        .bind("id", token)
        .bind("user", user)
        .bind("hash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(secret))
        .bind("grant", grant)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    db.sql("UPDATE taska.personal_access_tokens SET token_hash=:hash WHERE id=:id")
        .bind("hash", org.apache.commons.codec.digest.DigestUtils.sha256Hex(secret))
        .bind("id", token)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    var result = resolver.resolve(secret).block(WAIT);
    assertEquals(a.toString(), result.getBinding().getTenantId());
    assertEquals(grant.toString(), result.getBinding().getGrantId());
    assertEquals(List.of("read"), result.getBinding().getScopesList());
    db.sql("UPDATE taska.integration_grants SET revoked_at=now() WHERE id=:id")
        .bind("id", grant)
        .fetch()
        .rowsUpdated()
        .block(WAIT);
    assertEquals(DomainStatus.UNAUTHENTICATED, denied(resolver.resolve(secret)).getStatus());
  }
}
