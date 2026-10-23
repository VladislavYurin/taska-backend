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
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueLabels;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.entity.ProjectLabels;
import ru.taska.domain.IssueType;
import ru.taska.domain.dto.labels.IssueWithLabels;
import ru.taska.domain.util.PageResult;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.IssueService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class PaginationIssuesIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ProjectLabelsRepository projectLabelsRepository;

    @Autowired
    private IssueLabelsRepository issueLabelsRepository;

    private static final UUID PROJECT_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNEE_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ASSIGNEE_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String ISSUE_STATUS_KEY_TODO = "TODO";
    private static final String ISSUE_STATUS_KEY_DONE = "DONE";
    private static final String ISSUE_STATUS_KEY_IN_PROGRESS = "IN_PROGRESS";

    private final Issue issue1 = buildIssue(1, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1);
    private final Issue issue2 = buildIssue(2, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2);
    private final Issue issue3 = buildIssue(3, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1);
    private final Issue issue4 = buildIssue(4, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2);
    private final Issue issue5 = buildIssue(5, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1);
    private final Issue issue6 = buildIssue(6, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2);
    private final Issue issue7 = buildIssue(7, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1);
    private final Issue issue8 = buildIssue(8, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2);

    private final List<Issue> issues = List.of(issue1, issue2, issue3, issue4, issue5, issue6, issue7, issue8);

    /** Метка проекта 1 на задачах 1, 2, 3 и (некорректно, из чужого проекта) на задаче 5. */
    private UUID labelId1;
    /** Метка проекта 1 на задачах 1 и 4. */
    private UUID labelId2;
    /** Метка проекта 1 без задач. */
    private UUID unusedLabelId;

    @BeforeEach
    void refillDb() {
        issueRepository.deleteAll().block();
        projectLabelsRepository.deleteAll().block();
        issues.forEach(issue -> issueRepository.save(issue).block());

        labelId1 = createLabel("label-1");
        labelId2 = createLabel("label-2");
        unusedLabelId = createLabel("label-unused");
        List.of(1, 2, 3, 5).forEach(number -> attachLabel(number, labelId1));
        List.of(1, 4).forEach(number -> attachLabel(number, labelId2));
    }

    private UUID createLabel(String name) {
        return projectLabelsRepository.save(ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name(name)
                        .color("#FF0000")
                        .createdBy(REPORTER_ID)
                        .createdAt(Instant.now())
                        .build())
                .map(ProjectLabels::getId)
                .block();
    }

    private void attachLabel(int issueNumber, UUID labelId) {
        issueLabelsRepository.save(IssueLabels.builder()
                        .issueId(findIssueId(issueNumber))
                        .labelId(labelId)
                        .createdBy(REPORTER_ID)
                        .createdAt(Instant.now())
                        .build())
                .block();
    }

    private UUID findIssueId(int issueNumber) {
        return issueRepository.findAll()
                .filter(issue -> issue.getIssueNumber() == issueNumber)
                .single()
                .map(Issue::getId)
                .block();
    }

    @BeforeEach
    void setUp() {
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));
    }

    private Issue buildIssue(int number, UUID projectId, String status, UUID assigneeId) {
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
                        null, null, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).sorted().toList())
                            .containsExactly(1, 2, 3, 4);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndAssignee() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, ASSIGNEE_ID_1, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).sorted().toList())
                            .containsExactly(1, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatus() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        ISSUE_STATUS_KEY_TODO, null, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).sorted().toList())
                            .containsExactly(1, 2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageForBlankStatus() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        "", null, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnIssuesFromGivenProjectAndStatusAndAssignee() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).toList())
                            .containsExactly(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageIfNothingMatches() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        ISSUE_STATUS_KEY_IN_PROGRESS, ASSIGNEE_ID_1, null, 0, 50)
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
                        null, null, null, 0, 2)
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
                        null, null, null, 1, 2)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnPagesOrderedByCreatedAt() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, null, 1, 2)
                )
                .assertNext(result -> Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).toList())
                        .containsExactly(3, 4))
                .verifyComplete();
    }

    @Test
    void shouldNotReturnDeletedIssues() {
        Issue deleted = issueRepository.findAll()
                .filter(issue -> issue.getIssueNumber() == 1 && PROJECT_ID_1.equals(issue.getProjectId()))
                .single()
                .block();
        deleted.setDeletedAt(Instant.now());
        issueRepository.save(deleted).block();

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        ISSUE_STATUS_KEY_TODO, null, null, 0, 50)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).toList())
                            .containsExactly(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageBeyondLastPage() {
        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                        null, null, null, 2, 2)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    // ==================== Фильтр по метке ====================

    private Mono<PageResult<IssueWithLabels>> listByLabel(UUID labelId, String statusKey, UUID assigneeId, int page, int pageSize) {
        return issueService.listIssues(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                statusKey, assigneeId, labelId, page, pageSize);
    }

    private static List<Integer> issueNumbers(PageResult<IssueWithLabels> result) {
        return result.items().stream().map(issueWithLabels -> issueWithLabels.issue().getIssueNumber()).toList();
    }

    @Test
    void shouldReturnOnlyIssuesWithGivenLabelFromGivenProject() {
        StepVerifier.create(listByLabel(labelId1, null, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(issueNumbers(result)).containsExactlyInAnyOrder(1, 2, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotDuplicateIssueWithSeveralLabels() {
        StepVerifier.create(listByLabel(labelId2, null, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(issueNumbers(result)).containsExactlyInAnyOrder(1, 4);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageForLabelWithoutIssues() {
        StepVerifier.create(listByLabel(unusedLabelId, null, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterByLabelAndStatus() {
        StepVerifier.create(listByLabel(labelId1, ISSUE_STATUS_KEY_TODO, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(issueNumbers(result)).containsExactlyInAnyOrder(1, 2);
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterByLabelAndAssignee() {
        StepVerifier.create(listByLabel(labelId1, null, ASSIGNEE_ID_1, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(issueNumbers(result)).containsExactlyInAnyOrder(1, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterByLabelAndStatusAndAssignee() {
        StepVerifier.create(listByLabel(labelId1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(issueNumbers(result)).containsExactly(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageWhenLabelAndOtherFiltersDoNotMatch() {
        StepVerifier.create(listByLabel(labelId1, ISSUE_STATUS_KEY_IN_PROGRESS, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageForLabelAndBlankStatus() {
        StepVerifier.create(listByLabel(labelId1, "", null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldNotReturnDeletedIssuesWithLabel() {
        Issue deleted = issueRepository.findAll()
                .filter(issue -> issue.getIssueNumber() == 1)
                .single()
                .block();
        deleted.setDeletedAt(Instant.now());
        issueRepository.save(deleted).block();

        StepVerifier.create(listByLabel(labelId1, null, null, 0, 50))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(issueNumbers(result)).containsExactlyInAnyOrder(2, 3);
                })
                .verifyComplete();
    }

    @Test
    void shouldKeepTimeSpentForIssuesWithLabel() {
        Issue logged = issueRepository.findAll()
                .filter(issue -> issue.getIssueNumber() == 1)
                .single()
                .block();
        logged.setTimeSpentMinutes(75);
        issueRepository.save(logged).block();

        StepVerifier.create(listByLabel(labelId1, null, null, 0, 50))
                .assertNext(result -> Assertions.assertThat(result.items().stream()
                                .map(IssueWithLabels::issue)
                                .filter(issue -> issue.getIssueNumber() == 1)
                                .map(Issue::getTimeSpentMinutes)
                                .toList())
                        .containsExactly(75))
                .verifyComplete();
    }

    @Test
    void shouldReturnLabelPagesOrderedByCreatedAtDesc() {
        StepVerifier.create(listByLabel(labelId1, null, null, 0, 2))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(issueNumbers(result)).containsExactly(3, 2);
                })
                .verifyComplete();

        StepVerifier.create(listByLabel(labelId1, null, null, 1, 2))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(issueNumbers(result)).containsExactly(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyLabelPageBeyondLastPage() {
        StepVerifier.create(listByLabel(labelId1, null, null, 2, 2))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }
}
