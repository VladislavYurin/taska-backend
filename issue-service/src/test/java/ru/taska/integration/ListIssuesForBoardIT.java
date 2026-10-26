package ru.taska.integration;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
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
import ru.taska.domain.IssueType;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueLabels;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.entity.ProjectLabels;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.labels.IssueLabelsRepository;
import ru.taska.repository.labels.ProjectLabelsRepository;
import ru.taska.service.IssueService;
import ru.taska.transport.grpc.GrpcIssueService;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
    private ru.taska.transport.grpc.GrpcIssueServiceAdapter grpcIssueAdapter;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ru.taska.repository.IssueAttachmentRepository attachments;

    @Autowired
    private ProjectLabelsRepository projectLabelsRepository;

    @Autowired
    private IssueLabelsRepository issueLabelsRepository;

    @Autowired
    private DatabaseClient databaseClient;

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

    /**
     * {@code null} — выборка через Criteria API, число — сырой SQL с ROW_NUMBER().
     * Лимит 100 заведомо больше любой колонки, поэтому на результат фильтров он не влияет.
     */
    static List<Integer> pageSizesPerColumn() {
        return Arrays.asList(null, 100);
    }

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
                                .setProjectExists(true).setProjectArchived(false)
                        .build()));
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssueFromGivenProject(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssuesFilteredByIssueType(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null,  null, ISSUE_TYPE_BUG, true, List.of("DONE"),null,  pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssuesFilteredByAssignee(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, ASSIGNEE_ID_1, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssuesFilteredByStatusKey(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                ISSUE_STATUS_KEY_TODO, null, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldExcludeDoneIssuesWhenIncludeDoneIsFalse(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, false, List.of("DONE"), null, pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldNotFilterByDoneStatusesWhenDoneStatusKeysIsNull(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, false, null, null, pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-2", "TEST-3", "TEST-4");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnEmptyListWhenStatusKeyDoesNotMatch(Integer pageSizePerColumn){
        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID,  ACTOR_USER_ID,  PROJECT_ID_1,
                ISSUE_STATUS_KEY_IN_PROGRESS, null, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(result).isEmpty();
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldExcludeSoftDeletedIssues(Integer pageSizePerColumn){
        Issue deleted = buildIssue(9, PROJECT_ID_1, ISSUE_STATUS_KEY_TODO, ASSIGNEE_ID_1, ISSUE_TYPE_BUG);
        deleted.setDeletedAt(Instant.now());
        issueRepository.save(deleted).block();

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-2", "TEST-3", "TEST-4");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldSortIssuesByStatusKeyThenByCreatedAt(Integer pageSizePerColumn){
        // Внутри колонки порядок создания обратен номерам задач, чтобы сортировка
        // не совпала случайно с порядком вставки
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        setCreatedAt(savedIssues.get(1), base);                       // TEST-2, TODO
        setCreatedAt(savedIssues.get(0), base.plusSeconds(60));       // TEST-1, TODO
        setCreatedAt(savedIssues.get(3), base.plusSeconds(120));      // TEST-4, DONE
        setCreatedAt(savedIssues.get(2), base.plusSeconds(180));      // TEST-3, DONE

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-4", "TEST-3", "TEST-2", "TEST-1");
        }).verifyComplete();
    }

    static List<IssueType> countedTypes() {
        return Arrays.asList(null, IssueType.TASK, IssueType.BUG, IssueType.STORY);
    }

    @ParameterizedTest
    @MethodSource("countedTypes")
    void boundedPageIncludesTotalBeforeLimitAndUuidTieBreak(IssueType type) {
        databaseClient.sql("UPDATE taska.issues SET created_at='2026-01-01T00:00:00Z'")
                .fetch().rowsUpdated().block();
        var expected = savedIssues.stream().filter(issue -> PROJECT_ID_1.equals(issue.getProjectId()))
                .filter(issue -> type == null || type.equals(issue.getIssueType()))
                .collect(Collectors.groupingBy(Issue::getStatusKey));
        var result = issueService.listIssueBoard(REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, type, true, List.of(), null, 1, null, null).block();
        Assertions.assertThat(result).hasSize(expected.size());
        for (var row : result) {
            var group = expected.get(row.getStatusKey());
            Assertions.assertThat(row.getStatusTotalCount()).isEqualTo(group.size());
            Assertions.assertThat(row.getId()).isEqualTo(group.stream().map(i -> i.getId().toString()).sorted().findFirst().orElseThrow());
        }
    }

    @Test
    void labelIdsUseActiveLabelNameThenUuidOrderRegardlessOfBindingTime() {
        var first = saveLabel("z-last-name");
        var second = saveLabel("a-first-name");
        var deleted = saveLabel("0-deleted-first");
        var issue = savedIssues.getFirst();
        UUID id = issue.getId();
        attachLabel(issue, first);
        attachLabel(issue, second);
        attachLabel(issue, deleted);
        databaseClient.sql("UPDATE taska.project_labels SET deleted_at=now() WHERE id=:id")
                .bind("id", deleted.getId()).then().block();
        UUID later = new UUID(0, 1); // Binding time and a smaller UUID must not override the label name.
        databaseClient.sql("INSERT INTO taska.project_labels(id,project_id,name,color,created_by) VALUES(:id,:project,'later','#abcdef',:actor)")
                .bind("id",later).bind("project",PROJECT_ID_1).bind("actor",ACTOR_USER_ID).fetch().rowsUpdated().block();
        databaseClient.sql("INSERT INTO taska.issue_labels(issue_id,label_id,created_at,created_by) VALUES(:issue,:label,'2026-01-02T00:00:00Z',:actor)")
                .bind("issue",id).bind("label",later).bind("actor",ACTOR_USER_ID).fetch().rowsUpdated().block();
        Assertions.assertThat(issueRepository.findLabelIdsByIssueIds(List.of(id)).block().get(id))
                .containsExactly(second.getId(), later, first.getId());
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
    void shouldKeepOldestIssuesInEachColumnWhenPageSizePerColumnIsSet(){
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        setCreatedAt(savedIssues.get(1), base);                       // TEST-2, TODO
        setCreatedAt(savedIssues.get(0), base.plusSeconds(60));       // TEST-1, TODO
        setCreatedAt(savedIssues.get(3), base.plusSeconds(120));      // TEST-4, DONE
        setCreatedAt(savedIssues.get(2), base.plusSeconds(180));      // TEST-3, DONE

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), null, 1, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-4", "TEST-2");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssuesFilteredByLabelId(Integer pageSizePerColumn){
        ProjectLabels label = saveLabel("urgent");
        attachLabel(savedIssues.get(0), label);
        attachLabel(savedIssues.get(1), label);

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(label.getId()), pageSizePerColumn, null, null)
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

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnIssuesMatchingAnyOfGivenLabelIds(Integer pageSizePerColumn){
        ProjectLabels urgent = saveLabel("urgent");
        ProjectLabels backend = saveLabel("backend");
        attachLabel(savedIssues.get(0), urgent);
        attachLabel(savedIssues.get(2), backend);

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(urgent.getId(), backend.getId()), pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .sorted()
                            .toList()
            ).containsExactly("TEST-1", "TEST-3");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldCombineLabelFilterWithOtherFilters(Integer pageSizePerColumn){
        ProjectLabels label = saveLabel("urgent");
        attachLabel(savedIssues.get(0), label);   // TEST-1: TODO, ASSIGNEE_1
        attachLabel(savedIssues.get(1), label);   // TEST-2: TODO, ASSIGNEE_2
        attachLabel(savedIssues.get(2), label);   // TEST-3: DONE, ASSIGNEE_1

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, ASSIGNEE_ID_1, null, false, List.of("DONE"), List.of(label.getId()), pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-1");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldNotReturnLabeledIssuesFromOtherProject(Integer pageSizePerColumn){
        ProjectLabels label = saveLabel("urgent");
        attachLabel(savedIssues.get(0), label);   // TEST-1, PROJECT_ID_1
        attachLabel(savedIssues.get(4), label);   // TEST-5, PROJECT_ID_2

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(label.getId()), pageSizePerColumn, null, null)
        ).assertNext(result -> {
            Assertions.assertThat(
                    result.stream()
                            .map(IssueBoardResponse::getIssueKey)
                            .toList()
            ).containsExactly("TEST-1");
        }).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("pageSizesPerColumn")
    void shouldReturnEmptyListWhenLabelIdMatchesNothing(Integer pageSizePerColumn){
        ProjectLabels unusedLabel = saveLabel("unused");

        StepVerifier.create(issueService.listIssueBoard(
                REQUEST_ID, NODE_ID, ACTOR_USER_ID, PROJECT_ID_1,
                null, null, null, true, List.of("DONE"), List.of(unusedLabel.getId()), pageSizePerColumn, null, null)
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

    /**
     * Явно переданный пустой statusKey считается незаданным: фильтр по колонке не применяется,
     * а не превращается в {@code status_key = ''}.
     */
    @Test
    void shouldNotFilterByStatusKeyWhenItIsExplicitlyBlank(){
        ListIssuesForBoardRequest request = ListIssuesForBoardRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(REQUEST_ID)
                        .setNodeId(NODE_ID)
                        .build())
                .setBody(ListIssuesForBoardRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID_1.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setIssueType(ru.taska.api.issue.v1.IssueType.ISSUE_TYPE_BUG)
                        .setIncludeDone(true)
                        .setStatusKey("")
                        .build())
                .build();

        Assertions.assertThat(grpcIssueService.listIssuesForBoard(Mono.just(request))
                        .block(Duration.ofSeconds(10))
                        .getIssuesList()
                        .stream()
                        .map(IssueBoardResponse::getIssueKey)
                        .sorted()
                        .toList())
                .containsExactly("TEST-1", "TEST-4");
    }

    @Test
    void shouldReturnBothLabelIdsForIssueWithTwoLabels(){
        ProjectLabels label1 = saveLabel("bug");
        ProjectLabels label2 = saveLabel("urgent");
        attachLabel(savedIssues.get(0), label1);
        attachLabel(savedIssues.get(0), label2);

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

    @Test
    void cursorOrdersEqualTimestampsAndSurvivesConcurrentInsertsBeforeAndAfterIt() {
        Instant sameTime = Instant.parse("2026-01-01T12:00:00.123456Z");
        var ten = saveCursorIssue(110, 10, "CURSOR", sameTime);
        var twenty = saveCursorIssue(120, 20, "CURSOR", sameTime);
        var thirty = saveCursorIssue(130, 30, "CURSOR", sameTime);
        var forty = saveCursorIssue(140, 40, "CURSOR", sameTime);
        var first = boardColumnRequest("CURSOR").setPageSizePerColumn(2);
        var firstPage = callBoardColumn(first);
        Assertions.assertThat(firstPage.getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(ten.getId().toString(), twenty.getId().toString());
        Assertions.assertThat(firstPage.getTotalCount()).isEqualTo(4);
        Assertions.assertThat(firstPage.getHasMore()).isTrue();

        // Inserts committed between page reads must not shift an offset or repeat the cursor row.
        var before = buildIssue(115, PROJECT_ID_1, "CURSOR", ASSIGNEE_ID_1, ISSUE_TYPE_TASK);
        var after = buildIssue(125, PROJECT_ID_1, "CURSOR", ASSIGNEE_ID_1, ISSUE_TYPE_TASK);
        var last = buildIssue(150, PROJECT_ID_1, "CURSOR", ASSIGNEE_ID_1, ISSUE_TYPE_TASK);
        Mono.when(
                        saveCursorIssue(before, 15, sameTime),
                        saveCursorIssue(after, 25, sameTime),
                        saveCursorIssue(last, 50, sameTime))
                .block(Duration.ofSeconds(10));
        var second =
                callBoardColumn(
                        first.clone()
                                .setAfterCreatedAt(firstPage.getIssues(1).getCreatedAt())
                                .setAfterId(twenty.getId().toString()));
        Assertions.assertThat(second.getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(new UUID(0, 25).toString(), thirty.getId().toString());
        Assertions.assertThat(second.getTotalCount()).isEqualTo(7);
        Assertions.assertThat(second.getHasMore()).isTrue();
        var third =
                callBoardColumn(
                        first.clone()
                                .setAfterCreatedAt(second.getIssues(1).getCreatedAt())
                                .setAfterId(thirty.getId().toString()));
        Assertions.assertThat(third.getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(forty.getId().toString(), new UUID(0, 50).toString());
        Assertions.assertThat(third.getHasMore()).isFalse();
        var exhausted =
                callBoardColumn(
                        first.clone()
                                .setAfterCreatedAt(third.getIssues(1).getCreatedAt())
                                .setAfterId(third.getIssues(1).getId()));
        Assertions.assertThat(exhausted.getIssuesList()).isEmpty();
        Assertions.assertThat(exhausted.getTotalCount()).isEqualTo(7);
        Assertions.assertThat(exhausted.getHasMore()).isFalse();
        Assertions.assertThat(callBoardColumn(first).getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(ten.getId().toString(), new UUID(0, 15).toString());
    }

    @Test
    void cursorKeepsBoardFiltersMetadataAndFullCountsIncludingEmptyAndDonePages() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        var included = saveCursorIssue(101, 1, "CUSTOM_CLOSED", date);
        var second = saveCursorIssue(102, 2, "CUSTOM_CLOSED", date.plusSeconds(1));
        var deleted = saveCursorIssue(103, 3, "CUSTOM_CLOSED", date.plusSeconds(2));
        for (Issue issue : List.of(included, second, deleted)) {
            issue.setSummary("100%_hello");
            issue.setPriority(IssuePriority.HIGH);
            issue.setDescription("board description");
            issue.setVersion(7);
            issue.setTimeSpentMinutes(30);
            issueRepository.save(issue).block();
        }
        deleted.setDeletedAt(Instant.now());
        issueRepository.save(deleted).block();
        var label = saveLabel("cursor-label");
        var otherLabel = saveLabel("second-label");
        for (Issue issue : List.of(included, second, deleted)) {
            attachLabel(issue, otherLabel);
            attachLabel(issue, label);
        }
        var excludedType = saveCursorIssue(104, 4, "CUSTOM_CLOSED", date);
        excludedType.setIssueType(ISSUE_TYPE_BUG);
        excludedType.setSummary("100%_hello");
        excludedType.setPriority(IssuePriority.HIGH);
        issueRepository.save(excludedType).block();
        attachLabel(excludedType, label);
        var request =
                boardColumnRequest("CUSTOM_CLOSED")
                        .setPageSizePerColumn(1)
                        .setIssueTypeKey("TASK")
                        .setAssigneeId(ASSIGNEE_ID_1.toString())
                        .setPriority(ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_HIGH)
                        .setQuery("%")
                        .addLabelIds(label.getId().toString())
                        .addLabelIds(otherLabel.getId().toString())
                        .addDoneStatusKeys("CUSTOM_CLOSED");
        var first = callBoardColumn(request);
        Assertions.assertThat(first.getIssuesList())
                .singleElement()
                .satisfies(
                        issue -> {
                            Assertions.assertThat(issue.getId())
                                    .isEqualTo(included.getId().toString());
                            Assertions.assertThat(issue.getLabelIdsList())
                                    .containsExactly(
                                            label.getId().toString(),
                                            otherLabel.getId().toString());
                            Assertions.assertThat(issue.getVersion()).isEqualTo(7);
                            Assertions.assertThat(issue.getDescription())
                                    .isEqualTo("board description");
                            Assertions.assertThat(issue.getAssigneeId())
                                    .isEqualTo(ASSIGNEE_ID_1.toString());
                        });
        Assertions.assertThat(first.getTotalCount()).isEqualTo(2);
        Assertions.assertThat(first.getHasMore()).isTrue();
        var last =
                callBoardColumn(
                        request.clone()
                                .setAfterId(included.getId().toString())
                                .setAfterCreatedAt(first.getIssues(0).getCreatedAt()));
        Assertions.assertThat(last.getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(second.getId().toString());
        Assertions.assertThat(last.getTotalCount()).isEqualTo(2);
        Assertions.assertThat(last.getHasMore()).isFalse();
        var hiddenDone = callBoardColumn(request.clone().setIncludeDone(false));
        Assertions.assertThat(hiddenDone.getIssuesList()).isEmpty();
        Assertions.assertThat(hiddenDone.getTotalCount()).isZero();
        Assertions.assertThat(hiddenDone.getHasMore()).isFalse();
        var noLabelMatches =
                callBoardColumn(
                        request.clone().clearLabelIds().addLabelIds(UUID.randomUUID().toString()));
        Assertions.assertThat(noLabelMatches.getIssuesList()).isEmpty();
        Assertions.assertThat(noLabelMatches.getTotalCount()).isZero();
        var noAssigneeMatches =
                callBoardColumn(request.clone().setAssigneeId(ASSIGNEE_ID_2.toString()));
        Assertions.assertThat(noAssigneeMatches.getTotalCount()).isZero();
        var noPriorityMatches =
                callBoardColumn(
                        request.clone()
                                .setPriority(
                                        ru.taska.api.issue.v1.IssuePriority.ISSUE_PRIORITY_LOW));
        Assertions.assertThat(noPriorityMatches.getTotalCount()).isZero();
        var noQueryMatches = callBoardColumn(request.clone().setQuery("no match"));
        Assertions.assertThat(noQueryMatches.getTotalCount()).isZero();
    }

    @Test
    void grpcRejectsPartialOrInvalidBoardCursorsAndInvalidPageSizes() {
        var timestamp = com.google.protobuf.Timestamp.newBuilder().setSeconds(1).build();
        List<ListIssuesForBoardRequestBody.Builder> invalid =
                List.of(
                        boardColumnRequest("TODO").setAfterCreatedAt(timestamp),
                        boardColumnRequest("TODO").setAfterId(UUID.randomUUID().toString()),
                        boardColumnRequest("TODO")
                                .setAfterCreatedAt(timestamp)
                                .setAfterId("invalid"),
                        boardColumnRequest("TODO")
                                .setAfterCreatedAt(timestamp)
                                .setAfterId(UUID.randomUUID().toString())
                                .clearStatusKey(),
                        boardColumnRequest(" ")
                                .setAfterCreatedAt(timestamp)
                                .setAfterId(UUID.randomUUID().toString()),
                        boardColumnRequest("TODO").setPageSizePerColumn(0),
                        boardColumnRequest("TODO").setPageSizePerColumn(201),
                        boardColumnRequest("TODO")
                                .setAfterId(UUID.randomUUID().toString())
                                .setAfterCreatedAt(timestamp.toBuilder().setNanos(1000000000)),
                        boardColumnRequest("TODO")
                                .setAfterId(UUID.randomUUID().toString())
                                .setAfterCreatedAt(
                                        timestamp.toBuilder().setSeconds(253402300800L)));
        for (var body : invalid) {
            StepVerifier.create(
                            grpcIssueService.listIssuesForBoard(
                                    Mono.just(
                                            ListIssuesForBoardRequest.newBuilder()
                                                    .setHeader(
                                                            Header.newBuilder()
                                                                    .setRequestId(REQUEST_ID)
                                                                    .setNodeId(NODE_ID))
                                                    .setBody(body)
                                                    .build())))
                    .expectErrorSatisfies(
                            error ->
                                    Assertions.assertThat(Status.fromThrowable(error).getCode())
                                            .isEqualTo(Status.Code.INVALID_ARGUMENT))
                    .verify(Duration.ofSeconds(5));
        }
    }

    @Test
    void legacyStatusOnlyStaysUnboundedWhileExplicitAndCursorPagesAreBounded() {
        reactor.core.publisher.Flux.range(200, 51)
                .concatMap(
                        number ->
                                saveCursorIssue(
                                        buildIssue(
                                                number,
                                                PROJECT_ID_1,
                                                "PAGE_DEFAULT",
                                                ASSIGNEE_ID_1,
                                                ISSUE_TYPE_TASK),
                                        number,
                                        Instant.parse("2026-01-01T00:00:00Z")))
                .then()
                .block(Duration.ofSeconds(10));
        var legacy = callBoardColumn(boardColumnRequest("PAGE_DEFAULT"));
        Assertions.assertThat(legacy.getIssuesCount()).isEqualTo(51);
        Assertions.assertThat(legacy.hasTotalCount()).isFalse();
        Assertions.assertThat(legacy.hasHasMore()).isFalse();
        var page = callBoardColumn(boardColumnRequest("PAGE_DEFAULT").setPageSizePerColumn(50));
        Assertions.assertThat(page.getIssuesCount()).isEqualTo(50);
        Assertions.assertThat(page.getTotalCount()).isEqualTo(51);
        Assertions.assertThat(page.getHasMore()).isTrue();
        Assertions.assertThat(page.hasHasMore()).isTrue();
        var cursorDefault = callBoardColumn(boardColumnRequest("PAGE_DEFAULT")
                .setAfterCreatedAt(page.getIssues(0).getCreatedAt()).setAfterId(new UUID(0, 0).toString()));
        Assertions.assertThat(cursorDefault.getIssuesCount()).isEqualTo(50);
        Assertions.assertThat(cursorDefault.getTotalCount()).isEqualTo(51);
        Assertions.assertThat(cursorDefault.getHasMore()).isTrue();
        Mockito.when(
                        projectServiceStub.checkProjectMemberRole(
                                Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(
                        Mono.just(
                                CheckProjectMemberRoleResponse.newBuilder()
                                        .setProjectExists(true)
                                        .build()));
        StepVerifier.create(
                        grpcIssueAdapter.listIssuesForBoard(
                                Mono.just(
                                        ListIssuesForBoardRequest.newBuilder()
                                                .setHeader(
                                                        Header.newBuilder()
                                                                .setRequestId(REQUEST_ID)
                                                                .setNodeId(NODE_ID))
                                                .setBody(boardColumnRequest("TODO").setPageSizePerColumn(50))
                                                .build())))
                .expectErrorSatisfies(
                        error ->
                                Assertions.assertThat(Status.fromThrowable(error).getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void cursorDoesNotRequireItsAnchorRowToStillExist() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        var first = saveCursorIssue(101, 1, "DELETED_CURSOR", date);
        var second = saveCursorIssue(102, 2, "DELETED_CURSOR", date);
        var initial = callBoardColumn(boardColumnRequest("DELETED_CURSOR").setPageSizePerColumn(1));
        first.setDeletedAt(Instant.now());
        issueRepository.save(first).block();
        var continued =
                callBoardColumn(
                        boardColumnRequest("DELETED_CURSOR")
                                .setPageSizePerColumn(1)
                                .setAfterId(first.getId().toString())
                                .setAfterCreatedAt(initial.getIssues(0).getCreatedAt()));
        Assertions.assertThat(continued.getIssuesList())
                .extracting(IssueBoardResponse::getId)
                .containsExactly(second.getId().toString());
        Assertions.assertThat(continued.getTotalCount()).isEqualTo(1);
        Assertions.assertThat(continued.getHasMore()).isFalse();
    }

    private ListIssuesForBoardRequestBody.Builder boardColumnRequest(String status) {
        return ListIssuesForBoardRequestBody.newBuilder()
                .setProjectId(PROJECT_ID_1.toString())
                .setActorUserId(ACTOR_USER_ID.toString())
                .setStatusKey(status)
                .setIncludeDone(true);
    }

    private ru.taska.api.issue.v1.ListIssuesForBoardResponse callBoardColumn(
            ListIssuesForBoardRequestBody.Builder body) {
        return grpcIssueAdapter
                .listIssuesForBoard(
                        Mono.just(
                                ListIssuesForBoardRequest.newBuilder()
                                        .setHeader(
                                                Header.newBuilder()
                                                        .setRequestId(REQUEST_ID)
                                                        .setNodeId(NODE_ID))
                                        .setBody(body)
                                        .build()))
                .block(Duration.ofSeconds(10));
    }

    private Issue saveCursorIssue(int number, int id, String status, Instant date) {
        return saveCursorIssue(
                        buildIssue(number, PROJECT_ID_1, status, ASSIGNEE_ID_1, ISSUE_TYPE_TASK),
                        id,
                        date)
                .block();
    }

    private Mono<Issue> saveCursorIssue(Issue issue, int id, Instant date) {
        issue.setTimeSpentMinutes(0);
        return issueRepository
                .save(issue)
                .flatMap(
                        saved ->
                                databaseClient
                                        .sql(
                                                "UPDATE taska.issues SET id = :id, created_at ="
                                                    + " :date WHERE id = :oldId")
                                        .bind("id", new UUID(0, id))
                                        .bind("oldId", saved.getId())
                                        .bind("date", date)
                                        .then()
                                        .thenReturn(saved)
                                        .doOnNext(
                                                row -> {
                                                    row.setId(new UUID(0, id));
                                                    row.setCreatedAt(date);
                                                }));
    }

    private ProjectLabels saveLabel(String name){
        return projectLabelsRepository.save(
                ProjectLabels.builder()
                        .projectId(PROJECT_ID_1)
                        .name(name)
                        .color("#FF0000")
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();
    }

    private void attachLabel(Issue issue, ProjectLabels label){
        issueLabelsRepository.save(
                IssueLabels.builder()
                        .issueId(issue.getId())
                        .labelId(label.getId())
                        .createdBy(REPORTER_ID)
                        .build()
        ).block();
    }

    /** created_at выставляется аудитом при сохранении, поэтому для детерминированного порядка он переписывается напрямую. */
    private void setCreatedAt(Issue issue, Instant createdAt){
        databaseClient.sql("UPDATE taska.issues SET created_at = :createdAt WHERE id = :id")
                .bind("createdAt", createdAt)
                .bind("id", issue.getId())
                .then()
                .block();
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
