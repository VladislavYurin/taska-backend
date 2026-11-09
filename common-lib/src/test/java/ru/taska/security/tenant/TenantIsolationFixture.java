package ru.taska.security.tenant;

import static io.r2dbc.spi.ConnectionFactoryOptions.*;
import static org.junit.jupiter.api.Assertions.*;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.*;
import java.time.Duration;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.taska.r2dbc.TenantTransactionExecutor;

/** Test-only fixture: real template roles and pooled runtime LOGIN, never SET ROLE postgres. */
public final class TenantIsolationFixture implements AutoCloseable {
  public static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
  public static final UUID PROJECT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
  public static final UUID PROJECT_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000001");
  public static final Duration WAIT = Duration.ofSeconds(15);
  public final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
  public final ConnectionPool pool;
  public final DatabaseClient raw;
  public final DatabaseClient operator;
  public final TenantTransactionExecutor executor;

  public TenantIsolationFixture(int poolSize) throws Exception {
    postgres.start();
    ConnectionPool created = null;
    try {
      for (String name : List.of("bootstrap", "protect-table", "example-migration", "audit"))
        for (String ext : List.of("sql", "psql"))
          postgres.copyFileToContainer(
              MountableFile.forClasspathResource("db/tenant/" + name + "." + ext),
              "/tmp/" + name + "." + ext);
      psql(postgres.getUsername(), "-f", "/tmp/bootstrap.psql");
      psql("taska_project_deployer", "-f", "/tmp/example-migration.psql");
      for (String table : List.of("projects", "issues")) {
        psql(
            "taska_project_deployer",
            "-v",
            "table=" + table,
            "-v",
            "tenant_column=tenant_id",
            "-c",
            "SET ROLE taska_project_migrator",
            "-f",
            "/tmp/protect-table.psql");
        psql(
            postgres.getUsername(),
            "-v",
            "table=" + table,
            "-v",
            "tenant_column=tenant_id",
            "-f",
            "/tmp/audit.psql");
      }
      operator = DatabaseClient.create(factory(postgres.getUsername(), postgres.getPassword()));
      String password = UUID.randomUUID().toString();
      String quotedPassword =
          operator
              .sql("SELECT quote_literal(:password) AS quoted")
              .bind("password", password)
              .map((row, metadata) -> row.get("quoted", String.class))
              .one()
              .block(WAIT);
      operator
          .sql("ALTER ROLE taska_project_app PASSWORD " + quotedPassword)
          .fetch()
          .rowsUpdated()
          .block(WAIT);
      created =
          new ConnectionPool(
              ConnectionPoolConfiguration.builder(factory("taska_project_app", password))
                  .initialSize(poolSize)
                  .maxSize(poolSize)
                  .maxAcquireTime(WAIT)
                  .build());
      pool = created;
      raw = DatabaseClient.create(pool);
      executor = new TenantTransactionExecutor(pool);
    } catch (Exception | Error error) {
      if (created != null) created.dispose();
      postgres.stop();
      throw error;
    }
  }

  private ConnectionFactory factory(String user, String password) {
    return ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, postgres.getHost())
            .option(PORT, postgres.getMappedPort(5432))
            .option(DATABASE, postgres.getDatabaseName())
            .option(USER, user)
            .option(PASSWORD, password)
            .build());
  }

  private void psql(String user, String... args) throws Exception {
    var cmd =
        new ArrayList<>(
            List.of(
                "psql",
                "-X",
                "-qAt",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                user,
                "-d",
                postgres.getDatabaseName(),
                "-v",
                "service=project",
                "-v",
                "schema=taska",
                "-v",
                "app_access=read_write"));
    cmd.addAll(List.of(args));
    var result = postgres.execInContainer(cmd.toArray(String[]::new));
    assertEquals(0, result.getExitCode(), result.getStderr());
  }

  /** Operator-only deterministic fixtures, not a production migration/backfill path. */
  public void reset(int issuesPerTenant) {
    if (issuesPerTenant < 1 || issuesPerTenant > 100000)
      throw new IllegalArgumentException("fixture size");
    operator.sql("TRUNCATE taska.issues,taska.projects").fetch().rowsUpdated().block(WAIT);
    for (UUID tenant : List.of(A, B)) {
      UUID project = tenant.equals(A) ? PROJECT_A : PROJECT_B;
      operator
          .sql("INSERT INTO taska.projects VALUES (:id,:tenant,'shared')")
          .bind("id", project)
          .bind("tenant", tenant)
          .fetch()
          .rowsUpdated()
          .block(WAIT);
      operator
          .sql(
              "INSERT INTO taska.issues SELECT md5(:tenant::text || ':' ||"
                  + " n::text)::uuid,:tenant,:project,n FROM generate_series(1,:size) n")
          .bind("tenant", tenant)
          .bind("project", project)
          .bind("size", issuesPerTenant)
          .fetch()
          .rowsUpdated()
          .block(WAIT);
    }
    operator.sql("ANALYZE taska.projects").fetch().rowsUpdated().block(WAIT);
    operator.sql("ANALYZE taska.issues").fetch().rowsUpdated().block(WAIT);
  }

  /**
   * Minting authority is restricted to this test artifact; production still verifies RPC context.
   */
  public static <T> Mono<T> as(UUID tenant, Mono<T> work) {
    var identity =
        new VerifiedTenantContext(
            ACTOR.toString(),
            tenant.toString(),
            UUID.randomUUID().toString(),
            "test.isolation",
            Set.of("write"),
            new byte[0]);
    return work.contextWrite(identity::install);
  }

  public record Snapshot(
      int pid, long tx, String tenant, String actor, String login, String role) {}

  public static Mono<Snapshot> snapshot(DatabaseClient db) {
    return db.sql(
            "SELECT pg_backend_pid() pid,txid_current()"
                + " tx,NULLIF(current_setting('app.tenant_id',true),'')"
                + " tenant,NULLIF(current_setting('app.actor_id',true),'') actor,session_user::text"
                + " login,current_user::text role")
        .map(
            (r, m) ->
                new Snapshot(
                    r.get("pid", Integer.class),
                    r.get("tx", Long.class),
                    r.get("tenant", String.class),
                    r.get("actor", String.class),
                    r.get("login", String.class),
                    r.get("role", String.class)))
        .one();
  }

  public static Mono<Long> count(DatabaseClient db) {
    return db.sql("SELECT count(*) n FROM taska.issues")
        .map((r, m) -> r.get("n", Long.class))
        .one();
  }

  public void assertCleanSingleConnection() {
    var state = snapshot(raw).block(WAIT);
    assertNull(state.tenant());
    assertNull(state.actor());
    assertEquals("taska_project_app", state.login());
    assertEquals(state.login(), state.role());
    assertEquals(0L, count(raw).block(WAIT));
  }

  @Override
  public void close() {
    try {
      pool.disposeLater().block(WAIT);
    } finally {
      postgres.stop();
    }
  }
}
