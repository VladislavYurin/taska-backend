package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.repository.IssueRepository;
import ru.taska.transport.kafka.OutboxEventScheduler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
class ProjectIssueCountIntegrationTest extends AbstractIT {
    @MockitoBean private OutboxEventScheduler scheduler;
    @Autowired private IssueRepository repository;
    @Autowired private DatabaseClient database;

    @Test
    void countsOnlyActiveIssuesInRequestedProjectsIncludingDuplicateInputIds() {
        UUID project = UUID.randomUUID(), other = UUID.randomUUID(), empty = UUID.randomUUID();
        for (int i = 1; i <= 4; i++) {
            var issue = repository.save(Issue.builder().projectId(i == 4 ? other : project).issueNumber(i)
                    .issueKey(project + "-" + i).summary("counted").statusKey("TODO").issueType(IssueType.TASK)
                    .priority(IssuePriority.MEDIUM).reporterId(UUID.randomUUID()).build()).block();
            if (i == 3) database.sql("UPDATE taska.issues SET deleted_at = now() WHERE id = :id")
                    .bind("id", issue.getId()).fetch().rowsUpdated().block();
        }
        var rows = repository.countProjectsBatch(List.of(project, empty, project)).collectList().block();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().projectId()).isEqualTo(project);
        assertThat(rows.getFirst().issueCount()).isEqualTo(2);
        var all = repository.countProjectIssueTypesBatch(List.of(project), "TASK", true).collectList().block();
        assertThat(all.getFirst().issueCount()).isEqualTo(3);
        assertThat(repository.countProjectIssueTypesBatch(List.of(project), "TASK", false).collectList().block()
                .getFirst().issueCount()).isEqualTo(2);
        assertThat(repository.countProjectIssueTypesBatch(List.of(project), "BUG", true).collectList().block()).isEmpty();
    }
}
