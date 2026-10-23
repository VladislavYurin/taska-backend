package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.r2dbc.core.DatabaseClient;

class CommentNotificationMigrationIntegrationTest extends AbstractIT {

    @Autowired
    private DatabaseClient database;

    @Test
    void migration_redactsAllStoredCommentPreviewsAndPreservesOtherNotifications() throws Exception {
        UUID user = UUID.randomUUID();
        UUID issue = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            var insert = database.sql("""
                    INSERT INTO taska.notifications
                        (user_id, notification_type, title, body, source_event_id, issue_id, issue_key, read_at)
                    VALUES (:user, :type, 'title', 'secret-preview', :event, :issue, :key, now())
                    """).bind("user", user).bind("event", UUID.randomUUID())
                    .bind("type", i < 3 ? "ISSUE_COMMENT_CREATED" : "ISSUE_UPDATED");
            insert = i == 2 ? insert.bindNull("issue", UUID.class) : insert.bind("issue", issue);
            insert = i == 0 || i == 3 ? insert.bind("key", "PRV-1") : insert.bindNull("key", String.class);
            insert.then().block();
        }
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var script = new ClassPathResource("db/changelog/changes/0008-redact-comment-notification-previews.sql");
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);
        }
        var bodies = database.sql("SELECT body FROM taska.notifications WHERE user_id = :user")
                .bind("user", user).map(row -> row.get(0, String.class)).all().collectList().block();
        assertThat(bodies).containsExactlyInAnyOrder("Новый комментарий к задаче PRV-1",
                "Новый комментарий к задаче " + issue, "Новый комментарий", "secret-preview");
        Long readCount = database.sql("SELECT count(*) FROM taska.notifications WHERE user_id = :user AND read_at IS NOT NULL")
                .bind("user", user).map(row -> row.get(0, Long.class)).one().block();
        assertThat(readCount).isEqualTo(4);
    }
}
