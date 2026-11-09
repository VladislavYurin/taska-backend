package ru.taska.workflowstorage;

import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.security.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.*;

/** Legacy migration/RLS checks; transport verification is exercised by LegacyRpcTransportTest. */
@Testcontainers
@Timeout(90)
class LegacyWorkflowScopeIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static ConnectionPool pool;
  static DatabaseClient db;
  static TransactionalOperator tx;
  static RpcContextSecurity security;
  static final Duration WAIT = Duration.ofSeconds(15);
  static final UUID A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      ACTOR = UUID.randomUUID(),
      WA = UUID.randomUUID(),
      WB = UUID.randomUUID();
  static final UUID DEFAULT = UUID.fromString("11111111-1111-1111-1111-111111111111");

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

  @BeforeAll
  static void setup() throws Exception {
    String password = UUID.randomUUID().toString();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      st.execute("CREATE SCHEMA taska");
      st.execute("CREATE ROLE rpc_app LOGIN NOINHERIT PASSWORD '" + password + "'");
      st.execute("ALTER ROLE rpc_app SET app.rpc_scope_enforced='true'");
      st.execute("GRANT USAGE ON SCHEMA taska TO rpc_app");
      st.execute(
          "ALTER DEFAULT PRIVILEGES IN SCHEMA taska GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO"
              + " rpc_app");
      new liquibase.Liquibase(
              "db/changelog/db.changelog-master.yaml",
              new liquibase.resource.ClassLoaderResourceAccessor(),
              new liquibase.database.jvm.JdbcConnection(c))
          .update("");
    }
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("rpc_app", password))
                .initialSize(1)
                .maxSize(1)
                .maxAcquireTime(WAIT)
                .build());
    db = DatabaseClient.create(pool);
    tx = TransactionalOperator.create(new TenantRpcTransactionManager(pool));
    new LegacyRpcDatabaseBoundary(pool).validateConfiguration(true, "workflows");
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    security =
        new RpcContextSecurity(
            "api-gateway",
            Clock.systemUTC(),
            new RpcContextSecurity.Keys() {
              public RpcContextSecurity.SigningKey signingKey(String issuer) {
                return new RpcContextSecurity.SigningKey("owned", pair.getPrivate());
              }

              public PublicKey verificationKey(String issuer, String kid) {
                return pair.getPublic();
              }
            },
            (i, j, e) -> Mono.just(true),
            (c, r, p) ->
                Mono.just(
                    Set.of(A.toString(), B.toString()).contains(c.getContext().getTenantId())));
  }

  @AfterAll
  static void cleanup() {
    if (pool != null) pool.dispose();
  }

  static <T> T scoped(UUID tenant, Mono<T> work) {
    var now = Instant.now();
    var context =
        RequestContext.newBuilder()
            .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setContextVersion(1)
                    .setActorUserId(ACTOR.toString())
                    .setTenantId(tenant.toString())
                    .setActorGeneration("1")
                    .setMembershipGeneration("1")
                    .setSessionId(UUID.randomUUID().toString())
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                    .setIssuerService("api-gateway")
                    .setAudienceService("api-gateway")
                    .setAction("gatewayRequest")
                    .addScopes("read")
                    .addScopes("write")
                    .setIssuedAt(now.toString())
                    .setExpiresAt(now.plusSeconds(30).toString())
                    .setAssertionId(UUID.randomUUID().toString()))
            .build();
    return RpcContextBridge.install(
            tx.transactional(work),
            security
                .authorize(context, "gatewayRequest", new RpcContextSecurity.Policy(true, true))
                .block(WAIT))
        .block(WAIT);
  }

  static Mono<Long> execute(String sql, UUID id) {
    return db.sql(sql).bind("id", id).fetch().rowsUpdated();
  }

  @Test
  void tenantRowsAndReferencesAreIsolatedWhileBuiltInTemplateIsReadOnly() {
    for (var entry : Map.of(A, WA, B, WB).entrySet())
      scoped(
          entry.getKey(),
          execute(
              "INSERT INTO taska.workflows(id,name,owner_project_id)VALUES(:id,'Owned"
                  + " workflow',gen_random_uuid())",
              entry.getValue()));
    var a =
        scoped(
            A,
            db.sql("SELECT id FROM taska.workflows ORDER BY id")
                .map((r, m) -> r.get("id", UUID.class))
                .all()
                .collectList());
    assertEquals(Set.of(DEFAULT, WA), Set.copyOf(a));
    assertThrows(
        Exception.class,
        () ->
            scoped(
                A,
                db.sql(
                        "INSERT INTO"
                            + " taska.workflow_bindings(project_id,issue_type,workflow_id)VALUES(:project,'TASK',:target)")
                    .bind("project", UUID.randomUUID())
                    .bind("target", WB)
                    .fetch()
                    .rowsUpdated()));
    assertEquals(
        0,
        scoped(
            A,
            db.sql("SELECT count(*) n FROM taska.workflow_bindings WHERE tenant_id IS NOT NULL")
                .map((r, m) -> r.get("n", Long.class))
                .one()));
    UUID project = UUID.randomUUID();
    assertEquals(
        1,
        scoped(
            A,
            db.sql(
                    "INSERT INTO"
                        + " taska.workflow_bindings(project_id,issue_type,workflow_id)VALUES(:project,'TASK',:target)")
                .bind("project", project)
                .bind("target", DEFAULT)
                .fetch()
                .rowsUpdated()));
    assertEquals(
        0,
        scoped(
            B,
            db.sql("SELECT count(*) n FROM taska.workflow_bindings WHERE tenant_id IS NOT NULL")
                .map((r, m) -> r.get("n", Long.class))
                .one()));
    assertEquals(
        0,
        scoped(
            A,
            execute(
                "UPDATE taska.workflows SET name='Cannot edit default' WHERE id=:id", DEFAULT)));
    assertThrows(
        Exception.class,
        () ->
            scoped(
                A,
                db.sql("UPDATE taska.workflows SET tenant_id=:tenant WHERE id=:id")
                    .bind("tenant", B)
                    .bind("id", WA)
                    .fetch()
                    .rowsUpdated()));
    assertEquals(
        1, scoped(A, execute("UPDATE taska.workflows SET name='Still owned' WHERE id=:id", WA)));
    assertEquals(
        1,
        db.sql("SELECT count(*) n FROM taska.workflows")
            .map((r, m) -> r.get("n", Long.class))
            .one()
            .block(WAIT));
    assertThrows(
        IllegalStateException.class,
        () ->
            new LegacyRpcDatabaseBoundary(factory(pg.getUsername(), pg.getPassword()))
                .validateConfiguration(false, "workflows"));
  }
}
