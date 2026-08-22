package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.BoardIssue;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.*;
import ru.taska.mapper.BoardMapper;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.BoardService;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Реализация сервиса {@link BoardService} для получения данных доски задач.
 * <p>
 * Выполняет параллельные gRPC-вызовы к {@code workflow-service} и {@code issue-service},
 * после чего объединяет полученные статусы и задачи в единую структуру доски.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class BoardServiceImpl implements BoardService {

    private final GrpcWorkflowServiceClient workflowClient;
    private final GrpcIssueServiceClient issueClient;
    private final BoardMapper boardMapper;
    private final IssueMapper issueMapper;

    /**
     * Агрегирует workflow статусы и задачи для формирования доски проекта.
     * <p>
     * Алгоритм работы:
     * 1. Запрашивает workflow (статусы) для заданного типа задач в проекте из {@code workflow-service}.
     * 2. Запрашивает список задач по переданным фильтрам из {@code issue-service}.
     * 3. Группирует полученные задачи по ключу статуса ({@code statusKey}).
     * 4. Создает колонки доски на основе workflow статусов, сохраняя их порядок сортировки ({@code sortOrder}).
     * 5. Наполняет колонки соответствующими задачами. Если для статуса нет задач, возвращает пустую колонку.
     * 6. Выполняет валидацию: если найдены задачи со статусами, отсутствующими в workflow, выбрасывает {@link ResponseStatusException} с кодом 500 (INTERNAL_SERVER_ERROR).
     *
     * @param projectId   уникальный идентификатор проекта
     * @param issueType   тип задач для запроса workflow и задач
     * @param assigneeId  (опционально) идентификатор исполнителя
     * @param labelId     (опционально) идентификатор метки
     * @param includeDone флаг включения завершенных задач
     * @param context     сквозной контекст выполнения запроса, содержащий requestId и данные пользователя
     * @return {@link Mono} с REST DTO {@link BoardResponseDto}, представляющим структуру доски
     * @throws ResponseStatusException если после распределения задач остались задачи с неизвестными статусами
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
                    List<BoardIssue> issues = tuple.getT2();

                    Map<String, List<BoardIssue>> issuesByStatus =
                            issues.stream()
                                    .collect(Collectors.groupingBy(BoardIssue::getStatusKey));

                    List<BoardColumnDto> columns =
                            workflow.getStatuses()
                                    .stream()
                                    .sorted(Comparator.comparingInt(WorkflowStatusDto::getSortOrder))
                                    .map(status -> {

                                        List<BoardIssueDto> issuesForColumn =
                                                issuesByStatus.getOrDefault(
                                                                status.getStatusKey(),
                                                                List.<BoardIssue>of()
                                                        )
                                                        .stream()
                                                        .map(issueMapper::toRestBoardIssue)
                                                        .toList();

                                        issuesByStatus.remove(status.getStatusKey());

                                        return boardMapper.toBoardColumn(
                                                status,
                                                issuesForColumn
                                        );
                                    })
                                    .toList();

                    if (!issuesByStatus.isEmpty()) {
                        log.error(
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