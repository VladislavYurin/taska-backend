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
import ru.taska.domain.IssueType;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;

import java.util.List;
import java.util.UUID;

class SearchIssuesIT extends AbstractIT {

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
    private static final String ISSUE_STATUS_KEY_TODO = "TODO";
    private static final String ISSUE_STATUS_KEY_DONE = "DONE";
    private static final String ISSUE_STATUS_KEY_IN_PROGRESS = "IN_PROGRESS";

    private final Issue issue1 = buildIssue(1, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Создать документацию", IssuePriority.HIGH);
    private final Issue issue2 = buildIssue(2, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2, "Исправить ошибку в логине", IssuePriority.MEDIUM);
    private final Issue issue3 = buildIssue(3, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1, "Задача готова к релизу", IssuePriority.HIGH);
    private final Issue issue4 = buildIssue(4, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2, "Документация обновлена", IssuePriority.LOW);
    private final Issue issue5 = buildIssue(5, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Создать API для поиска", IssuePriority.MEDIUM);
    private final Issue issue6 = buildIssue(6, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2, "Написать тесты", IssuePriority.HIGH);
    private final Issue issue7 = buildIssue(7, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1, "Документация API завершена", IssuePriority.MEDIUM);
    private final Issue issue8 = buildIssue(8, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2, "Исправить баг с поиском", IssuePriority.HIGH);

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
                        .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));

        Mockito.when(projectServiceStub.listMyProjects(Mockito.any(ru.taska.api.project.v1.ListMyProjectsRequest.class)))
                .thenReturn(Mono.just(ru.taska.api.project.v1.ListMyProjectsResponse.newBuilder()
                        .addProjectResponse(ru.taska.api.project.v1.ProjectResponse.newBuilder()
                                .setId(PROJECT_ID_1.toString())
                                .setProjectKey("TEST1")
                                .setName("Test Project 1")
                                .build())
                        .addProjectResponse(ru.taska.api.project.v1.ProjectResponse.newBuilder()
                                .setId(PROJECT_ID_2.toString())
                                .setProjectKey("TEST2")
                                .setName("Test Project 2")
                                .build())
                        .build()));
    }

    private Issue buildIssue(int number, UUID projectId, String status, UUID assigneeId, String summary, IssuePriority priority) {
        return Issue.builder()
                .projectId(projectId)
                .issueNumber(number)
                .issueKey("TEST-" + number)
                .issueType(IssueType.TASK)
                .summary(summary)
                .description("Описание задачи " + number)
                .statusKey(status)
                .priority(priority)
                .assigneeId(assigneeId)
                .reporterId(REPORTER_ID)
                .version(1)
                .build();
    }

    // ==================== ТЕСТЫ ====================

    @Test
    void shouldSearchIssuesByQueryOnly() {
        String searchQuery = "документ";

        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        searchQuery,           // query
                        null,                  // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        null,                  // priority
                        null,                  // issueType
                        0,                     // page
                        50                     // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getSummary)
                            .containsExactlyInAnyOrder(
                                    "Создать документацию",
                                    "Документация обновлена",
                                    "Документация API завершена"
                            );
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByQueryAndProjectId() {
        String searchQuery = "документ";

        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        searchQuery,           // query
                        PROJECT_ID_1,          // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        null,                  // priority
                        null,                  // issueType
                        0,                     // page
                        50                     // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getSummary)
                            .containsExactlyInAnyOrder(
                                    "Создать документацию",
                                    "Документация обновлена"
                            );
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByProjectIdOnly() {
        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        null,                  // query
                        PROJECT_ID_1,          // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        null,                  // priority
                        null,                  // issueType
                        0,                     // page
                        50                     // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getIssueNumber)
                            .containsExactlyInAnyOrder(1, 2, 3, 4);
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByProjectIdAndPriority() {
        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        null,                  // query
                        PROJECT_ID_1,          // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        IssuePriority.HIGH,    // priority
                        null,                  // issueType
                        0,                     // page
                        50                     // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getIssueNumber)
                            .containsExactlyInAnyOrder(1, 3);
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.getPriority() == IssuePriority.HIGH);
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByQueryOnlyWithPagination() {
        String searchQuery = "документ";

        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        searchQuery,           // query
                        null,                  // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        null,                  // priority
                        null,                  // issueType
                        0,                     // page
                        2                      // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(result.items()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByProjectIdOnlyWithPagination() {
        StepVerifier.create(issueService.searchIssues(
                        REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                        null,                  // query
                        PROJECT_ID_1,          // projectId
                        null,                  // statusKey
                        null,                  // assigneeId
                        null,                  // reporterId
                        null,                  // priority
                        null,                  // issueType
                        1,                     // page (second page)
                        2                      // pageSize
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(4);
                    Assertions.assertThat(result.items()).hasSize(2);
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                })
                .verifyComplete();
    }
}