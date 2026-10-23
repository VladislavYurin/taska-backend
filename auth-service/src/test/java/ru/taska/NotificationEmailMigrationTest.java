package ru.taska;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
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
import ru.taska.entity.OutboxEvent;
import ru.taska.mapper.OutboxEventMapper;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class NotificationEmailMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16");

    @Test
    void backfillIsOnceOnlyAndFutureEmailUpdatesEmitTransactionally() throws Exception {
        migrate("db/changelog/changes/0000-init.sql");
        migrate("db/changelog/changes/0003-user-status-locked.sql");
        UUID active=UUID.randomUUID();
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var insert=connection.prepareStatement("INSERT INTO taska.users(id,login,email,display_name,status) VALUES (?,?,?,'User',?)")) {
            String[][] cases={{"active@example.test","ACTIVE"},{"locked@example.test","LOCKED"},
                    {"invited@example.test","INVITED"},{"blocked@example.test","BLOCKED"},{"   ","ACTIVE"},
                    {"bad\u0085@example.test","ACTIVE"},{"\u2003","ACTIVE"}};
            for(int i=0;i<cases.length;i++) {
                insert.setObject(1,i==0?active:UUID.randomUUID());insert.setString(2,"legacy"+i);
                insert.setString(3,cases[i][0]);insert.setString(4,cases[i][1]);insert.executeUpdate();
            }
        }
        migrate("db/changelog/db.changelog-master.yaml");
        migrate("db/changelog/db.changelog-master.yaml");
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var query=connection.createStatement()) {
            try(var rows=query.executeQuery("SELECT count(*) FROM taska.outbox_events WHERE event_type='UserEmailChanged'")) {
                rows.next();assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try(var update=connection.prepareStatement("UPDATE taska.users SET email=? WHERE id=?")) {
                update.setString(1,"updated@example.test");update.setObject(2,active);update.executeUpdate();update.executeUpdate();
            }
            try(var rows=query.executeQuery("SELECT payload::text FROM taska.outbox_events WHERE request_id='TAS-391-email-change'")) {
                assertThat(rows.next()).isTrue();
                var json=new ObjectMapper();
                var payload=json.readTree(rows.getString(1));
                var published=new OutboxEventMapper(json).toTaskaEvent(OutboxEvent.builder()
                        .aggregateId(active).eventType("UserEmailChanged").payload(payload).build());
                assertThat(published.payload().get("userId").asString()).isEqualTo(active.toString());
                assertThat(published.payload().get("email").asString()).isEqualTo("updated@example.test");
                assertThat(rows.next()).isFalse();
            }
            connection.setAutoCommit(false);
            query.executeUpdate("UPDATE taska.users SET email='rolled-back@example.test' WHERE id='"+active+"'");
            connection.rollback();connection.setAutoCommit(true);
            try(var rows=query.executeQuery("SELECT count(*) FROM taska.outbox_events WHERE payload->>'email'='rolled-back@example.test'")) {
                rows.next();assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void migrate(String path) throws Exception {
        try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var liquibase=new Liquibase(path,new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            liquibase.update(new Contexts(),new LabelExpression());
        }
    }
}
