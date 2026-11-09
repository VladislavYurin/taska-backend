package ru.taska.security.trust;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.r2dbc.spi.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.*;

@Testcontainers
@Timeout(120)
class AuthRpcTrustIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  @TempDir Path directory;
  static ConnectionFactory connections;
  static DatabaseClient db;
  ManagedTrustFixture fixture;
  AuthRpcTrustBackend backend, replica;
  UUID actor = UUID.randomUUID(),
      tenant = UUID.randomUUID(),
      web = UUID.randomUUID(),
      session = UUID.randomUUID();
  volatile long member = 2;
  volatile boolean offline;
  static final Duration WAIT = Duration.ofSeconds(15);
  static final String RPC = "owned.Projects/Create", ACTION = "createProject";
  static final RpcContextSecurity.Policy POLICY = new RpcContextSecurity.Policy(true, true);

  @BeforeAll
  static void database() throws Exception {
    try (var jdbc =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      new liquibase.Liquibase(
              "db/changelog/db.changelog-master.yaml",
              new liquibase.resource.ClassLoaderResourceAccessor(),
              new liquibase.database.jvm.JdbcConnection(jdbc))
          .update("");
    }
    connections =
        ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, pg.getHost())
                .option(ConnectionFactoryOptions.PORT, pg.getMappedPort(5432))
                .option(ConnectionFactoryOptions.DATABASE, pg.getDatabaseName())
                .option(ConnectionFactoryOptions.USER, pg.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, pg.getPassword())
                .build());
    db = DatabaseClient.create(connections);
  }

  static void sql(String sql) {
    db.sql(sql).then().block(WAIT);
  }

  @BeforeEach
  void setup() throws Exception {
    sql("TRUNCATE taska.users,taska.rpc_assertion_replay CASCADE");
    sql(
        "INSERT INTO taska.users(id,email,display_name,status,global_role) VALUES('"
            + actor
            + "','owned@example.invalid','Owned','ACTIVE','USER')");
    sql(
        "INSERT INTO"
            + " taska.web_sessions(id,user_id,state,assurance,auth_version,created_at,expires_at)"
            + " VALUES('"
            + web
            + "','"
            + actor
            + "','ACTIVE','PASSWORD',1,now(),now()+interval '1 hour')");
    sql(
        "INSERT INTO"
            + " taska.tenant_sessions(id,web_session_id,user_id,tenant_id,membership_version,created_at,expires_at)"
            + " VALUES('"
            + session
            + "','"
            + web
            + "','"
            + actor
            + "','"
            + tenant
            + "',2,now(),now()+interval '1 hour')");
    fixture =
        new ManagedTrustFixture(
            directory, List.of("auth-service", "api-gateway", "project-service"));
    fixture.userEdge("api-gateway", "project-service", RPC, ACTION);
    fixture.write();
    backend = backend();
    replica = backend();
  }

  AuthRpcTrustBackend backend() {
    return new AuthRpcTrustBackend(
        db,
        connections,
        (t, u) ->
            Mono.defer(
                () ->
                    offline
                        ? Mono.error(new IllegalStateException("owned outage"))
                        : t.equals(tenant) && u.equals(actor) && member > 0
                            ? Mono.just(member)
                            : Mono.empty()),
        fixture.trust("auth-service"),
        Clock.systemUTC());
  }

  RequestContext context() {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            TrustedTenantContext.newBuilder()
                .setActorUserId(actor.toString())
                .setTenantId(tenant.toString())
                .setSessionId(session.toString())
                .setCredentialId(session.toString())
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setActorGeneration("1")
                .setMembershipGeneration("2")
                .addScopes("read")
                .addScopes("write")
                .setIssuerService("api-gateway")
                .setAudienceService("project-service")
                .setAction(ACTION)
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString())
                .setContextVersion(1))
        .build();
  }

  @Test
  void currentSessionAccountAndMembershipAreRequiredOnEveryReadAndWrite() {
    var c = context();
    assertEquals(true, backend.allowed(c, RPC, POLICY).block(WAIT));
    member = 3;
    assertEquals(
        false, replica.allowed(c, RPC, new RpcContextSecurity.Policy(true, false)).block(WAIT));
    member = 2;
    offline = true;
    assertThrows(RuntimeException.class, () -> backend.allowed(c, RPC, POLICY).block(WAIT));
    offline = false;
    sql("UPDATE taska.web_sessions SET revoked_at=now() WHERE id='" + web + "'");
    assertEquals(false, backend.allowed(c, RPC, POLICY).block(WAIT));
    sql("UPDATE taska.web_sessions SET revoked_at=NULL WHERE id='" + web + "'");
    sql("UPDATE taska.users SET auth_version=2 WHERE id='" + actor + "'");
    assertEquals(false, backend.allowed(c, RPC, POLICY).block(WAIT));
    assertEquals(
        false,
        backend
            .allowed(
                c.toBuilder()
                    .setContext(
                        c.getContext().toBuilder().setCredentialId(UUID.randomUUID().toString()))
                    .build(),
                RPC,
                POLICY)
            .block(WAIT));
  }

  @Test
  void boundPersonalTokenCannotWidenScopesOrSurviveGrantRevocation() {
    UUID grant = UUID.randomUUID(), token = UUID.randomUUID();
    sql(
        "INSERT INTO"
            + " taska.integration_grants(id,user_id,kind,scope_type,tenant_id,access_level,permissions,version,created_at,expires_at)"
            + " VALUES('"
            + grant
            + "','"
            + actor
            + "','USER','ONE_TENANT','"
            + tenant
            + "','READ',ARRAY[]::text[],1,now(),now()+interval '1 hour')");
    sql(
        "INSERT INTO"
            + " taska.personal_access_tokens(id,user_id,name,token_prefix,token_hash,scopes,created_at,expires_at,integration_grant_id)"
            + " VALUES('"
            + token
            + "','"
            + actor
            + "','Owned','tsk_pat_owned','"
            + "1".repeat(64)
            + "',ARRAY['read'],now(),now()+interval '1 hour','"
            + grant
            + "')");
    var webContext = context();
    var pat =
        webContext.toBuilder()
            .setContext(
                webContext.getContext().toBuilder()
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_USER_PAT)
                    .clearSessionId()
                    .setCredentialId(token.toString())
                    .setGrantId(grant.toString())
                    .setGrantGeneration("1"))
            .build();
    assertEquals(false, backend.allowed(pat, RPC, POLICY).block(WAIT));
    var read =
        pat.toBuilder()
            .setContext(pat.getContext().toBuilder().clearScopes().addScopes("read"))
            .build();
    assertEquals(
        true, backend.allowed(read, RPC, new RpcContextSecurity.Policy(true, false)).block(WAIT));
    sql(
        "UPDATE taska.integration_grants SET revoked_at=now(),version=version+1 WHERE id='"
            + grant
            + "'");
    assertEquals(false, replica.allowed(read, RPC, POLICY).block(WAIT));
    assertEquals(
        false,
        replica
            .allowed(
                read.toBuilder()
                    .setContext(
                        read.getContext().toBuilder().setTenantId(UUID.randomUUID().toString()))
                    .build(),
                RPC,
                POLICY)
            .block(WAIT));
  }

  @Test
  void replayIsSharedAcrossReplicasAndSurvivesBusinessRollback() {
    String jti = UUID.randomUUID().toString();
    Instant until = Instant.now().plusSeconds(30);
    var results =
        Flux.range(0, 24)
            .flatMap(
                i ->
                    (i % 2 == 0 ? backend : replica)
                        .consume("project-service", "api-gateway", jti, until),
                12)
            .collectList()
            .block(WAIT);
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    String rollback = UUID.randomUUID().toString();
    var tx = TransactionalOperator.create(new R2dbcTransactionManager(connections));
    assertThrows(
        RuntimeException.class,
        () ->
            backend
                .consume("project-service", "api-gateway", rollback, until)
                .flatMap(
                    ok -> {
                      assertTrue(ok);
                      return Mono.error(new IllegalStateException("owned business rollback"));
                    })
                .as(tx::transactional)
                .block(WAIT));
    assertEquals(
        false, replica.consume("project-service", "api-gateway", rollback, until).block(WAIT));
    assertEquals(true, replica.consume("auth-service", "api-gateway", jti, until).block(WAIT));
    assertEquals(
        3L,
        db.sql("SELECT count(*) AS n FROM taska.rpc_assertion_replay")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
  }

  @Test
  void replayRetentionCoversTheRecipientWindowWithPrimaryClockSkew() {
    String jti = UUID.randomUUID().toString();
    Instant recipientEnd = Instant.now().plusSeconds(1);
    var ahead =
        new AuthRpcTrustBackend(
            db,
            connections,
            (t, u) -> Mono.just(2L),
            fixture.trust("auth-service"),
            Clock.offset(Clock.systemUTC(), Duration.ofSeconds(5)));
    assertEquals(
        true, ahead.consume("project-service", "api-gateway", jti, recipientEnd).block(WAIT));
    var stored =
        db.sql("SELECT retain_until FROM taska.rpc_assertion_replay WHERE assertion_id=:id")
            .bind("id", UUID.fromString(jti))
            .map((r, m) -> r.get("retain_until", OffsetDateTime.class).toInstant())
            .one()
            .block(WAIT);
    assertFalse(stored.isBefore(recipientEnd.plusSeconds(4)));
    assertEquals(
        false, backend.consume("project-service", "api-gateway", jti, recipientEnd).block(WAIT));
    var behind =
        new AuthRpcTrustBackend(
            db,
            connections,
            (t, u) -> Mono.just(2L),
            fixture.trust("auth-service"),
            Clock.offset(Clock.systemUTC(), Duration.ofSeconds(-5)));
    assertEquals(
        true,
        behind
            .consume(
                "project-service",
                "api-gateway",
                UUID.randomUUID().toString(),
                Instant.now().plusSeconds(40))
            .block(WAIT));
    assertEquals(
        false,
        behind
            .consume(
                "project-service",
                "api-gateway",
                UUID.randomUUID().toString(),
                Instant.now().plusSeconds(46))
            .block(WAIT));
  }

  @Test
  void mtlsControlPlaneChecksRecipientAndRevokesAnExistingConnection() throws Exception {
    var ssl =
        GrpcSslContexts.forServer(
                fixture.path("auth-service.crt").toFile(),
                fixture.path("auth-service.key").toFile())
            .trustManager(fixture.path("ca.crt").toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    var server =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(
                ServerInterceptors.interceptForward(
                    new RpcTrustControlGrpcService(backend),
                    new ManagedRpcPeerInterceptor(fixture.trust("auth-service"), "test")))
            .build()
            .start();
    var channel =
        new FileMtlsRpcChannels(
                fixture.path("ca.crt"),
                fixture.path("project-service.crt"),
                fixture.path("project-service.key"),
                "test")
            .create("localhost", server.getPort(), "auth-service");
    try (var control = new RpcControlClient(channel)) {
      assertEquals(true, control.allowed(context(), RPC, POLICY).block(WAIT));
      String jti = UUID.randomUUID().toString();
      assertEquals(
          true,
          control
              .consume("project-service", "api-gateway", jti, Instant.now().plusSeconds(30))
              .block(WAIT));
      assertThrows(
          StatusRuntimeException.class,
          () ->
              control
                  .consume(
                      "auth-service",
                      "api-gateway",
                      UUID.randomUUID().toString(),
                      Instant.now().plusSeconds(30))
                  .block(WAIT));
      fixture.services.put("project-service", new ManagedRpcTrust.Service("1", Set.of()));
      fixture.write();
      var error =
          assertThrows(
              StatusRuntimeException.class,
              () -> control.allowed(context(), RPC, POLICY).block(WAIT));
      assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
      assertEquals(
          1L,
          db.sql("SELECT count(*) AS n FROM taska.rpc_assertion_replay")
              .map((r, m) -> r.get("n", Long.class))
              .one()
              .block(WAIT));
    } finally {
      server.shutdownNow().awaitTermination();
    }
  }
}
