package ru.taska.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class WorkflowSortOrderMigrationTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
  PGSimpleDataSource source;

  @BeforeEach
  void oldSchema() throws Exception {
    String name = "review_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name);
    }
    source = new PGSimpleDataSource();
    source.setURL("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + name);
    source.setUser(pg.getUsername());
    source.setPassword(pg.getPassword());
    try (var paths =
        java.nio.file.Files.list(
            java.nio.file.Path.of(getClass().getResource("/db/changelog/changes").toURI()))) {
      for (var file :
          paths
              .filter(p -> p.getFileName().toString().endsWith(".sql"))
              .filter(p -> p.getFileName().toString().compareTo("0012") < 0)
              .sorted()
              .toList()) {
        var previous = new SpringLiquibase();
        previous.setDataSource(source);
        previous.setChangeLog("classpath:db/changelog/changes/" + file.getFileName());
        previous.afterPropertiesSet();
      }
    }
    update(
        "INSERT INTO taska.workflows(id,name) VALUES"
            + " ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','Upgrade fixture');INSERT INTO"
            + " taska.statuses(id,workflow_id,status_key,name,category,sort_order) VALUES "
            + "('aaaaaaaa-0000-0000-0000-000000000001','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','TODO','Todo','TODO',10),"
            + "('aaaaaaaa-0000-0000-0000-000000000002','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','BUSY','Busy','IN_PROGRESS',20),"
            + "('aaaaaaaa-0000-0000-0000-000000000003','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','DONE','Done','DONE',30);INSERT"
            + " INTO taska.transitions(workflow_id,from_status_id,to_status_id,name,sort_order)"
            + " VALUES "
            + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','aaaaaaaa-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000002','Start',10),"
            + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','aaaaaaaa-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000003','Finish',20)");
  }

  void migrate() throws Exception {
    var migration = new SpringLiquibase();
    migration.setDataSource(source);
    migration.setChangeLog(
        "classpath:db/changelog/changes/0012-workflow-sort-order-uniqueness.sql");
    migration.afterPropertiesSet();
  }

  String snapshot() throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM"
                    + " taska.statuses x),(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM"
                    + " taska.transitions x),(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM"
                    + " taska.validator_rules x))::text")) {
      assertTrue(r.next());
      return r.getString(1);
    }
  }

  void update(String sql) throws Exception {
    try (var c = source.getConnection();
        var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  void haltedWithoutDataLoss() throws Exception {
    String before = snapshot();
    assertThrows(Exception.class, this::migrate);
    assertEquals(before, snapshot());
    try (var c = source.getConnection();
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='taska' AND indexname IN"
                    + " ('statuses_workflow_sort_order_uniq','transitions_workflow_sort_order_uniq')")) {
      assertTrue(r.next());
      assertEquals(0, r.getInt(1));
    }
  }

  @Test
  void distinctTransitionsWithSameDisplayOrderHaltWithoutDeletingEither() throws Exception {
    update("UPDATE taska.transitions SET sort_order=0");
    haltedWithoutDataLoss();
  }

  @Test
  void distinctStatusesWithSameDisplayOrderHaltWithoutDeletingOrphan() throws Exception {
    update(
        "INSERT INTO taska.statuses(workflow_id,status_key,name,category,sort_order)"
            + " SELECT workflow_id,'WAITING_REVIEW','Waiting review','IN_PROGRESS',sort_order"
            + " FROM taska.statuses WHERE status_key='TODO' LIMIT 1");
    haltedWithoutDataLoss();
  }

  @Test
  void cleanUpgradeAndReplayPreserveGraphAndRejectDuplicateOrders() throws Exception {
    String before = snapshot();
    migrate();
    migrate();
    assertEquals(before, snapshot());
    SQLException status =
        assertThrows(
            SQLException.class,
            () ->
                update(
                    "INSERT INTO taska.statuses(workflow_id,status_key,name,category,sort_order)"
                        + " SELECT workflow_id,'OTHER','Other','IN_PROGRESS',sort_order FROM"
                        + " taska.statuses LIMIT 1"));
    assertEquals("23505", status.getSQLState());
    assertTrue(status.getMessage().contains("statuses_workflow_sort_order_uniq"));
    SQLException transition =
        assertThrows(
            SQLException.class,
            () ->
                update(
                    "INSERT INTO"
                        + " taska.transitions(workflow_id,from_status_id,to_status_id,name,sort_order)"
                        + " SELECT workflow_id,to_status_id,from_status_id,'Reverse',sort_order"
                        + " FROM taska.transitions WHERE"
                        + " workflow_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' LIMIT 1"));
    assertEquals("23505", transition.getSQLState());
    assertTrue(transition.getMessage().contains("transitions_workflow_sort_order_uniq"));
    assertEquals(before, snapshot());
  }
}
