package ru.taska.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.repository.*;
import ru.taska.repository.builder.SearchQueryBuilder;
import ru.taska.repository.criteria.SearchCriteria;
import ru.taska.repository.executor.SearchQueryExecutor;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.transport.kafka.OutboxEventScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class CollectionOrderingIntegrationTest extends AbstractIT {
    @MockitoBean
    private OutboxEventScheduler outboxScheduler;
    @Autowired
    private DatabaseClient database;
    @Autowired
    private IssueRepository issues;
    @Autowired
    private IssueCommentRepository comments;
    @Autowired
    private IssueLinkRepository links;
    @Autowired
    private IssueWatcherRepository watchers;
    @Autowired
    private ProjectLabelsRepository projectLabels;
    @Autowired
    private IssueLabelsRepository issueLabels;
    @Autowired
    private SearchQueryBuilder searchBuilder;
    @Autowired
    private SearchQueryExecutor searchExecutor;

    @Test
    void collectionsAndSearchUseDocumentedOrderIncludingTiesAndPages() {
        UUID project = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID root = saveIssue(project, actor, 1, "root");
        List<UUID> rowIds = new ArrayList<>();
        List<UUID> targetIds = new ArrayList<>();
        long prefix = UUID.randomUUID().getMostSignificantBits();
        for (int i = 0; i < 3; i++) {
            UUID rowId = new UUID(prefix, i + 1);
            rowIds.add(rowId);
            UUID target = saveIssue(project, actor, i + 2, "ordered-target");
            targetIds.add(target);
            Instant time = Instant.parse(i == 0 ? "2026-01-02T00:00:00Z" : "2026-01-01T00:00:00Z");
            database.sql("UPDATE taska.issues SET created_at = :time WHERE id = :id")
                    .bind("time", time).bind("id", target).fetch().rowsUpdated().block();
            insert("INSERT INTO taska.issue_comments (id, issue_id, project_id, author_user_id, created_at, body) "
                    + "VALUES (:id, :issue, :project, :actor, :time, 'comment')", rowId, root, project, actor, time);
            insert("INSERT INTO taska.issue_watchers (id, issue_id, project_id, user_id, created_by, created_at) "
                    + "VALUES (:id, :issue, :project, :id, :actor, :time)", rowId, root, project, actor, time);
            database.sql("INSERT INTO taska.issue_links "
                            + "(id, source_issue_id, target_issue_id, project_id, created_by, created_at, link_type) "
                            + "VALUES (:id, :issue, :target, :project, :actor, :time, 'BLOCKS')")
                    .bind("id", rowId).bind("issue", root).bind("target", target).bind("project", project)
                    .bind("actor", actor).bind("time", time).fetch().rowsUpdated().block();
            String labelName = List.of("Zulu", "Alpha", "Beta").get(i);
            database.sql("INSERT INTO taska.project_labels (id, project_id, name, color, created_by) "
                            + "VALUES (:id, :project, :name, '#000000', :actor)")
                    .bind("id", rowId).bind("project", project).bind("name", labelName)
                    .bind("actor", actor).fetch().rowsUpdated().block();
            database.sql("INSERT INTO taska.issue_labels (issue_id, label_id, created_by) VALUES (:issue, :label, :actor)")
                    .bind("issue", root).bind("label", rowId).bind("actor", actor).fetch().rowsUpdated().block();
        }
        List<UUID> ascending = List.of(rowIds.get(1), rowIds.get(2), rowIds.get(0));
        List<UUID> descending = List.of(rowIds.get(0), rowIds.get(2), rowIds.get(1));
        assertThat(comments.findActiveByIssueIdOrderByCreatedAtDesc(root).map(c -> c.getId()).collectList().block())
                .containsExactlyElementsOf(descending);
        assertThat(watchers.findByIssueId(root).map(w -> w.getId()).collectList().block())
                .containsExactlyElementsOf(descending);
        assertThat(watchers.findByIssueId(root, 1, 1).map(w -> w.getId()).collectList().block())
                .containsExactly(rowIds.get(2));
        assertThat(links.findAllByIssueId(root).map(l -> l.getId()).collectList().block())
                .containsExactlyElementsOf(ascending);
        assertThat(links.findIssueLinksWithOtherIssues(root).map(l -> l.link().getId()).collectList().block())
                .containsExactlyElementsOf(ascending);
        assertThat(projectLabels.findByProjectIdAndDeletedAtIsNull(project).map(l -> l.getName()).collectList().block())
                .containsExactly("Alpha", "Beta", "Zulu");
        assertThat(issueLabels.findActiveLabelsByIssueId(root).map(l -> l.getName()).collectList().block())
                .containsExactly("Alpha", "Beta", "Zulu");
        assertThat(issueLabels.findActiveLabelsWithIssueId(List.of(root)).map(l -> l.name()).collectList().block())
                .containsExactly("Alpha", "Beta", "Zulu");
        List<UUID> tiedTargets = targetIds.subList(1, 3).stream()
                .sorted((a, b) -> b.toString().compareTo(a.toString())).toList();
        var criteria = SearchCriteria.builder().projectId(project).searchQuery("ordered-target")
                .limit(2).offset(1L).build();
        assertThat(searchExecutor.executeQuery(searchBuilder.build(criteria)).map(Issue::getId).collectList().block())
                .containsExactlyElementsOf(tiedTargets);
    }

    private UUID saveIssue(UUID project, UUID actor, int number, String summary) {
        return issues.save(Issue.builder().projectId(project).issueNumber(number).issueKey(project + "-" + number)
                .summary(summary).statusKey("TODO").issueType(IssueType.TASK).priority(IssuePriority.MEDIUM)
                .reporterId(actor).build()).block().getId();
    }

    private void insert(String sql, UUID id, UUID issue, UUID project, UUID actor, Instant time) {
        database.sql(sql).bind("id", id).bind("issue", issue).bind("project", project)
                .bind("actor", actor).bind("time", time).fetch().rowsUpdated().block();
    }
}
