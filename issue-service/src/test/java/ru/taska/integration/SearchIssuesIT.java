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
import ru.taska.api.project.v1.GetProjectInfoBatchInternalRequest;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ProjectShortInfo;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.aggregate.IssueWithProject;
import ru.taska.domain.util.PageResult;
import ru.taska.repository.IssueRepository;
import ru.taska.service.IssueService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

class SearchIssuesIT extends AbstractIT {

    public static final String PROJECT_KEY_1 = "PRJ1";
    public static final String PROJECT_KEY_2 = "PRJ2";
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
    private static final UUID OTHER_REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
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
                        .setProjectExists(true).setProjectArchived(false)
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

    private void mockGrpcProjectInfoResponse(ProjectShortInfo... projects) {
        GetProjectInfoBatchInternalResponse.Builder response;
        response = GetProjectInfoBatchInternalResponse.newBuilder();
        Arrays.stream(projects)
                .forEach(project -> response.putProjects(project.getId(), project));

        Mockito.when(projectServiceStub.getProjectInfoBatchInternal( Mockito.any(GetProjectInfoBatchInternalRequest.class)))
                .thenReturn(Mono.just(response.build()));
    }

    private ProjectShortInfo projectInfo(UUID projectId, String key, String name) {
        return ProjectShortInfo.newBuilder()
                .setId(projectId.toString())
                .setKey(key)
                .setName(name)
                .build();
    }

    private PageResult<IssueWithProject> search(
            String query,
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType
    ) {
        return issueService.searchIssues(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                query, projectId, statusKey, assigneeId, reporterId, priority, issueType,
                0, 50
        ).block();
    }

    private void assertFound(PageResult<IssueWithProject> result, Integer... issueNumbers) {
        Assertions.assertThat(result.totalCount()).isEqualTo(issueNumbers.length);
        Assertions.assertThat(result.items())
                .extracting(item -> item.issue().getIssueNumber())
                .containsExactlyInAnyOrder(issueNumbers);
    }

    @Autowired private ru.taska.mapper.IssueMapper mapper;

