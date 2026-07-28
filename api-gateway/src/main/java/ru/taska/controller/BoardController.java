package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.BoardApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.BoardColumnDto;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BoardController implements BoardApi {

    private final GatewayRequestExecutor executor;
    private final GrpcWorkflowServiceClient workflowClient;
    private final GrpcIssueServiceClient issueClient;

    @Override
    public Mono<ResponseEntity<BoardResponseDto>> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context -> {

            var workflowMono = workflowClient.getWorkflowForProject(projectId, issueType, context);

            var issuesMono = issueClient.listIssuesForBoard(
                    projectId.toString(),
                    issueType.name(),
                    assigneeId != null ? assigneeId.toString() : null,
                    labelId != null ? labelId.toString() : null,
                    includeDone,
                    context
            );

            return Mono.zip(workflowMono, issuesMono).map(tuple -> {
                var workflow = tuple.getT1();
                var issues = tuple.getT2();

                Map<String, List<BoardIssueDto>> issuesByStatus = issues.stream()
                        .collect(Collectors.groupingBy(BoardIssueDto::getStatusKey));

                List<BoardColumnDto> columns = workflow.getStatuses().stream()
                        .sorted(Comparator.comparingInt(WorkflowStatusDto::getSortOrder))
                        .map(status -> {
                            BoardColumnDto column = new BoardColumnDto();
                            column.setStatusKey(status.getStatusKey());
                            column.setName(status.getName());
                            column.setCategory(status.getCategory());
                            column.setSortOrder(status.getSortOrder());

                            column.setIssues(issuesByStatus.getOrDefault(status.getStatusKey(), new ArrayList<>()));
                            issuesByStatus.remove(status.getStatusKey());

                            return column;
                        })
                        .toList();

                if (!issuesByStatus.isEmpty()) {
                    log.error("[{}] Found issues with unknown statuses: {}", context.requestId(), issuesByStatus.keySet());
                    throw new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Inconsistent state: issues found with statuses not present in workflow"
                    );
                }

                BoardResponseDto response = new BoardResponseDto();
                response.setProjectId(projectId);
                response.setIssueType(issueType);
                response.setColumns(columns);

                return ResponseEntity.ok(response);
            });
        });
    }
}