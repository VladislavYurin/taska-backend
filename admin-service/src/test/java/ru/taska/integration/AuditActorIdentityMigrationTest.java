package ru.taska.integration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AuditActorIdentityMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void preservesHistoricalActorAndAcceptsNameAndEmailSnapshots() throws Exception {
        migrate("db/changelog/changes/0000-init.sql");
        migrate("db/changelog/changes/0001-admin-audit-log.sql");
        UUID actorId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO taska.admin_audit_log(actor_user_id,actor_login,actor_roles,action,target_service,target_table,target_id,reason) VALUES ('"
                    + actorId + "','invited_legacy','[\"GLOBAL_ADMIN\"]','USER_BLOCKED','auth','users','target','test')");
        }

        migrate("db/changelog/db.changelog-master.yaml");

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT actor_user_id,actor_display_name,actor_email FROM taska.admin_audit_log")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(actorId);
                assertThat(rows.getString(2)).isEqualTo("invited_legacy");
                assertThat(rows.getString(3)).isNull();
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='taska' AND table_name='admin_audit_log' AND column_name='actor_login'")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var insert = connection.prepareStatement("INSERT INTO taska.admin_audit_log(actor_user_id,actor_display_name,actor_email,actor_roles,action,target_service,target_table,target_id,reason) VALUES (?,?,?,'[\"GLOBAL_ADMIN\"]','USER_BLOCKED','auth','users','target','test')")) {
                insert.setObject(1, actorId);
                insert.setString(2, "Имя".repeat(40));
                insert.setString(3, "admin@example.com");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private void migrate(String changelog) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
