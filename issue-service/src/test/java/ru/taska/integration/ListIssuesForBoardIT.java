package ru.taska.integration;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.v1.IssueBoardResponse;
import ru.taska.api.issue.v1.ListIssuesForBoardRequest;
import ru.taska.api.issue.v1.ListIssuesForBoardRequestBody;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.IssueLabels;
import ru.taska.domain.entity.ProjectLabels;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.IssueService;
import ru.taska.transport.grpc.GrpcIssueService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ListIssuesForBoardIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private GrpcIssueService grpcIssueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ru.taska.repository.IssueAttachmentRepository attachments;

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

    private List<Issue> savedIssues;

    @BeforeEach
    void refillDb(){
        attachments.deleteAll().block();
        issueLabelsRepository.deleteAll().block();
        projectLabelsRepository.deleteAll().block();
        issueRepository.deleteAll().block();
        savedIssues = issues.stream()
                .map(issue -> issueRepository.save(issue).block())
                .toList();
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
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, null, null)
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
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null,  null, ISSUE_TYPE_BUG, true, List.of("DONE"),null,  null, null, null)
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
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, ASSIGNEE_ID_1, null, true, List.of("DONE"), null, null, null, null)
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
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                ISSUE_STATUS_KEY_TODO, null, null, true, List.of("DONE"), null, null, null, null)
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
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, false, List.of("DONE"), null,null, null, null)
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
                REQUEST_ID, NODE_ID,  ACTOR_USER_ID,  PROJECT_ID_1,
                ISSUE_STATUS_KEY_IN_PROGRESS, null, null, true, List.of("DONE"), null, null, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).isEmpty();
        }).verifyComplete();
    }

    @Test
    void shouldLimitResultsPerColumnNotTotalWhenPageSizePerColumnIsSet(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, 1, null, null)
        ).assertNext(result -> {
            Map<String, List<IssueBoardResponse>> byStatus = result.stream()
                    .collect(Collectors.groupingBy(IssueBoardResponse::getStatusKey));

            Assertions.assertThat(byStatus.keySet())
                    .containsExactlyInAnyOrder(ISSUE_STATUS_KEY_TODO, ISSUE_STATUS_KEY_DONE);

            byStatus.values().forEach(issuesInColumn ->
                    Assertions.assertThat(issuesInColumn).hasSize(1)
            );
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByPriority(){
        issueRepository.save(
                buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_TASK, IssuePriority.HIGH)
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, IssuePriority.HIGH, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-9");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesOfAllTypesWhenIssueTypeIsNull(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueType)
                            .distinct()
                            .toList()
            ).containsExactlyInAnyOrder(
                    ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_BUG,
                    ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_STORY,
                    ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_TASK
            );
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByQueryCaseInsensitive(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, null, "test-3")
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-3");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByQueryWithPageSizePerColumn(){
        issueRepository.save(
                buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_TASK)
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, 1, null, "Тестовая")
        ).assertNext(result -> {
            Map<String, List<IssueBoardResponse>> byStatus = result.stream()
                    .collect(Collectors.groupingBy(IssueBoardResponse::getStatusKey));

            byStatus.values().forEach(issuesInColumn ->
                    Assertions.assertThat(issuesInColumn).hasSize(1)
            );
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByPriorityWithPageSizePerColumn(){
        issueRepository.save(
                buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_TASK, IssuePriority.HIGH)
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, 1, IssuePriority.HIGH, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-9");
        }).verifyComplete();
    }

    @Test
    void shouldReturnIssuesFilteredByLabelId(){
        ProjectLabels label = projectLabelsRepository.save(
                ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name("urgent")
                        .color("#FF0000")
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();

        issueLabelsRepository.save(
                IssueLabels.builder()
                        .issueId(savedIssues.get(0).getId())
                        .labelId(label.getId())
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();
        issueLabelsRepository.save(
                IssueLabels.builder()
                        .issueId(savedIssues.get(1).getId())
                        .labelId(label.getId())
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(label.getId()), null, null, null)
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
    void shouldReturnEmptyListWhenLabelIdMatchesNothing(){
        ProjectLabels unusedLabel = projectLabelsRepository.save(
                ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name("unused")
                        .color("#00FF00")
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(unusedLabel.getId()), null, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).isEmpty();
        }).verifyComplete();
    }

    @Test
    void shouldReturnInvalidArgumentWhenLabelIdIsNotAValidUuid(){
        ListIssuesForBoardRequest request = ListIssuesForBoardRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(REQUEST_ID)
                        .setNodeId(NODE_ID)
                        .build())
                .setBody(ListIssuesForBoardRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID_1.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setIncludeDone(true)
                        .addLabelIds("not-a-valid-uuid")
                        .build())
                .build();

        StepVerifier.create(grpcIssueService.listIssuesForBoard(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(StatusRuntimeException.class);

                    StatusRuntimeException ex = (StatusRuntimeException) error;
                    Assertions.assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    Assertions.assertThat(ex.getStatus().getDescription()).contains("body.labelIds");
                })
                .verify();
    }

    @Test
    void shouldReturnBothLabelIdsForIssueWithTwoLabels(){
        ProjectLabels label1 = projectLabelsRepository.save(
                ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name("bug")
                        .color("#FF0000")
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();
        ProjectLabels label2 = projectLabelsRepository.save(
                ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name("urgent")
                        .color("#00FF00")
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();

        issueLabelsRepository.save(
                IssueLabels.builder()
                        .issueId(savedIssues.get(0).getId())
                        .labelId(label1.getId())
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();
        issueLabelsRepository.save(
                IssueLabels.builder()
                        .issueId(savedIssues.get(0).getId())
                        .labelId(label2.getId())
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, null, null)
        ).assertNext(result -> {
            IssueBoardResponse card = result.stream()
                    .filter(r -> r.getIssueKey().equals("TEST-1"))
                    .findFirst()
                    .orElseThrow();

            Assertions.assertThat(card.getLabelIdsList())
                    .containsExactlyInAnyOrder(label1.getId().toString(), label2.getId().toString());
        }).verifyComplete();
    }

    @Test
    void shouldReturnEmptyLabelIdsListForIssueWithoutLabels(){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, null, null, null)
        ).assertNext(result -> {
            IssueBoardResponse card = result.stream()
                    .filter(r -> r.getIssueKey().equals("TEST-1"))
                    .findFirst()
                    .orElseThrow();

            Assertions.assertThat(card.getLabelIdsList()).isEmpty();
        }).verifyComplete();
    }

    @Test
    void boardCountsOnlyActiveAttachmentsAndKeepsCardsWithNullDescription() {
        var issue = savedIssues.getFirst();
        issue.setDescription(null);
        issue.setTimeSpentMinutes(0);
        issueRepository.save(issue).block();
        for (boolean deleted : List.of(false, true)) {
            attachments.save(ru.taska.domain.entity.IssueAttachment.builder()
                    .issueId(issue.getId()).uploadedBy(REPORTER_ID).objectKey(UUID.randomUUID().toString())
                    .fileName("test.txt").contentType("text/plain").sizeBytes(5L).checksum("test")
                    .deletedAt(deleted ? java.time.Instant.now() : null).build()).block();
        }
        try {
            StepVerifier.create(issueService.listIssueBoard(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                    null, null, null, true, List.of(), null, null, null, issue.getIssueKey()))
                    .assertNext(rows -> Assertions.assertThat(rows).singleElement().satisfies(card -> {
                        Assertions.assertThat(card.getId()).isEqualTo(issue.getId().toString());
                        Assertions.assertThat(card.getAttachmentCount()).isEqualTo(1);
                    })).verifyComplete();
        } finally {
            attachments.deleteAll().block();
        }
    }

    @Test
    void queryMatchesPercentUnderscoreAndEscapeCharactersLiterally() {
        var issue = issueRepository.findById(savedIssues.getFirst().getId()).block();
        issue.setSummary("100%_ready\\file!");
        issueRepository.save(issue).block();
        for (String query : List.of("%", "_", "\\", "!", "100%_ready\\file!")) {
            StepVerifier.create(issueService.listIssueBoard(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                    null, null, null, true, List.of(), null, null, null, query))
                    .assertNext(rows -> Assertions.assertThat(rows).singleElement()
                            .satisfies(card -> Assertions.assertThat(card.getId()).isEqualTo(issue.getId().toString())))
                    .verifyComplete();
        }
    }

    private Issue buildIssue(int number, UUID projectId, String statusKey, UUID assigneeId, IssueType issueType){
        return buildIssue(number, projectId, statusKey, assigneeId, issueType, IssuePriority.MEDIUM);
    }

    private Issue buildIssue(int number, UUID projectId, String statusKey, UUID assigneeId, IssueType issueType,
                              IssuePriority priority){
        return Issue.builder()
                .projectId(projectId)
                .issueNumber(number)
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .issueType(issueType)
                .issueKey("TEST-" + number)
                .summary("Тестовая задача")
                .priority(priority)
                .reporterId(REPORTER_ID)
                .version(1)
                .build();

    }

}
