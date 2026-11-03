package ru.taska.r2dbc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Executes the shipped psql files themselves against PostgreSQL16, never a reimplementation. */
@Testcontainers
class TenantRlsTemplatesIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  static final String A = UUID.randomUUID().toString(), B = UUID.randomUUID().toString();
  static final String PROJECT_A = UUID.randomUUID().toString(),
      PROJECT_B = UUID.randomUUID().toString();

  static String run(boolean success, String... args) throws Exception {
    return runAs(postgres.getUsername(), success, args);
  }

  static String runAs(String user, boolean success, String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                "psql",
                "-X",
                "-qAt",
                "-v",
                "ON_ERROR_STOP=1",
                "-v",
                "app_access=read_write",
                "-U",
                user,
                "-d",
                postgres.getDatabaseName()));
    command.addAll(List.of(args));
    var result = postgres.execInContainer(command.toArray(String[]::new));
    if (success) assertEquals(0, result.getExitCode(), result.getStderr());
    else assertNotEquals(0, result.getExitCode(), result.getStdout());
    return result.getStdout().trim();
  }

  static String sql(String query) throws Exception {
    return run(true, "-c", query);
  }

  static String as(String role, String query) throws Exception {
    return runAs(
        role.endsWith("_migrator") ? "taska_project_deployer" : role,
        true,
        "-c",
        (role.endsWith("_migrator") ? "SET ROLE " + role + ";" : "") + query);
  }

  static void denies(String role, String query) throws Exception {
    runAs(
        role.endsWith("_migrator") ? "taska_project_deployer" : role,
        false,
        "-c",
        (role.endsWith("_migrator") ? "SET ROLE " + role + ";" : "") + query);
  }

  static void template(String name, String table, String column) throws Exception {
    run(
        true,
        "-v",
        "service=project",
        "-v",
        "schema=taska",
        "-v",
        "table=" + table,
        "-v",
        "tenant_column=" + column,
        "-f",
        "/tmp/" + name + ".psql");
  }

  static void protect(String table, String column) throws Exception {
    // SET ROLE and included template execute in the same psql session.
    postgres.copyFileToContainer(
        MountableFile.forClasspathResource("db/tenant/protect-table.psql"),
        "/tmp/protect-table.psql");
    runAs(
        "taska_project_deployer",
        true,
        "-v",
        "service=project",
        "-v",
        "schema=taska",
        "-v",
        "table=" + table,
        "-v",
        "tenant_column=" + column,
        "-c",
        "SET ROLE taska_project_migrator",
        "-f",
        "/tmp/protect-table.psql");
  }

  @BeforeAll
  static void setup() throws Exception {
    for (String name : List.of("bootstrap", "protect-table", "example-migration", "audit")) {
      for (String extension : List.of("psql", "sql"))
        postgres.copyFileToContainer(
            MountableFile.forClasspathResource("db/tenant/" + name + "." + extension),
            "/tmp/" + name + "." + extension);
    }
    template("bootstrap", "projects", "tenant_id");
    template("bootstrap", "projects", "tenant_id");
    run(true, "-f", "/tmp/example-migration.psql");
    protect("projects", "tenant_id");
    protect("issues", "tenant_id");
    sql(
        "SET ROLE taska_project_migrator; CREATE TABLE taska.tenants(id uuid PRIMARY KEY); RESET"
            + " ROLE;");
    protect("tenants", "id");
    // Owner migration may seed data only before FORCE RLS or with an explicit migration policy.
    sql(
        "INSERT INTO taska.projects VALUES ('"
            + PROJECT_A
            + "','"
            + A
            + "','same'),('"
            + PROJECT_B
            + "','"
            + B
            + "','same'); INSERT INTO taska.tenants VALUES ('"
            + A
            + "'),('"
            + B
            + "');");
    sql(
        "SET ROLE taska_project_migrator; GRANT SELECT,INSERT,UPDATE,DELETE ON"
            + " taska.projects,taska.issues TO taska_project_job; RESET ROLE;");
  }

  @Test
  void shippedAuditAndReapplicationSucceed() throws Exception {
    template("audit", "projects", "tenant_id");
    template("audit", "issues", "tenant_id");
    template("audit", "tenants", "id");
    protect("issues", "tenant_id");
    template("audit", "issues", "tenant_id");
  }

  @Test
  void runtimeRolesCannotEscapeTenantIncludingInsertsAndTenantChanges() throws Exception {
    for (String suffix : List.of("app", "admin", "job")) {
      String role = "taska_project_" + suffix;
      assertEquals("0", as(role, "SELECT count(*) FROM taska.projects"));
      assertEquals(
          "1",
          as(
              role,
              "BEGIN; SET LOCAL app.tenant_id='"
                  + A
                  + "'; SELECT count(*) FROM taska.projects; COMMIT;"));
      assertEquals(
          "0",
          as(
              role,
              "BEGIN; SET LOCAL app.tenant_id='"
                  + A
                  + "'; SELECT count(*) FROM taska.projects WHERE tenant_id='"
                  + B
                  + "'; COMMIT;"));
    }
    for (String role : List.of("taska_project_app", "taska_project_job")) {
      denies(
          role,
          "BEGIN; SET LOCAL app.tenant_id='"
              + A
              + "'; INSERT INTO taska.projects VALUES ('"
              + UUID.randomUUID()
              + "','"
              + B
              + "','foreign'); COMMIT;");
      denies(
          role,
          "BEGIN; SET LOCAL app.tenant_id='"
              + A
              + "'; UPDATE taska.projects SET project_key='moved-nonconflicting',tenant_id='"
              + B
              + "'; COMMIT;");
      assertEquals(
          "0",
          as(
              role,
              "BEGIN; SET LOCAL app.tenant_id='"
                  + A
                  + "'; WITH gone AS (DELETE FROM taska.projects WHERE tenant_id='"
                  + B
                  + "' RETURNING id) SELECT count(*) FROM gone; COMMIT;"));
    }
  }

  @Test
  void compositeFkAndTenantLocalUniquenessAreEnforced() throws Exception {
    denies(
        "taska_project_app",
        "BEGIN; SET LOCAL app.tenant_id='"
            + A
            + "'; INSERT INTO taska.issues VALUES ('"
            + UUID.randomUUID()
            + "','"
            + A
            + "','"
            + PROJECT_B
            + "',1); COMMIT;");
    denies(
        "taska_project_app",
        "BEGIN; SET LOCAL app.tenant_id='"
            + A
            + "'; INSERT INTO taska.projects VALUES ('"
            + UUID.randomUUID()
            + "','"
            + A
            + "','same'); COMMIT;");
  }

  @Test
  void ddlTruncateRoleEscalationAndAdminWriteAreForbidden() throws Exception {
    for (String role :
        List.of(
            "taska_project_app",
            "taska_project_admin",
            "taska_project_job",
            "taska_project_dispatcher")) {
      denies(role, "SET ROLE taska_project_migrator");
      denies(role, "SET ROLE taska_project_deployer");
      denies(
          role,
          "SET ROLE "
              + (role.equals("taska_project_admin") ? "taska_project_app" : "taska_project_admin"));
      denies(role, "CREATE TABLE taska.escape(id int)");
      denies(role, "CREATE TEMP TABLE escape(id int)");
      denies(role, "TRUNCATE taska.projects");
      denies(role, "ALTER TABLE taska.projects DISABLE ROW LEVEL SECURITY");
    }
    denies(
        "taska_project_admin",
        "BEGIN;SET LOCAL app.tenant_id='" + A + "';DELETE FROM taska.projects;COMMIT;");
    denies("taska_project_dispatcher", "SELECT * FROM taska.projects");
    denies("taska_project_app", "SELECT * FROM taska.global_catalog");
  }

  @Test
  void deploymentMustExplicitlyAssumeMigrationOwner() throws Exception {
    denies("taska_project_deployer", "CREATE TABLE taska.deployment_probe(id int)");
    assertEquals(
        "taska_project_migrator",
        as("taska_project_deployer", "SET ROLE taska_project_migrator;SELECT current_user"));
    denies("taska_project_migrator", "CREATE ROLE forbidden LOGIN");
    assertEquals("0", as("taska_project_migrator", "SELECT count(*) FROM taska.projects"));
  }

  @Test
  void missingEmptyOrInvalidContextNeverProvidesGlobalAccess() throws Exception {
    assertEquals(
        "0", as("taska_project_app", "SET app.tenant_id=''; SELECT count(*) FROM taska.projects"));
    denies("taska_project_app", "SET app.tenant_id='invalid'; SELECT * FROM taska.projects");
    assertEquals(
        "1",
        as(
            "taska_project_app",
            "BEGIN; SET LOCAL app.tenant_id='"
                + A
                + "'; SELECT count(*) FROM taska.tenants; COMMIT;"));
  }

  @Test
  void auditRejectsPrivilegePolicyAndMembershipDrift() throws Exception {
    String predicate = "tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid";
    for (var change :
        List.of(
            new String[] {
              "GRANT TRUNCATE ON taska.projects TO taska_project_app",
              "REVOKE TRUNCATE ON taska.projects FROM taska_project_app"
            },
            new String[] {
              "ALTER ROLE taska_project_app BYPASSRLS", "ALTER ROLE taska_project_app NOBYPASSRLS"
            },
            new String[] {
              "GRANT taska_project_app TO taska_project_dispatcher",
              "REVOKE taska_project_app FROM taska_project_dispatcher"
            },
            new String[] {
              "ALTER TABLE taska.projects NO FORCE ROW LEVEL SECURITY",
              "ALTER TABLE taska.projects FORCE ROW LEVEL SECURITY"
            },
            new String[] {
              "GRANT UPDATE(project_key) ON taska.projects TO taska_project_admin",
              "REVOKE UPDATE(project_key) ON taska.projects FROM taska_project_admin"
            },
            new String[] {
              "CREATE POLICY extra ON taska.projects TO taska_project_app USING(true)",
              "DROP POLICY extra ON taska.projects"
            },
            new String[] {
              "ALTER POLICY tenant_guard ON taska.projects USING(true) WITH CHECK(true)",
              "ALTER POLICY tenant_guard ON taska.projects USING("
                  + predicate
                  + ") WITH CHECK("
                  + predicate
                  + ")"
            })) {
      sql(change[0]);
      try {
        run(
            false,
            "-v",
            "service=project",
            "-v",
            "schema=taska",
            "-v",
            "table=projects",
            "-v",
            "tenant_column=tenant_id",
            "-f",
            "/tmp/audit.psql");
      } finally {
        sql(change[1]);
      }
    }
    template("audit", "projects", "tenant_id");
  }

  @Test
  void invalidInputsAndExistingUnsafeRolesFailClosed() throws Exception {
    run(
        false,
        "-v",
        "service=project;DROP ROLE x",
        "-v",
        "schema=taska",
        "-f",
        "/tmp/bootstrap.psql");
    sql("ALTER ROLE taska_project_job CREATEROLE");
    try {
      run(false, "-v", "service=project", "-v", "schema=taska", "-f", "/tmp/bootstrap.psql");
    } finally {
      sql("ALTER ROLE taska_project_job NOCREATEROLE");
    }
    assertEquals(
        "taska_project_app|taska_project_app",
        as("taska_project_app", "SELECT session_user,current_user"));
  }

  @Test
  void auditRejectsGrantOptionsAndMigrationRoleEscalation() throws Exception {
    for (var change :
        List.of(
            new String[] {
              "GRANT SELECT ON taska.projects TO taska_project_app WITH GRANT OPTION",
              "REVOKE GRANT OPTION FOR SELECT ON taska.projects FROM taska_project_app"
            },
            new String[] {
              "ALTER ROLE taska_project_migrator BYPASSRLS",
              "ALTER ROLE taska_project_migrator NOBYPASSRLS"
            },
            new String[] {
              "ALTER ROLE taska_project_deployer CREATEROLE",
              "ALTER ROLE taska_project_deployer NOCREATEROLE"
            })) {
      sql(change[0]);
      try {
        run(
            false,
            "-v",
            "service=project",
            "-v",
            "schema=taska",
            "-v",
            "table=projects",
            "-v",
            "tenant_column=tenant_id",
            "-f",
            "/tmp/audit.psql");
      } finally {
        sql(change[1]);
      }
    }
    assertEquals(
        "0",
        as("taska_project_app", "SET app.is_admin='true';SELECT count(*) FROM taska.projects"));
  }

  @Test
  void auditRejectsTenantForeignKeyWithoutTenantComponent() throws Exception {
    sql(
        "SET ROLE taska_project_migrator;CREATE TABLE taska.bad_link(id uuid PRIMARY KEY,tenant_id"
            + " uuid NOT NULL,project_id uuid REFERENCES taska.projects(id));RESET ROLE;");
    try {
      protect("bad_link", "tenant_id");
      run(
          false,
          "-v",
          "service=project",
          "-v",
          "schema=taska",
          "-v",
          "table=bad_link",
          "-v",
          "tenant_column=tenant_id",
          "-f",
          "/tmp/audit.psql");
    } finally {
      sql("DROP TABLE taska.bad_link");
    }
  }

  @Test
  void runtimeOwnWritesAndCompositeReferenceSucceed() throws Exception {
    String id = UUID.randomUUID().toString();
    assertEquals(
        A,
        as(
            "taska_project_app",
            "BEGIN;SET LOCAL app.tenant_id='"
                + A
                + "';INSERT INTO taska.projects VALUES ('"
                + id
                + "','"
                + A
                + "','own-positive'); INSERT INTO taska.issues VALUES ('"
                + UUID.randomUUID()
                + "','"
                + A
                + "','"
                + id
                + "',42) RETURNING tenant_id;ROLLBACK;"));
  }

  @Test
  void defaultPrivilegesDoNotExposeFutureTablesOrFunctions() throws Exception {
    as(
        "taska_project_deployer",
        "SET ROLE taska_project_migrator;CREATE TABLE taska.private_probe(id uuid);CREATE FUNCTION"
            + " taska.private_probe_fn() RETURNS int LANGUAGE sql AS 'SELECT 1';");
    try {
      denies("taska_project_app", "SELECT * FROM taska.private_probe");
      denies("taska_project_app", "SELECT taska.private_probe_fn()");
      assertEquals("1", as("taska_project_migrator", "SELECT taska.private_probe_fn()"));
    } finally {
      sql("DROP TABLE taska.private_probe;DROP FUNCTION taska.private_probe_fn()");
    }
  }

  @Test
  void nullTenantDataAndWrongExistingIndexAbortProtectionAtomically() throws Exception {
    for (String table : List.of("bad_null", "bad_index")) {
      sql(
          "SET ROLE taska_project_migrator;CREATE TABLE taska."
              + table
              + "(id uuid PRIMARY KEY,tenant_id uuid);RESET ROLE;");
      try {
        if (table.equals("bad_null"))
          sql("INSERT INTO taska.bad_null VALUES ('" + UUID.randomUUID() + "',NULL)");
        else sql("CREATE INDEX bad_index_tenant_guard_idx ON taska.bad_index(id)");
        runAs(
            "taska_project_deployer",
            false,
            "-v",
            "service=project",
            "-v",
            "schema=taska",
            "-v",
            "table=" + table,
            "-v",
            "tenant_column=tenant_id",
            "-c",
            "SET ROLE taska_project_migrator",
            "-f",
            "/tmp/protect-table.psql");
        assertEquals(
            "f",
            sql("SELECT relrowsecurity FROM pg_class WHERE oid='taska." + table + "'::regclass"));
        denies("taska_project_app", "SELECT * FROM taska." + table);
      } finally {
        sql("DROP TABLE taska." + table);
      }
    }
  }

  @Test
  void auditRejectsImplicitUnrestrictedPolicy() throws Exception {
    sql(
        "DROP POLICY tenant_guard ON taska.projects;CREATE POLICY tenant_guard ON taska.projects TO"
            + " taska_project_app,taska_project_admin,taska_project_job;");
    try {
      run(
          false,
          "-v",
          "service=project",
          "-v",
          "schema=taska",
          "-v",
          "table=projects",
          "-v",
          "tenant_column=tenant_id",
          "-f",
          "/tmp/audit.psql");
    } finally {
      sql(
          "DROP POLICY tenant_guard ON taska.projects;CREATE POLICY tenant_guard ON taska.projects"
              + " TO taska_project_app,taska_project_admin,taska_project_job"
              + " USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH"
              + " CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);");
    }
  }

  @Test
  void appendOnlyAndReadOnlyProfilesGrantOnlyRequiredDml() throws Exception {
    as(
        "taska_project_migrator",
        "CREATE TABLE taska.ledger_probe(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,note text NOT"
            + " NULL)");
    try {
      for (String profile : List.of("append_only", "read_only")) {
        runAs(
            "taska_project_deployer",
            true,
            "-v",
            "service=project",
            "-v",
            "schema=taska",
            "-v",
            "table=ledger_probe",
            "-v",
            "tenant_column=tenant_id",
            "-v",
            "app_access=" + profile,
            "-c",
            "SET ROLE taska_project_migrator",
            "-f",
            "/tmp/protect-table.psql");
        run(
            true,
            "-v",
            "service=project",
            "-v",
            "schema=taska",
            "-v",
            "table=ledger_probe",
            "-v",
            "tenant_column=tenant_id",
            "-v",
            "app_access=" + profile,
            "-f",
            "/tmp/audit.psql");
        if (profile.equals("append_only"))
          as(
              "taska_project_app",
              "BEGIN;SET LOCAL app.tenant_id='"
                  + A
                  + "';INSERT INTO taska.ledger_probe VALUES ('"
                  + UUID.randomUUID()
                  + "','"
                  + A
                  + "','initial');COMMIT;");
        else
          denies(
              "taska_project_app",
              "BEGIN;SET LOCAL app.tenant_id='"
                  + A
                  + "';INSERT INTO taska.ledger_probe VALUES ('"
                  + UUID.randomUUID()
                  + "','"
                  + A
                  + "','forbidden');COMMIT;");
        denies(
            "taska_project_app",
            "BEGIN;SET LOCAL app.tenant_id='"
                + A
                + "';UPDATE taska.ledger_probe SET note='changed';COMMIT;");
        denies(
            "taska_project_app",
            "BEGIN;SET LOCAL app.tenant_id='" + A + "';DELETE FROM taska.ledger_probe;COMMIT;");
      }
    } finally {
      sql("DROP TABLE taska.ledger_probe");
    }
  }
}
