package ru.taska.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.taska.domain.IssueStatus.IN_PROGRESS;
import static ru.taska.domain.IssueStatus.TODO;


class ListIssuesIT extends AbstractIT {

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    private static final UUID PROJECT_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNEE_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ASSIGNEE_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final Issue issue1 = buildIssue(1, PROJECT_ID_1, TODO, ASSIGNEE_ID_1);
    private final Issue issue2 = buildIssue(2, PROJECT_ID_1, TODO, ASSIGNEE_ID_2);
    private final Issue issue3 = buildIssue(3, PROJECT_ID_1, IssueStatus.DONE, ASSIGNEE_ID_1);
    private final Issue issue4 = buildIssue(4, PROJECT_ID_1, IssueStatus.DONE, ASSIGNEE_ID_2);
    private final Issue issue5 = buildIssue(5, PROJECT_ID_2, TODO, ASSIGNEE_ID_1);
    private final Issue issue6 = buildIssue(6, PROJECT_ID_2, TODO, ASSIGNEE_ID_2);
    private final Issue issue7 = buildIssue(7, PROJECT_ID_2, IssueStatus.DONE, ASSIGNEE_ID_1);
    private final Issue issue8 = buildIssue(8, PROJECT_ID_2, IssueStatus.DONE, ASSIGNEE_ID_2);

    private final List<Issue> issues = List.of(issue1, issue2, issue3, issue4, issue5, issue6, issue7, issue8);

    @BeforeEach
    void refillDb() {
        issueRepository.deleteAll().block();
        issues.forEach(issue -> issueRepository.save(issue).block());
    }

    private Issue buildIssue(int number, UUID projectId, IssueStatus status, UUID assigneeId) {
        return Issue.builder()
                .projectId(projectId)
                .issueNumber(number)
                .issueKey("TEST-" + number)
                .issueType(IssueType.TASK)
                .summary("Тестовая задача")
                .statusKey(status)
                .priority(IssuePriority.MEDIUM)
                .assigneeId(assigneeId)
                .reporterId(REPORTER_ID)
                .version(1)
                .build();
    }

    @Test
    void shouldReturnIssuesFromGivenProject() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, null, null, 0, 50))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(4);
                    assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 2, 3, 4);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndAssignee() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, null, ASSIGNEE_ID_1, 0, 50))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(2);
                    assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatus() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, TODO, null, 0, 50))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(2);
                    assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatusAndAssignee() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, TODO, ASSIGNEE_ID_1, 0, 50))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(1);
                    assertThat(result.items().stream().map(Issue::getIssueNumber).toList())
                            .containsExactly(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageIfNothingMatches() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, IN_PROGRESS, ASSIGNEE_ID_1, 0, 50))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isZero();
                    assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnFirstPageWhenPaginationApplied() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, null, null, 0, 2))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(4);
                    assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnSecondPageWhenPaginationApplied() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, null, null, 1, 2))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(4);
                    assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageBeyondLastPage() {
        StepVerifier.create(issueService.listIssues(PROJECT_ID_1, null, null, 2, 2))
                .assertNext(result -> {
                    assertThat(result.totalCount()).isEqualTo(4);
                    assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }
}
