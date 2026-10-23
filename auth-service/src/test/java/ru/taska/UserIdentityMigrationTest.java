package ru.taska;

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
class UserIdentityMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void upgradesExistingAccountsWithoutChangingIdentityOrCredentials() throws Exception {
        migrate("db/changelog/changes/0000-init.sql");
        UUID id = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("INSERT INTO taska.users(id,login,email,display_name,status) VALUES ('" + id
                    + "','invited_legacy','person@example.com','Person','ACTIVE')");
            statement.execute("INSERT INTO taska.credentials(user_id,credential_type,secret_hash,algo) VALUES ('"
                    + id + "','PASSWORD','existing-password-hash','BCRYPT')");
        }

        migrate("db/changelog/db.changelog-master.yaml");

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT u.id,u.email,u.display_name,u.status,c.secret_hash FROM taska.users u JOIN taska.credentials c ON c.user_id=u.id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("id", UUID.class)).isEqualTo(id);
                assertThat(rows.getString("email")).isEqualTo("person@example.com");
                assertThat(rows.getString("display_name")).isEqualTo("Person");
                assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                assertThat(rows.getString("secret_hash")).isEqualTo("existing-password-hash");
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='taska' AND table_name='users' AND column_name='login'")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            assertThat(statement.executeUpdate("INSERT INTO taska.users(email,display_name) VALUES ('new@example.com','New user')")).isEqualTo(1);
        }
    }

    private void migrate(String changelog) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
