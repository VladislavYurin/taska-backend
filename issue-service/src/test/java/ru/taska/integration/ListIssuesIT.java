package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;

import java.util.List;
import java.util.UUID;

class ListIssuesIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    private static final UUID PROJECT_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNEE_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ASSIGNEE_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";

    private final Issue issue1 = buildIssue(1, PROJECT_ID_1, IssueStatus.TODO, ASSIGNEE_ID_1);
    private final Issue issue2 = buildIssue(2, PROJECT_ID_1, IssueStatus.TODO, ASSIGNEE_ID_2);
    private final Issue issue3 = buildIssue(3, PROJECT_ID_1, IssueStatus.DONE, ASSIGNEE_ID_1);
    private final Issue issue4 = buildIssue(4, PROJECT_ID_1, IssueStatus.DONE, ASSIGNEE_ID_2);
    private final Issue issue5 = buildIssue(5, PROJECT_ID_2, IssueStatus.TODO, ASSIGNEE_ID_1);
    private final Issue issue6 = buildIssue(6, PROJECT_ID_2, IssueStatus.TODO, ASSIGNEE_ID_2);
    private final Issue issue7 = buildIssue(7, PROJECT_ID_2, IssueStatus.DONE, ASSIGNEE_ID_1);
    private final Issue issue8 = buildIssue(8, PROJECT_ID_2, IssueStatus.DONE, ASSIGNEE_ID_2);

    private final List<Issue> issues = List.of(issue1, issue2, issue3, issue4, issue5, issue6, issue7, issue8);

    @BeforeEach
    void refillDb() {
        issueRepository.deleteAll().block();
        issues.forEach(issue -> issueRepository.save(issue).block());
    }

    @BeforeEach
    void setUp() {
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(ProjectRole.MEMBER)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));
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
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 2, 3, 4);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndAssignee() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, ASSIGNEE_ID_1, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatus() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        IssueStatus.TODO, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items().stream().map(Issue::getIssueNumber).sorted().toList())
                            .containsExactly(1, 2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatusAndAssignee() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        IssueStatus.TODO, ASSIGNEE_ID_1, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items().stream().map(Issue::getIssueNumber).toList())
                            .containsExactly(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageIfNothingMatches() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        IssueStatus.IN_PROGRESS, ASSIGNEE_ID_1, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnFirstPageWhenPaginationApplied() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, 0, 2)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnSecondPageWhenPaginationApplied() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, 1, 2)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageBeyondLastPage() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, 2, 2)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }
}
