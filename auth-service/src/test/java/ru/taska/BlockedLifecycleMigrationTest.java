package ru.taska;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BlockedLifecycleMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void legacyBlocksAreBackfilledWithoutTreatingOAuthAsAnUnacceptedInvitation() throws Exception {
        migrate("db/changelog/changes/0000-init.sql");
        migrate("db/changelog/changes/0009-user-created-via.sql");
        Map<UUID, String> expected = new LinkedHashMap<>();
        UUID pending = null;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (String kind : new String[] {"NONE", "PASSWORD", "OAUTH", "OIDC", "OAUTH_ORIGIN", "API_TOKEN", "ACTIVE", "INVITED"}) {
                UUID id = UUID.randomUUID();
                String status = kind.equals("ACTIVE") || kind.equals("INVITED") ? kind : "BLOCKED";
                try (var insert = connection.prepareStatement("INSERT INTO taska.users(id,login,email,display_name,status,created_via) VALUES (?,?,?,'Legacy',?,?)")) {
                    insert.setObject(1,id); insert.setString(2,kind); insert.setString(3,kind+"@example.test");
                    insert.setString(4,status); insert.setString(5,kind.equals("OAUTH_ORIGIN") ? "OAUTH" : "INVITATION"); insert.executeUpdate();
                }
                if (kind.equals("PASSWORD") || kind.equals("API_TOKEN")) {
                    try (var insert = connection.prepareStatement("INSERT INTO taska.credentials(user_id,credential_type,secret_hash,algo) VALUES (?,?,'legacy-hash','BCRYPT')")) {
                        insert.setObject(1,id); insert.setString(2,kind); insert.executeUpdate();
                    }
                } else if (kind.equals("OAUTH") || kind.equals("OIDC")) {
                    try (var insert = connection.prepareStatement("INSERT INTO taska.credentials(user_id,credential_type,provider,subject) VALUES (?,?,'test',?)")) {
                        insert.setObject(1,id); insert.setString(2,kind); insert.setString(3,id.toString()); insert.executeUpdate();
                    }
                }
                expected.put(id, status.equals("BLOCKED") ? (kind.equals("NONE") || kind.equals("API_TOKEN") ? "INVITED" : "ACTIVE") : null);
                if (kind.equals("NONE")) pending = id;
            }
        }
        migrate("db/changelog/db.changelog-master.yaml");
        migrate("db/changelog/db.changelog-master.yaml");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var query = connection.createStatement()) {
            try (var rows = query.executeQuery("SELECT id,blocked_from_status FROM taska.users")) {
                int count=0;
                while (rows.next()) { assertThat(rows.getString(2)).isEqualTo(expected.get(rows.getObject(1,UUID.class))); count++; }
                assertThat(count).isEqualTo(expected.size());
            }
            // Additive compatibility: an old unblock writer can leave the internal marker behind.
            assertThat(query.executeUpdate("UPDATE taska.users SET status='ACTIVE' WHERE id='"+pending+"'")).isEqualTo(1);
            UUID target=pending;
            assertThatThrownBy(() -> query.executeUpdate("UPDATE taska.users SET blocked_from_status='LOCKED' WHERE id='"+target+"'"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var rows=query.executeQuery("SELECT count(*) FROM taska.credentials WHERE secret_hash='legacy-hash'")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }
    private void migrate(String path) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var migration = new Liquibase(path,new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            migration.update(new Contexts(),new LabelExpression());
        }
    }
}
