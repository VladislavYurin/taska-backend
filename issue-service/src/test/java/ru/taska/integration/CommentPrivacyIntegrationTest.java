package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueCommentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.CommentService;
import ru.taska.service.IssueService;
import ru.taska.transport.kafka.OutboxEventScheduler;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class CommentPrivacyIntegrationTest extends AbstractIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SECRET = "private-comment-text";

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;
    @MockitoBean
    private OutboxEventScheduler outboxEventScheduler;
    @Autowired
    private CommentService comments;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private IssueCommentRepository commentRepository;
    @Autowired
    private IssueMapper mapper;
    @Autowired
    private DatabaseClient database;

    @BeforeEach
    void setUp() {
        issueRepository.deleteAll().block();
        role(ProjectRole.PROJECT_ROLE_MEMBER);
    }

    @Test
    void commentLifecycle_keepsTextOnlyInCommentStorageAndMetadataInHistoryAndOutbox() {
        Issue issue = createIssue();
        var comment = comments.addComment("privacy", "test", issue.getId(), ACTOR, SECRET).block();
        assertThat(comment).isNotNull();
        var updated = comments.updateComment("privacy", "test", issue.getId(), comment.getId(), ACTOR,
                SECRET + " edited").block();
        assertThat(updated.getBody()).isEqualTo(SECRET + " edited");
        comments.deleteComment("privacy", "test", issue.getId(), comment.getId(), ACTOR).block();
        assertThat(commentRepository.findActiveById(comment.getId()).block()).isNull();
        assertThat(commentRepository.countActiveByIssueId(issue.getId()).block()).isZero();

        for (String table : List.of("issue_history", "outbox_events")) {
            String column = table.equals("issue_history") ? "issue_id" : "aggregate_id";
            var payloads = database.sql("SELECT payload::text FROM taska." + table + " WHERE " + column + " = :id")
                    .bind("id", issue.getId()).map(row -> row.get(0, String.class)).all().collectList().block();
            assertThat(payloads).hasSize(3).allSatisfy(payload -> {
                assertThat(payload).contains(comment.getId().toString()).doesNotContain(SECRET);
                assertThat(payload).doesNotContain("\"body\"", "\"oldBody\"", "\"newBody\"");
            });
        }
        role(ProjectRole.PROJECT_ROLE_VIEWER);
        var response = mapper.toIssueDetailsProto(issues.getIssue("privacy", "test", issue.getId(), ACTOR).block());
        assertThat(response.getHistoryList()).hasSize(3).allSatisfy(event ->
                assertThat(event.getPayload()).doesNotContain(SECRET, "\"body\"", "\"oldBody\"", "\"newBody\""));
    }

    @Test
    void migration_redactsAllCommentEventsAndStatusesWithoutChangingOtherHistory() throws Exception {
        Issue issue = createIssue();
        List<String> types = List.of("COMMENT_CREATED", "COMMENT_UPDATED", "COMMENT_DELETED", "UPDATED");
        List<String> events = List.of("CommentCreated", "CommentUpdated", "CommentDeleted", "IssueUpdated");
        List<String> statuses = List.of("NEW", "FAILED", "PUBLISHED", "NEW");
        String payload = "{\"commentId\":\"keep\",\"body\":\"secret\",\"oldBody\":\"old\",\"newBody\":\"new\"}";
        for (int i = 0; i < types.size(); i++) {
            database.sql("INSERT INTO taska.issue_history (issue_id, event_type, actor_user_id, occurred_at, payload) "
                    + "VALUES (:id, :type, :actor, now(), CAST(:payload AS jsonb))")
                    .bind("id", issue.getId()).bind("type", types.get(i)).bind("actor", ACTOR)
                    .bind("payload", payload).then().block();
            database.sql("INSERT INTO taska.outbox_events (aggregate_type, aggregate_id, event_type, payload, status) "
                    + "VALUES ('ISSUE', :id, :type, CAST(:payload AS jsonb), :status)")
                    .bind("id", issue.getId()).bind("type", events.get(i)).bind("payload", payload)
                    .bind("status", statuses.get(i)).then().block();
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var script = new ClassPathResource("db/changelog/changes/0009-redact-comment-event-text.sql");
            ScriptUtils.executeSqlScript(connection, script);
            ScriptUtils.executeSqlScript(connection, script);
        }
        for (String table : List.of("issue_history", "outbox_events")) {
            String column = table.equals("issue_history") ? "issue_id" : "aggregate_id";
            var rows = database.sql("SELECT event_type, payload::text FROM taska." + table + " WHERE " + column + " = :id")
                    .bind("id", issue.getId()).map(row -> List.of(row.get(0, String.class), row.get(1, String.class)))
                    .all().collectList().block();
            assertThat(rows).hasSize(4).allSatisfy(row -> {
                assertThat(row.get(1)).contains("keep");
                if (row.get(0).equals("UPDATED") || row.get(0).equals("IssueUpdated")) {
                    assertThat(row.get(1)).contains("secret");
                } else {
                    assertThat(row.get(1)).doesNotContain("secret", "\"body\"", "\"oldBody\"", "\"newBody\"");
                }
            });
        }
        var states = database.sql("SELECT status FROM taska.outbox_events WHERE aggregate_id = :id ORDER BY event_type")
                .bind("id", issue.getId()).map(row -> row.get(0, String.class)).all().collectList().block();
        assertThat(states).containsExactlyInAnyOrderElementsOf(statuses);
    }

    private void role(ProjectRole role) {
        when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setProjectExists(true).setIsMember(true).setRole(role).build()));
    }

    private Issue createIssue() {
        return issueRepository.save(Issue.builder().projectId(UUID.randomUUID()).issueNumber(1).issueKey("PRV-1")
                .issueType(IssueType.TASK).summary("Privacy regression").statusKey("TODO")
                .priority(IssuePriority.MEDIUM).reporterId(ACTOR).version(1).build()).block();
    }
}
