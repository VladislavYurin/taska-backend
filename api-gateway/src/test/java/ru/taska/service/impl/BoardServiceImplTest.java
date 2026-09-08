package ru.taska.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.issue.v1.IssueBoardResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.mapper.BoardMapper;
import ru.taska.mapper.IssueMapper;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
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
    private static final IssueTypeDto ISSUE_TYPE = IssueTypeDto.TASK;

    @Mock
    private GrpcWorkflowServiceClient workflowClient;

    @Mock
    private GrpcIssueServiceClient issueClient;

    @Mock
    private IssueMapper issueMapper;

    private BoardServiceImpl boardService;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        boardService = new BoardServiceImpl(
                workflowClient,
                issueClient,
                new BoardMapper(),
                issueMapper
        );

        context = new GatewayContext(
                "request-id",
                "api-gateway",
                null
        );
    }

    @Test
    @DisplayName("Должен отсортировать колонки workflow и распределить задачи по статусам")
    void getBoard_shouldSortColumnsAndGroupIssuesByStatus() {
        var todoStatus = workflowStatus("TODO", "To Do", "TODO", 1);
        var doneStatus = workflowStatus("DONE", "Done", "DONE", 2);

        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(doneStatus, todoStatus));

        var todoIssue = issue("TODO", "TAS-1");
        var doneIssue = issue("DONE", "TAS-2");

        var todoRestIssue = boardIssueDto("TAS-1");
        var doneRestIssue = boardIssueDto("TAS-2");

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));

        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE.name(),
                ASSIGNEE_ID.toString(),
                LABEL_ID.toString(),
                true,
                context
        )).thenReturn(Mono.just(List.of(doneIssue, todoIssue)));

        when(issueMapper.toRestBoardIssue(todoIssue)).thenReturn(todoRestIssue);
        when(issueMapper.toRestBoardIssue(doneIssue)).thenReturn(doneRestIssue);

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID,
                        ISSUE_TYPE,
                        ASSIGNEE_ID,
                        LABEL_ID,
                        true,
                        context
                ))
                .assertNext(response -> {
                    assertThat(response.getProjectId()).isEqualTo(PROJECT_ID);
                    assertThat(response.getIssueType()).isEqualTo(ISSUE_TYPE);
                    assertThat(response.getColumns()).hasSize(2);

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
                ISSUE_TYPE.name(),
                ASSIGNEE_ID.toString(),
                LABEL_ID.toString(),
                true,
                context
        );
    }

    @Test
    @DisplayName("Должен вернуть 500 если задача содержит статус, отсутствующий в workflow")
    void getBoard_shouldFailWhenIssueHasUnknownStatus() {
        var todoStatus = workflowStatus("TODO", "To Do", "TODO", 1);

        var workflow = new WorkflowResponseDto();
        workflow.setStatuses(List.of(todoStatus));

        var unknownIssue = issue("UNKNOWN", "TAS-99");

        when(workflowClient.getWorkflowForProject(PROJECT_ID, ISSUE_TYPE, context))
                .thenReturn(Mono.just(workflow));

        when(issueClient.listIssuesForBoard(
                PROJECT_ID.toString(),
                ISSUE_TYPE.name(),
                null,
                null,
                false,
                context
        )).thenReturn(Mono.just(List.of(unknownIssue)));

        StepVerifier.create(boardService.getBoard(
                        PROJECT_ID,
                        ISSUE_TYPE,
                        null,
                        null,
                        false,
                        context
                ))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);

                    var exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getReason())
                            .isEqualTo(
                                    "Inconsistent state: issues found with statuses not present in workflow"
                            );
                })
                .verify();
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

    private IssueBoardResponse issue(String statusKey, String issueKey) {
        return IssueBoardResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setIssueKey(issueKey)
                .setSummary("Test issue")
                .setStatusKey(statusKey)
                .build();
    }

    private BoardIssueDto boardIssueDto(String issueKey) {
        var issue = new BoardIssueDto();
        issue.setId(UUID.randomUUID());
        issue.setIssueKey(issueKey);
        issue.setSummary("Test issue");
        return issue;
    }
}
