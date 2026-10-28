package ru.taska.repository;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Apply the exact upgrade SQL over both supported pre-0006 creation snapshots. */
@Testcontainers
class IssueTypeCreationMigrationIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void upgradePreservesBothExistingSnapshotKindsAndAllowsOnlyExplicitEmptyRegistry() throws Exception {
        try (var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
                var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA taska; CREATE TABLE taska.issue_type_creations(id int PRIMARY KEY,first_type_code text NOT NULL,first_type_generation uuid NOT NULL,desired_workflow_id uuid)");
            statement.execute("INSERT INTO taska.issue_type_creations VALUES (1,'TASK',gen_random_uuid(),NULL),(2,'BUG',gen_random_uuid(),gen_random_uuid())");
            statement.execute(Files.readString(Path.of("src/main/resources/db/changelog/changes/0006-first-type-explicit-workflow.sql")));
            try (var rows=statement.executeQuery("SELECT count(*) FROM taska.issue_type_creations WHERE first_type_code IS NOT NULL AND first_type_generation IS NOT NULL")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);
            }
            statement.execute("INSERT INTO taska.issue_type_creations VALUES (3,NULL,NULL,gen_random_uuid())");
            assertThatThrownBy(() -> statement.execute("INSERT INTO taska.issue_type_creations VALUES (4,NULL,NULL,NULL)"))
                    .isInstanceOfSatisfying(SQLException.class,error -> assertThat(error.getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> statement.execute("INSERT INTO taska.issue_type_creations VALUES (5,'TASK',NULL,gen_random_uuid())"))
                    .isInstanceOfSatisfying(SQLException.class,error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        }
    }
}
