package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.IssueBoardResponse;
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

public class ListIssuesForBoardIT extends AbstractIT {

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
    private static final IssueType ISSUE_TYPE_BUG = IssueType.BUG;
    private static final IssueType ISSUE_TYPE_TASK = IssueType.TASK;
    private static final IssueType ISSUE_TYPE_STORY = IssueType.STORY;

    private final Issue issue1 = buildIssue(1, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_BUG);
    private final Issue issue2 = buildIssue(2, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2, ISSUE_TYPE_STORY);
    private final Issue issue3 = buildIssue(3, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1, ISSUE_TYPE_TASK);
    private final Issue issue4 = buildIssue(4, PROJECT_ID_1, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2, ISSUE_TYPE_BUG);
    private final Issue issue5 = buildIssue(5, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_STORY);
    private final Issue issue6 = buildIssue(6, PROJECT_ID_2, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_2, ISSUE_TYPE_TASK);
    private final Issue issue7 = buildIssue(7, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_1, ISSUE_TYPE_BUG);
    private final Issue issue8 = buildIssue(8, PROJECT_ID_2, ISSUE_STATUS_KEY_DONE, ASSIGNEE_ID_2, ISSUE_TYPE_STORY);

    private final List<Issue> issues = List.of(issue1, issue2, issue3, issue4, issue5, issue6, issue7, issue8);

    @BeforeEach
    void refillDb(){
        issueRepository.deleteAll().block();
        issues.forEach(issue -> issueRepository.save(issue).block());
    }

    @BeforeEach
    void setup(){
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                                .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                                .setIsMember(true)
                                .setProjectExists(true)
                        .build()));
    }

    @Test
    void shouldReturnIssueFromGivenProject(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                null, null, null, true, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).hasSize(4);

            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-2", "TEST-3", "TEST-4");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByIssueType(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                ISSUE_TYPE_BUG, null, null, true, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).hasSize(2);

            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-4");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByAssignee(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                null, ASSIGNEE_ID_1, null, true, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).hasSize(2);

            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-3");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByStatusKey(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                null, null, ISSUE_STATUS_KEY_TODO, true, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).hasSize(2);

            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-2");
        }).verifyComplete();
    }

    @Test
    void shouldExcludeDoneIssuesWhenIncludeDoneIsFalse(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                null, null, null, false, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).hasSize(2);

            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-2");
        }).verifyComplete();
    }

    @Test
    void shouldReturnEmptyListWhenStatusKeyDoesNotMatch(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, PROJECT_ID_1, ACTOR_USER_ID,
                null, null, ISSUE_STATUS_KEY_IN_PROGRESS, true, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).isEmpty();
        }).verifyComplete();
    }

    private Issue buildIssue(int number, UUID projectId, String statusKey, UUID assigneeId, IssueType issueType){
        return Issue.builder()
                .projectId(projectId)
                .issueNumber(number)
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .issueType(issueType)
                .issueKey("TEST-" + number)
                .summary("Тестовая задача")
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .version(1)
                .build();

    }

}
