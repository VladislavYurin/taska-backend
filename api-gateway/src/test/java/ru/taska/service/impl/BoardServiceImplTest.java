package ru.taska.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.AvatarResponse;
import ru.taska.api.auth.profile.v1.GetUserDetailsByIdsResponse;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.domain.BoardIssueData;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.BoardUserDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.ListProjectLabelsResponseDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.mapper.BoardMapper;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcLabelServiceClient;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для {@link BoardServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BoardServiceImpl Tests")
class BoardServiceImplTest {

    private static final UUID PROJECT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNEE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LABEL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String ISSUE_TYPE = "TASK";

    @Mock
    private GrpcWorkflowServiceClient workflowClient;

    @Mock
    private GrpcIssueServiceClient issueClient;

    @Mock
    private GrpcLabelServiceClient labelClient;

    @Mock
    private GrpcUserProfileServiceClient profileClient;

    @Mock
    private ru.taska.transport.grpc.GrpcProjectIssueTypeClient issueTypesClient;

    private BoardServiceImpl boardService;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        boardService = new BoardServiceImpl(
                workflowClient,
                issueClient,
                labelClient,
                profileClient,
                new BoardMapper(), issueTypesClient
        );

        context = new GatewayContext(
                "request-id",
                "api-gateway",
                null
        );
        Mockito.lenient().when(issueTypesClient.list(PROJECT_ID, context)).thenReturn(Mono.just(registry("TASK", "BUG", "STORY")));
    }

    @Test
    void typeMissingFromProjectRegistryReturnsNoColumnsOrIssues() {
        StepVerifier.create(boardService.getBoard(PROJECT_ID, "REQUEST", null, null, false, null, null, 20, context))
                .assertNext(board -> {
                    assertThat(board.getColumns()).isEmpty();
                    assertThat(board.getUnplacedIssues()).isEmpty();
                }).verifyComplete();
        Mockito.verifyNoInteractions(workflowClient, issueClient);
    }

    @Test
    void pageCombinesTypesBeforeLimitingAndCountsFilteredGroupsOnlyOnce() {
        var flow = new WorkflowResponseDto().statuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context)).thenReturn(Mono.just(flow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context)).thenReturn(Mono.just(flow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));
        var old = pageIssue("TASK", "TODO", 1, "2026-01-01T00:00:00Z", 3);
        var taskTie = pageIssue("TASK", "TODO", 9, "2026-01-02T00:00:00Z", 3);
        var bugTie = pageIssue("BUG", "TODO", 2, "2026-01-02T03:00:00+03:00", 4);
        var newer = pageIssue("BUG", "TODO", 3, "2026-01-03T00:00:00Z", 4);
        var loose1 = pageIssue("STORY", "LEGACY", 4, "2026-01-01T00:00:00Z", 5);
        var loose2 = pageIssue("STORY", "LEGACY", 5, "2026-01-02T00:00:00Z", 5);
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "TASK", null, null, true, List.of(), null, null, 2, context))
                .thenReturn(Mono.just(List.of(taskTie, old)));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "BUG", null, null, true, List.of(), null, null, 2, context))
                .thenReturn(Mono.just(List.of(newer, bugTie)));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "STORY", null, null, true, List.of(), null, null, 2, context))
                .thenReturn(Mono.just(List.of(loose2, loose1)));
        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, true, null, null, 2, context))
                .assertNext(board -> {
                    var column = board.getColumns().getFirst();
                    assertThat(column.getIssues()).containsExactly(old.issue(), bugTie.issue());
                    assertThat(column.getTotalCount()).isEqualTo(7);
                    assertThat(column.getHasMore()).isTrue();
                    assertThat(board.getUnplacedIssues()).containsExactly(loose1.issue(), loose2.issue());
                    assertThat(board.getUnplacedTotalCount()).isEqualTo(5);
                    assertThat(board.getUnplacedHasMore()).isTrue();
                }).verifyComplete();
    }

    private BoardIssueData pageIssue(String type, String status, int id, String createdAt, long count) {
        var dto = boardIssueDto("TAS-" + id);
        dto.setIssueType(type);
        dto.setId(new UUID(0, id));
        dto.setCreatedAt(java.time.OffsetDateTime.parse(createdAt));
        return new BoardIssueData(status, List.of(), dto, count);
    }

    @Test
    @DisplayName("Должен отсортировать колонки workflow и распределить задачи по статусам")
    void getBoard_shouldSortColumnsAndGroupIssuesByStatus() {
        var todoStatus = workflowStatus("TODO", "To Do", "TODO", 1);
        var doneStatus = workflowStatus("DONE", "Done", "DONE", 2);

        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(doneStatus, todoStatus));

        var todoRestIssue = boardIssueDto("TAS-1");
        var doneRestIssue = boardIssueDto("TAS-2");

        var todoIssue = new BoardIssueData("TODO", List.of(), todoRestIssue);
        var doneIssue = new BoardIssueData("DONE", List.of(), doneRestIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));

        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE,
                ASSIGNEE_ID.toString(),
                LABEL_ID.toString(),
                true, List.of("DONE"),
                null, null, 50,
                context
        )).thenReturn(Mono.just(List.of(doneIssue, todoIssue)));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID,
                        ISSUE_TYPE,
                        ASSIGNEE_ID,
                        LABEL_ID,
                        true,
                        null, null, 50,
                        context
                ))
                .assertNext(response -> {
                    assertThat(response.getProjectId()).isEqualTo(PROJECT_ID);
                    assertThat(response.getColumns()).hasSize(2);
                    assertThat(response.getUnplacedIssues()).isEmpty();

                    assertThat(response.getColumns().get(0).getStatusKey()).isEqualTo("TODO");
                    assertThat(response.getColumns().get(0).getIssues())
                            .containsExactly(todoRestIssue);

                    assertThat(response.getColumns().get(1).getStatusKey()).isEqualTo("DONE");
                    assertThat(response.getColumns().get(1).getIssues())
                            .containsExactly(doneRestIssue);
                })
                .verifyComplete();

        verify(workflowClient).getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context);
        verify(issueClient).listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE,
                ASSIGNEE_ID.toString(),
                LABEL_ID.toString(),
                true, List.of("DONE"),
                null, null, 50,
                context
        );
    }

    @Test
    @DisplayName("Задача со статусом вне workflow попадает в unplacedIssues, а не роняет доску")
    void getBoard_shouldReturnUnplacedIssues_whenIssueHasUnknownStatus() {
        var todoStatus = workflowStatus("TODO", "To Do", "TODO", 1);

        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(todoStatus));

        var unknownRestIssue = boardIssueDto("TAS-99");
        var unknownIssue = new BoardIssueData("UNKNOWN", List.of(), unknownRestIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));

        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE,
                null,
                null,
                false, List.of(),
                null, null, 50,
                context
        )).thenReturn(Mono.just(List.of(unknownIssue)));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID,
                        ISSUE_TYPE,
                        null,
                        null,
                        false,
                        null, null, 50,
                        context
                ))
                .assertNext(response -> {
                    assertThat(response.getColumns()).hasSize(1);
                    assertThat(response.getColumns().getFirst().getIssues()).isEmpty();
                    assertThat(response.getUnplacedIssues()).containsExactly(unknownRestIssue);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Без issueType объединяет статусы workflow трёх типов (TASK/BUG/STORY), сортируя по sortOrder")
    void getBoard_withoutIssueType_mergesWorkflowAcrossAllTypes() {
        var taskWorkflow = new WorkflowResponseDto();
        taskWorkflow.setStatuses(List.of(
                workflowStatus("TODO", "To Do (task)", "TODO", 1),
                workflowStatus("DONE", "Done (task)", "DONE", 3)
        ));
        var bugWorkflow = new WorkflowResponseDto();
        bugWorkflow.setStatuses(List.of(
                workflowStatus("TODO", "To Do (bug, ignored dup)", "TODO", 99),
                workflowStatus("IN_PROGRESS", "In Progress", "IN_PROGRESS", 2)
        ));
        var storyWorkflow = new WorkflowResponseDto();
        storyWorkflow.setStatuses(List.of());

        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context))
                .thenReturn(Mono.just(taskWorkflow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context))
                .thenReturn(Mono.just(bugWorkflow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.just(storyWorkflow));

        for (String type : List.of("TASK", "BUG", "STORY")) {
            when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), type, null, null, true,
                    "TASK".equals(type) ? List.of("DONE") : List.of(), null, null, 50, context))
                    .thenReturn(Mono.just(List.of()));
        }

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID,
                        null,
                        null,
                        null,
                        true,
                        null, null, 50,
                        context
                ))
                .assertNext(response -> {
                    assertThat(response.getColumns()).hasSize(3);
                    assertThat(response.getColumns().stream().map(c -> c.getStatusKey()).toList())
                            .containsExactly("TODO", "IN_PROGRESS", "DONE");
                    assertThat(response.getColumns().getFirst().getName()).isEqualTo("To Do (task)");
                })
                .verifyComplete();

        verify(workflowClient).getWorkflowForProject(PROJECT_ID, "TASK", context);
        verify(workflowClient).getWorkflowForProject(PROJECT_ID, "BUG", context);
        verify(workflowClient).getWorkflowForProject(PROJECT_ID, "STORY", context);
    }

    @Test
    @DisplayName("Без issueType: NOT_FOUND workflow одного из типов пропускается, доска строится из остальных")
    void getBoard_withoutIssueType_skipsTypeWithNotFoundWorkflow() {
        var taskWorkflow = new WorkflowResponseDto();
        taskWorkflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));
        var bugWorkflow = new WorkflowResponseDto();
        bugWorkflow.setStatuses(List.of(workflowStatus("IN_PROGRESS", "In Progress", "IN_PROGRESS", 2)));
        var storyNotFound = new StatusRuntimeException(Status.NOT_FOUND.withDescription("Default Workflow not found"));

        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context))
                .thenReturn(Mono.just(taskWorkflow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context))
                .thenReturn(Mono.just(bugWorkflow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.error(storyNotFound));

        var storyOnlyStatusIssue = boardIssueDto("TAS-7");
        storyOnlyStatusIssue.setIssueType("STORY");
        var storyIssueData = new BoardIssueData("STORY_REVIEW", List.of(), storyOnlyStatusIssue);

        for (String type : List.of("TASK", "BUG", "STORY")) {
            when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), type, null, null, true, List.of(), null, null, 50, context))
                    .thenReturn(Mono.just("STORY".equals(type) ? List.of(storyIssueData) : List.of()));
        }

        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, true, null, null, 50, context))
                .assertNext(response -> {
                    assertThat(response.getColumns()).hasSize(2);
                    assertThat(response.getColumns().stream().map(c -> c.getStatusKey()).toList())
                            .containsExactly("TODO", "IN_PROGRESS");
                    assertThat(response.getUnplacedIssues()).containsExactly(storyOnlyStatusIssue);
                })
                .verifyComplete();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void customProjectTypeIsVisibleInColumnOrUnplaced(boolean hasWorkflow) {
        when(issueTypesClient.list(PROJECT_ID, context)).thenReturn(Mono.just(registry("REQUEST")));
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "REQUEST", context))
                .thenReturn(hasWorkflow ? Mono.just(workflow) : Mono.error(Status.NOT_FOUND.asRuntimeException()));
        var issue = boardIssueDto("REQ-1");
        issue.setIssueType("REQUEST");
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "REQUEST", null, null, true,
                List.of(), null, null, 50, context)).thenReturn(Mono.just(List.of(new BoardIssueData("TODO", List.of(), issue))));
        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, true, null, null, 50, context))
                .assertNext(board -> {
                    if (hasWorkflow) {
                        assertThat(board.getColumns()).singleElement().satisfies(column -> assertThat(column.getIssues()).containsExactly(issue));
                        assertThat(board.getUnplacedIssues()).isEmpty();
                    } else {
                        assertThat(board.getColumns()).isEmpty();
                        assertThat(board.getUnplacedIssues()).containsExactly(issue);
                    }
                }).verifyComplete();
        verify(workflowClient).getWorkflowForProject(PROJECT_ID, "REQUEST", context);
        Mockito.verifyNoMoreInteractions(workflowClient);
    }

    @Test
    @DisplayName("Без issueType: ошибка, отличная от NOT_FOUND, у одного из типов роняет доску")
    void getBoard_withoutIssueType_propagatesNonNotFoundError() {
        var taskWorkflow = new WorkflowResponseDto();
        taskWorkflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));
        var unavailable = new StatusRuntimeException(Status.UNAVAILABLE.withDescription("workflow-service down"));

        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context))
                .thenReturn(Mono.just(taskWorkflow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context))
                .thenReturn(Mono.error(unavailable));
        Mockito.lenient().when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.just(new WorkflowResponseDto()));

        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, true, null, null, 50, context))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(StatusRuntimeException.class);
                    assertThat(((StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(Status.Code.UNAVAILABLE);
                })
                .verify();

        Mockito.verifyNoInteractions(issueClient);
    }

    @Test
    @DisplayName("С заданным issueType NOT_FOUND workflow по-прежнему роняет доску (не подавляется)")
    void getBoard_withIssueType_doesNotSuppressNotFound() {
        var notFound = new StatusRuntimeException(Status.NOT_FOUND.withDescription("Default Workflow not found"));

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.error(notFound));

        StepVerifier.create(boardService.getBoard(PROJECT_ID, ISSUE_TYPE, null, null, true, null, null, 50, context))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(StatusRuntimeException.class);
                    assertThat(((StatusRuntimeException) error).getStatus().getCode())
                            .isEqualTo(Status.Code.NOT_FOUND);
                })
                .verify();

        Mockito.verifyNoInteractions(issueClient);
    }

    @Test
    @DisplayName("Фильтры priority и query пробрасываются в GrpcIssueServiceClient")
    void getBoard_passesPriorityAndQueryToIssueClient() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE,
                null, null,
                true, List.of(),
                IssuePriorityDto.HIGH, "bug fix", 50,
                context
        )).thenReturn(Mono.just(List.of()));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, true,
                        IssuePriorityDto.HIGH, "bug fix", 50,
                        context
                ))
                .assertNext(response -> assertThat(response.getColumns())
                        .hasSize(1))
                .verifyComplete();

        verify(issueClient).listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE,
                null, null,
                true, List.of(),
                IssuePriorityDto.HIGH, "bug fix", 50,
                context
        );
    }

    @Test
    @DisplayName("Заполняет labels-объекты и assignee.displayName/avatarUrl из labelClient и profileClient")
    void getBoard_enrichesLabelsAndAssigneeProfile() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));

        var restIssue = boardIssueDto("TAS-1");
        var assignee = new BoardUserDto();
        assignee.setId(ASSIGNEE_ID);
        restIssue.setAssignee(assignee);

        var issueData = new BoardIssueData("TODO", List.of(LABEL_ID), restIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(), ISSUE_TYPE, null, null, false, List.of(), null, null, 50, context
        )).thenReturn(Mono.just(List.of(issueData)));

        var label = new ProjectLabelResponseDto();
        label.setId(LABEL_ID);
        label.setName("urgent");
        label.setColor("#FF0000");
        var labelsResponse = new ListProjectLabelsResponseDto();
        labelsResponse.setItems(List.of(label, label));

        when(labelClient.listProjectLabels(PROJECT_ID.toString(), context))
                .thenReturn(Mono.just(labelsResponse));

        var userDetails = UserDetails.newBuilder()
                .setUserId(ASSIGNEE_ID.toString())
                .setDisplayName("Ivan Petrov")
                .setAvatar(AvatarResponse.newBuilder().setDownloadUrl("https://cdn/avatar.png").build())
                .build();
        when(profileClient.getUserDetailsByIds(List.of(ASSIGNEE_ID), context))
                .thenReturn(Mono.just(GetUserDetailsByIdsResponse.newBuilder().addUserDetails(userDetails).build()));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context
                ))
                .assertNext(response -> {
                    var cardLabels = response.getColumns().getFirst().getIssues().getFirst().getLabels();
                    assertThat(cardLabels).hasSize(1);
                    assertThat(cardLabels.getFirst().getId()).isEqualTo(LABEL_ID);
                    assertThat(cardLabels.getFirst().getName()).isEqualTo("urgent");
                    assertThat(cardLabels.getFirst().getColor()).isEqualTo("#FF0000");

                    var cardAssignee = response.getColumns().getFirst().getIssues().getFirst().getAssignee();
                    assertThat(cardAssignee.getDisplayName()).isEqualTo("Ivan Petrov");
                    assertThat(cardAssignee.getAvatarUrl()).isEqualTo("https://cdn/avatar.png");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Пропущенная в проекте метка отбрасывается с warn, доска не падает")
    void getBoard_skipsLabelMissingFromProject() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));

        var restIssue = boardIssueDto("TAS-1");
        var issueData = new BoardIssueData("TODO", List.of(LABEL_ID), restIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(), ISSUE_TYPE, null, null, false, List.of(), null, null, 50, context
        )).thenReturn(Mono.just(List.of(issueData)));

        var labelsResponse = new ListProjectLabelsResponseDto();
        labelsResponse.setItems(List.of());
        when(labelClient.listProjectLabels(PROJECT_ID.toString(), context))
                .thenReturn(Mono.just(labelsResponse));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context
                ))
                .assertNext(response -> assertThat(response.getColumns().getFirst().getIssues().getFirst().getLabels()).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("Сбой получения профилей возвращает 503")
    void getBoard_profileFailureFailsBoard() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));

        var restIssue = boardIssueDto("TAS-1");
        var assignee = new BoardUserDto();
        assignee.setId(ASSIGNEE_ID);
        restIssue.setAssignee(assignee);
        var issueData = new BoardIssueData("TODO", List.of(), restIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(), ISSUE_TYPE, null, null, false, List.of(), null, null, 50, context
        )).thenReturn(Mono.just(List.of(issueData)));
        when(profileClient.getUserDetailsByIds(List.of(ASSIGNEE_ID), context))
                .thenReturn(Mono.error(new IllegalStateException("auth-service unavailable")));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context
                ))
                .expectErrorMatches(error -> error instanceof org.springframework.web.server.ResponseStatusException status
                        && status.getStatusCode().value() == 503).verify();
    }

    @Test
    @DisplayName("Сбой получения меток возвращает 503")
    void getBoard_labelFailureFailsBoard() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));

        var restIssue = boardIssueDto("TAS-1");
        var issueData = new BoardIssueData("TODO", List.of(LABEL_ID), restIssue);

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(), ISSUE_TYPE, null, null, false, List.of(), null, null, 50, context
        )).thenReturn(Mono.just(List.of(issueData)));
        when(labelClient.listProjectLabels(PROJECT_ID.toString(), context))
                .thenReturn(Mono.error(new IllegalStateException("issue-service unavailable")));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context
                ))
                .expectErrorMatches(error -> error instanceof org.springframework.web.server.ResponseStatusException status
                        && status.getStatusCode().value() == 503).verify();
    }

    @Test
    @DisplayName("Сбой получения задач роняет доску")
    void getBoard_issueClientFailurePropagates() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("TODO", "To Do", "TODO", 1)));
        var failure = new IllegalStateException("issue-service unavailable");

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(), ISSUE_TYPE, null, null, false, List.of(), null, null, 50, context
        )).thenReturn(Mono.error(failure));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context
                ))
                .expectErrorMatches(error -> error == failure)
                .verify();

        Mockito.verifyNoInteractions(labelClient, profileClient);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {false, true})
    void getBoard_passesFinalStatusKeysFromCategories(Boolean includeDone) {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(
                workflowStatus("DONE", "Still in progress", "IN_PROGRESS", 1),
                workflowStatus("CLOSED", "Closed", "DONE", 2),
                workflowStatus("RELEASED", "Released", "DONE", 3)));
        var activeIssue = boardIssueDto("TAS-1");
        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context)).thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), ISSUE_TYPE, null, null,
                includeDone, List.of("CLOSED", "RELEASED"), null, null, 50, context))
                .thenReturn(Mono.just(List.of(new BoardIssueData("DONE", List.of(), activeIssue))));

        StepVerifier.create(boardService.getBoard(PROJECT_ID, ISSUE_TYPE, null, null, includeDone, null, null, 50, context))
                .assertNext(board -> assertThat(board.getColumns().getFirst().getIssues()).containsExactly(activeIssue))
                .verifyComplete();
        verify(issueClient).listIssuesForBoard(PROJECT_ID.toString(), ISSUE_TYPE, null, null,
                includeDone, List.of("CLOSED", "RELEASED"), null, null, 50, context);
    }

    @Test
    void getBoard_withoutFinalCategoriesDoesNotInventDoneKey() {
        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(workflowStatus("DONE", "Still open", "TODO", 1)));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context)).thenReturn(Mono.just(workflow));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), ISSUE_TYPE, null, null,
                false, List.of(), null, null, 50, context)).thenReturn(Mono.just(List.of()));
        StepVerifier.create(boardService.getBoard(PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context))
                .assertNext(board -> assertThat(board.getColumns()).hasSize(1)).verifyComplete();
    }

    @Test
    void getBoard_workflowFailureDoesNotReadIssuesWithoutTheirCategories() {
        var failure = new IllegalStateException("workflow unavailable");
        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context)).thenReturn(Mono.error(failure));
        StepVerifier.create(boardService.getBoard(PROJECT_ID, ISSUE_TYPE, null, null, false, null, null, 50, context))
                .expectErrorMatches(error -> error == failure).verify();
        Mockito.verifyNoInteractions(issueClient, labelClient, profileClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedBoardUsesDoneCategoryPerTypeRegardlessOfWorkflowArrivalOrder(boolean taskFirst) {
        var taskFlow = new WorkflowResponseDto();
        taskFlow.setStatuses(List.of(workflowStatus("REVIEW", "Task review", "DONE", 1)));
        var bugFlow = new WorkflowResponseDto();
        bugFlow.setStatuses(List.of(workflowStatus("REVIEW", "Bug review", "IN_PROGRESS", 1)));
        var bug = boardIssueDto("BUG-1");
        bug.setIssueType("BUG");
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context))
                .thenReturn(Mono.delay(java.time.Duration.ofMillis(taskFirst ? 1 : 30)).thenReturn(taskFlow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context))
                .thenReturn(Mono.delay(java.time.Duration.ofMillis(taskFirst ? 30 : 1)).thenReturn(bugFlow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.just(new WorkflowResponseDto().statuses(List.of())));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "TASK", null, null,
                false, List.of("REVIEW"), IssuePriorityDto.HIGH, "needle", 50, context)).thenReturn(Mono.just(List.of()));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "BUG", null, null,
                false, List.of(), IssuePriorityDto.HIGH, "needle", 50, context))
                .thenReturn(Mono.just(List.of(new BoardIssueData("REVIEW", List.of(), bug))));
        when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), "STORY", null, null,
                false, List.of(), IssuePriorityDto.HIGH, "needle", 50, context)).thenReturn(Mono.just(List.of()));
        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, false, IssuePriorityDto.HIGH, "needle", 50, context))
                .assertNext(board -> {
                    assertThat(board.getColumns()).singleElement().satisfies(column -> {
                        assertThat(column.getName()).isEqualTo("Task review");
                        assertThat(column.getIssues()).containsExactly(bug);
                    });
                    assertThat(board.getUnplacedIssues()).isEmpty();
                }).verifyComplete();
    }

    @Test
    void statusFoundOnlyInAnotherTypeWorkflowDoesNotMisplaceCard() {
        var taskFlow = new WorkflowResponseDto().statuses(List.of(workflowStatus("REVIEW", "Review", "IN_PROGRESS", 1)));
        var bugFlow = new WorkflowResponseDto().statuses(List.of(workflowStatus("TODO", "To Do", "TODO", 0)));
        var bug = boardIssueDto("BUG-1");
        bug.setIssueType("BUG");
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "TASK", context)).thenReturn(Mono.just(taskFlow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "BUG", context)).thenReturn(Mono.just(bugFlow));
        when(workflowClient.getWorkflowForProject(PROJECT_ID, "STORY", context))
                .thenReturn(Mono.just(new WorkflowResponseDto().statuses(List.of())));
        for (String type : List.of("TASK", "BUG", "STORY")) {
            when(issueClient.listIssuesForBoard(PROJECT_ID.toString(), type, null, null, true, List.of(), null, null, 50, context))
                    .thenReturn(Mono.just("BUG".equals(type) ? List.of(new BoardIssueData("REVIEW", List.of(), bug)) : List.of()));
        }
        StepVerifier.create(boardService.getBoard(PROJECT_ID, null, null, null, true, null, null, 50, context))
                .assertNext(board -> {
                    assertThat(board.getUnplacedIssues()).containsExactly(bug);
                    assertThat(board.getColumns()).allSatisfy(column -> assertThat(column.getIssues()).isEmpty());
                }).verifyComplete();
    }

    private WorkflowStatusDto workflowStatus(
            String statusKey,
            String name,
            String category,
            int sortOrder
    ) {
        var status = new WorkflowStatusDto();
        status.setStatusKey(statusKey);
        status.setName(name);
        status.setCategory(category);
        status.setSortOrder(sortOrder);
        return status;
    }

    private ru.taska.domain.dto.ListProjectIssueTypesDto registry(String... codes) {
        return new ru.taska.domain.dto.ListProjectIssueTypesDto(java.util.Arrays.stream(codes).map(code -> {
            var type = new ru.taska.domain.dto.ProjectIssueTypeDto();
            type.setCode(code);
            return type;
        }).toList());
    }

    private BoardIssueDto boardIssueDto(String issueKey) {
        var issue = new BoardIssueDto();
        issue.setId(UUID.randomUUID());
        issue.setIssueKey(issueKey);
        issue.setSummary("Test issue");
        issue.setIssueType("TASK");
        return issue;
    }
}