    @Test
    void searchUsesOneBatchForProjectSpecificTypeMetadata() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1").toBuilder()
                        .addIssueTypes(ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder()
                                .setCode("TASK").setName("Заявка").setColor("teal").setShape("diamond")
                                .setIcon("headset").setLegacyIcon("headset")).build(),
                projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2").toBuilder()
                        .addIssueTypes(ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder()
                                .setCode("TASK").setName("Внутренняя задача").setColor("purple").setShape("circle")
                                .setLegacyIcon("check-square")).build());
        var result = issueService.searchIssues(REQUEST_ID, NODE_ID, ACTOR_USER_ID,
                null, null, null, null, null, null, null, 0, 50).block();
        Assertions.assertThat(result.items()).hasSize(8);
        for (var item : result.items()) {
            var proto = mapper.toIssueShortProto(item);
            Assertions.assertThat(proto.getIssueTypeName()).isEqualTo(item.issue().getProjectId().equals(PROJECT_ID_1)
                    ? "Заявка" : "Внутренняя задача");
            Assertions.assertThat(proto.getIssueTypeColor()).isNotBlank();
            // TAS-432: the hit carries the registry shape; a type without an icon has no issue_type_icon.
            boolean first = item.issue().getProjectId().equals(PROJECT_ID_1);
            Assertions.assertThat(proto.getIssueTypeShape()).isEqualTo(first ? "diamond" : "circle");
            Assertions.assertThat(proto.hasIssueTypeIcon()).isEqualTo(first);
            if (first) Assertions.assertThat(proto.getIssueTypeIcon()).isEqualTo("headset");
        }
        var request = org.mockito.ArgumentCaptor.forClass(GetProjectInfoBatchInternalRequest.class);
        Mockito.verify(projectServiceStub).getProjectInfoBatchInternal(request.capture());
        Assertions.assertThat(request.getValue().getBody().getProjectIdsList())
                .containsExactlyInAnyOrder(PROJECT_ID_1.toString(), PROJECT_ID_2.toString());
    }

    // ==================== ТЕСТЫ ====================

    @Test
    void shouldSearchIssuesByQueryOnly() {
        String searchQuery = "документ";

        mockGrpcProjectInfoResponse(
                projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"),
                projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2")
        );

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
                            .extracting(item -> item.issue().getSummary())
                            .containsExactlyInAnyOrder(
                                    "Создать документацию",
                                    "Документация обновлена",
                                    "Документация API завершена"
                            );
                    Assertions.assertThat(result.items())
                            .extracting(item -> item.project().key())
                            .containsExactlyInAnyOrder(PROJECT_KEY_1, PROJECT_KEY_2, PROJECT_KEY_1);
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByQueryAndProjectId() {
        String searchQuery = "документ";

        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

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
                            .extracting(item -> item.issue().getSummary())
                            .containsExactlyInAnyOrder(
                                    "Создать документацию",
                                    "Документация обновлена"
                            );
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.issue().getProjectId().equals(PROJECT_ID_1));
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::project)
                            .allMatch(project -> project.key().equals(PROJECT_KEY_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByProjectIdOnly() {
        mockGrpcProjectInfoResponse( projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

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
                            .extracting(item -> item.issue().getIssueNumber())
                            .containsExactlyInAnyOrder(1, 2, 3, 4);
                    Assertions.assertThat(result.items())
                            .allMatch(issue -> issue.issue().getProjectId().equals(PROJECT_ID_1));
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::project)
                            .allMatch(project -> project.key().equals(PROJECT_KEY_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByProjectIdAndPriority() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

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
                            .extracting(item -> item.issue().getIssueNumber())
                            .containsExactlyInAnyOrder(1, 3);
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::issue)
                            .allMatch(issue -> issue.getPriority() == IssuePriority.HIGH);
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::issue)
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::project)
                            .allMatch(project -> project.key().equals(PROJECT_KEY_1));
                })
                .verifyComplete();
    }

    @Test
    void shouldSearchIssuesByQueryOnlyWithPagination() {
        String searchQuery = "документ";

        mockGrpcProjectInfoResponse(
                projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"),
                projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2")
        );

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
                    Assertions.assertThat(result.items())
                            .extracting(item -> item.project().key())
                            .containsExactlyInAnyOrder(PROJECT_KEY_1, PROJECT_KEY_2);
                })
                .verifyComplete();
    }

    @Test
    void shouldFilterByStatusKey() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search(null, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, null, null, null, null), 3, 4);
    }

    @Test
    void shouldFilterByAssigneeId() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2"));

        assertFound(search(null, PROJECT_ID_2, null, ASSIGNEE_ID_2, null, null, null), 6, 8);
    }

    @Test
    void shouldFilterByReporterId() {
        issueRepository.save(buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Чужая задача", IssuePriority.LOW)
                .toBuilder().reporterId(OTHER_REPORTER_ID).build()).block();
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search(null, PROJECT_ID_1, null, null, OTHER_REPORTER_ID, null, null), 9);
    }

    @Test
    void shouldFilterByIssueType() {
        issueRepository.save(buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Упал логин", IssuePriority.HIGH)
                .toBuilder().issueType(IssueType.BUG).build()).block();
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search(null, PROJECT_ID_1, null, null, null, null, IssueType.BUG), 9);
    }

    @Test
    void shouldCombineQueryWithFiltersInAllProjects() {
        mockGrpcProjectInfoResponse(
                projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"),
                projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2")
        );

        assertFound(search("документ", null, ISSUE_STATUS_KEY_DONE, null, null, null, null), 4, 7);
        assertFound(search("документ", null, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2, null, null, null), 4);
    }

    @Test
    void shouldSearchByIssueKeyIgnoringCase() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2"));

        assertFound(search("test-5", null, null, null, null, null, null), 5);
    }

    @Test
    void shouldSearchByDescription() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_2, PROJECT_KEY_2, "Project 2"));

        assertFound(search("задачи 7", null, null, null, null, null, null), 7);
    }

    @Test
    void shouldExcludeDeletedIssues() {
        issueRepository.save(buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Документация удалена", IssuePriority.LOW)
                .toBuilder().deletedAt(Instant.now()).build()).block();
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search(null, PROJECT_ID_1, null, null, null, null, null), 1, 2, 3, 4);
        assertFound(search("документ", PROJECT_ID_1, null, null, null, null, null), 1, 4);
    }

    @Test
    void shouldIgnoreBlankStatusKey() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search(null, PROJECT_ID_1, "  ", null, null, null, null), 1, 2, 3, 4);
    }

    @Test
    void shouldTreatLikeWildcardsLiterally() {
        issueRepository.save(buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, "Покрытие 50%_тестами", IssuePriority.LOW))
                .block();
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

        assertFound(search("50%_", null, null, null, null, null, null), 9);
        assertFound(search("%%%", null, null, null, null, null, null));
        assertFound(search("___", null, null, null, null, null, null));
    }

    @Test
    void shouldIgnoreProjectFilterForEmptyProjectList() {
        StepVerifier.create(issueRepository.searchIssuesInProjects(List.of(), null, null, null, null, null, null, 50, 0)
                        .map(Issue::getIssueNumber)
                        .collectList())
                .assertNext(numbers -> Assertions.assertThat(numbers).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8))
                .verifyComplete();
        StepVerifier.create(issueRepository.countSearchIssuesInProjects(List.of(), null, null, null, null, null, null))
                .expectNext(8L)
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyPageWhenNothingMatches() {
        mockGrpcProjectInfoResponse();

        assertFound(search("несуществующая", null, null, null, null, null, null));
    }

    @Test
    void shouldSearchIssuesByProjectIdOnlyWithPagination() {
        mockGrpcProjectInfoResponse(projectInfo(PROJECT_ID_1, PROJECT_KEY_1, "Project 1"));

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
                            .extracting(IssueWithProject::issue)
                            .allMatch(issue -> issue.getProjectId().equals(PROJECT_ID_1));
                    Assertions.assertThat(result.items())
                            .extracting(IssueWithProject::project)
                            .allMatch(project -> project.key().equals(PROJECT_KEY_1));
                })
                .verifyComplete();
    }
}
