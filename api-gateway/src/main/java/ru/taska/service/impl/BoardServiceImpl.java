package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.domain.BoardIssueData;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;
import ru.taska.mapper.BoardMapper;
import ru.taska.service.BoardService;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Сервис формирования доски проекта на основе workflow и задач из issue-service.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class BoardServiceImpl implements BoardService {

    private final GrpcWorkflowServiceClient workflowClient;
    private final GrpcIssueServiceClient issueClient;
    private final BoardMapper boardMapper;

    /**
     * Формирует доску проекта: получает workflow, загружает задачи и группирует их по статусам.
     *
     * @param projectId идентификатор проекта
     * @param issueType тип задач
     * @param assigneeId фильтр по исполнителю
     * @param labelId фильтр по метке
     * @param includeDone включать ли завершенные задачи
     * @param context контекст запроса
     * @return сформированная доска проекта
     */
    @Override
    public Mono<BoardResponseDto> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            GatewayContext context
    ) {
        log.info("[{}] Getting board for project: {}", context.requestId(), projectId);

        var workflowMono = workflowClient.getWorkflowForProject(projectId, issueType, context);

        var issuesMono = issueClient.listIssuesForBoard(
                projectId.toString(),
                issueType.name(),
                assigneeId != null ? assigneeId.toString() : null,
                labelId != null ? labelId.toString() : null,
                includeDone,
                context
        );

        return Mono.zip(workflowMono, issuesMono)
                .map(tuple -> {

                    WorkflowResponseDto workflow = tuple.getT1();
                    List<BoardIssueData> issues = tuple.getT2();

                    Map<String, List<BoardIssueData>> issuesByStatus =
                            issues.stream()
                                    .collect(Collectors.groupingBy(BoardIssueData::statusKey));

                    List<BoardColumnDto> columns =
                            workflow.getStatuses()
                                    .stream()
                                    .sorted(Comparator.comparingInt(WorkflowStatusDto::getSortOrder))
                                    .map(status -> {

                                        List<BoardIssueDto> issuesForColumn =
                                                issuesByStatus.getOrDefault(
                                                                status.getStatusKey(),
                                                                List.<BoardIssueData>of()
                                                        )
                                                        .stream()
                                                        .map(BoardIssueData::issue)
                                                        .toList();

                                        issuesByStatus.remove(status.getStatusKey());

                                        return boardMapper.toBoardColumn(
                                                status,
                                                issuesForColumn
                                        );
                                    })
                                    .toList();

                    if (!issuesByStatus.isEmpty()) {
                        log.warn(
                                "[{}] Found issues with unknown statuses: {}",
                                context.requestId(),
                                issuesByStatus.keySet()
                        );

                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Inconsistent state: issues found with statuses not present in workflow"
                        );
                    }

                    return boardMapper.toBoardResponse(
                            projectId,
                            issueType,
                            columns
                    );
                });
    }
}