package ru.taska.workflowstorage;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.security.tenant.WorkflowTestContext.as;

import io.r2dbc.pool.*;
import io.r2dbc.spi.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.*;
import ru.taska.domain.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.workflowbootstrap.TenantStorageConfiguration;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Timeout(90)
abstract class TenantWorkflowFixture {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), ACTOR = UUID.randomUUID();
  static final UUID DEFAULT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String APP = UUID.randomUUID().toString(), DEPLOY = UUID.randomUUID().toString();
  static final Duration WAIT = Duration.ofSeconds(20);
  static final List<String> TABLES =
      List.of(
          "workflows",
          "statuses",
          "transitions",
          "validator_rules",
          "workflow_bindings",
          "issue_type_move_workflow_pins",
          "workflow_binding_changes",
          "issue_type_creation_workflow_receipts",
          "tenant_workflow_templates",
          "workflow_template_instances",
          "workflow_provisioning_receipts",
          "project_initializations",
          "workflow_authorization_audit",
          "workflow_creation_receipts");
  static ConnectionPool pool;
  static DatabaseClient raw, operator;
  static TenantTransactionExecutor tx;
  static TenantWorkflowStore store;
  static final ObjectMapper json = new ObjectMapper();
  static String seed;

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
    for (String ext : List.of("sql", "psql"))
      pg.copyFileToContainer(
          MountableFile.forClasspathResource("db/tenant/bootstrap." + ext),
          "/tmp/bootstrap." + ext);
    bootstrap(pg.getDatabaseName());
    operator = DatabaseClient.create(factory(pg.getUsername(), pg.getPassword()));
    sql("CREATE SCHEMA workflow_liquibase AUTHORIZATION taska_workflow_migrator");
    sql("REVOKE ALL ON SCHEMA workflow_liquibase FROM PUBLIC");
    sql("ALTER ROLE taska_workflow_app PASSWORD '" + APP + "'");
    sql("ALTER ROLE taska_workflow_deployer PASSWORD '" + DEPLOY + "'");
    migrate(pg.getJdbcUrl());
    migrate(pg.getJdbcUrl());
    seed =
        operator
            .sql("SELECT graph::text graph FROM taska.workflow_templates WHERE id=:id")
            .bind("id", DEFAULT)
            .map((r, m) -> r.get("graph", String.class))
            .one()
            .block(WAIT);
    pool =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(factory("taska_workflow_app", APP))
                .initialSize(1)
                .maxSize(3)
                .maxAcquireTime(WAIT)
                .build());
    raw = DatabaseClient.create(pool);
    tx = new TenantTransactionExecutor(pool);
    store = new TenantWorkflowStore(tx, json);
    new TenantStorageConfiguration().safeWorkflowRuntime(pool).afterPropertiesSet();
  }

  static void bootstrap(String db) throws Exception {
    var r =
        pg.execInContainer(
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            pg.getUsername(),
            "-d",
            db,
            "-v",
            "service=workflow",
            "-v",
            "schema=taska",
            "-f",
            "/tmp/bootstrap.psql");
    assertEquals(0, r.getExitCode(), r.getStderr());
  }

  static void migrate(String url) throws Exception {
    var migration =
        new TenantStorageConfiguration()
            .workflowTenantLiquibase(url, "taska_workflow_deployer", DEPLOY);
    migration.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader());
    migration.afterPropertiesSet();
  }

  static void sql(String text) {
    operator.sql(text).then().block(WAIT);
  }

  @AfterAll
  static void close() {
    if (pool != null) pool.disposeLater().block(WAIT);
  }

  @BeforeEach
  void reset() {
    sql("TRUNCATE taska.workflow_templates CASCADE");
    sql(
        "TRUNCATE "
            + String.join(",", TABLES.stream().map(t -> "taska." + t).toList())
            + " CASCADE");
    publish(DEFAULT, 1, 1, seed);
  }

  static void publish(UUID id, long version, int schema, String graph) {
    operator
        .sql(
            "INSERT INTO"
                + " taska.workflow_templates(id,code,version,name,graph_schema_version,graph,published_at)"
                + " VALUES(:id,'DEFAULT',:v,'Default workflow',:s,CAST(:graph AS jsonb),now())")
        .bind("id", id)
        .bind("v", version)
        .bind("s", schema)
        .bind("graph", graph)
        .then()
        .block(WAIT);
  }

  TenantWorkflowStore.TemplateCopy copy(UUID tenant) {
    return as(tenant, ACTOR, store.execute(s -> s.provisionTemplate(DEFAULT))).block(WAIT);
  }

  TenantWorkflowStore.Workflow graph(UUID tenant, UUID project) {
    var c = copy(tenant);
    return as(tenant, ACTOR, store.execute(s -> s.instantiate(c.id(), project))).block(WAIT);
  }

  long count(String table) {
    return operator
        .sql("SELECT count(*) n FROM taska." + table)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .block(WAIT);
  }

  static String state(Throwable error) {
    for (var e = error; e != null; e = e.getCause())
      if (e instanceof R2dbcException r) return r.getSqlState();
    return null;
  }

  void awaitBlockedInsert(String table, java.util.concurrent.CompletableFuture<?> loser) {
    Long waiting =
        Mono.defer(
                () ->
                    operator
                        .sql(
                            "SELECT count(*) n FROM pg_stat_activity WHERE"
                                + " usename='taska_workflow_app' AND wait_event_type='Lock' AND"
                                + " query LIKE :query")
                        .bind("query", "%INSERT INTO taska." + table + "%")
                        .map((r, m) -> r.get("n", Long.class))
                        .one())
            .repeatWhen(done -> done.delayElements(Duration.ofMillis(25)))
            .filter(n -> n > 0)
            .next()
            .block(WAIT);
    assertNotNull(waiting);
    assertTrue(waiting > 0);
    assertFalse(loser.isDone());
  }
}
