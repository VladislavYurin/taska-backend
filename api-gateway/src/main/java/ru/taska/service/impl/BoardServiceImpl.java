package ru.taska.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.profile.v1.UserDetails;
import ru.taska.domain.BoardIssueData;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.BoardColumnDto;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.domain.dto.BoardUserDto;
import ru.taska.domain.dto.IssueLabelResponseDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.mapper.BoardMapper;
import ru.taska.service.BoardService;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcLabelServiceClient;
import ru.taska.transport.grpc.GrpcUserProfileServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Сервис формирования доски проекта на основе workflow и задач из issue-service.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class BoardServiceImpl implements BoardService {

    private static final List<IssueTypeDto> ALL_ISSUE_TYPES = List.of(IssueTypeDto.TASK, IssueTypeDto.BUG, IssueTypeDto.STORY);

    private final GrpcWorkflowServiceClient workflowClient;
    private final GrpcIssueServiceClient issueClient;
    private final GrpcLabelServiceClient labelClient;
    private final GrpcUserProfileServiceClient profileClient;
    private final BoardMapper boardMapper;

    /**
     * Формирует доску проекта: получает workflow, загружает задачи, обогащает их
     * метками и профилем исполнителя и группирует по колонкам workflow.
     *
     * @param projectId  идентификатор проекта
     * @param issueType  тип задач; {@code null} — задачи всех типов
     * @param assigneeId фильтр по исполнителю
     * @param labelId    фильтр по метке
     * @param includeDone включать ли завершенные задачи
     * @param priority   фильтр по приоритету задачи
     * @param query      полнотекстовый фильтр по ключу, краткому описанию и описанию задачи
     * @param context    контекст запроса
     * @return сформированная доска проекта
     */
    @Override
    public Mono<BoardResponseDto> getBoard(
            UUID projectId,
            IssueTypeDto issueType,
            UUID assigneeId,
            UUID labelId,
            Boolean includeDone,
            IssuePriorityDto priority,
            String query,
            GatewayContext context
    ) {
        log.info("[{}] Getting board for project: {}", context.requestId(), projectId);

        return resolveWorkflows(projectId, issueType, context).flatMap(workflows -> {
            List<WorkflowStatusDto> statuses = mergeStatusesByKey(workflows.stream()
                    .filter(WorkflowTypeResolution::isFound).map(WorkflowTypeResolution::statuses).toList());
            // DONE belongs to (issueType, statusKey). Filter in issue-service before merging columns.
            return Flux.fromIterable(workflows).flatMapSequential(workflow -> {
                List<String> doneKeys = workflow.isFound() ? workflow.statuses().stream()
                        .filter(status -> "DONE".equals(status.getCategory()))
                        .map(WorkflowStatusDto::getStatusKey).distinct().toList() : List.of();
                return issueClient.listIssuesForBoard(projectId.toString(), workflow.issueType(),
                        assigneeId != null ? assigneeId.toString() : null,
                        labelId != null ? labelId.toString() : null, includeDone, doneKeys, priority, query, context);
            }).flatMapIterable(rows -> rows).collectList()
                    .flatMap(issues -> enrichAndBuildBoard(projectId, statuses, issues, context));
        });
    }

    private Mono<List<WorkflowTypeResolution>> resolveWorkflows(UUID projectId, IssueTypeDto issueType, GatewayContext context) {
        if (issueType != null) {
            return workflowClient.getWorkflowForProject(projectId, issueType, context)
                    .map(workflow -> List.of(WorkflowTypeResolution.found(issueType, workflow.getStatuses())));
        }
        // Preserve TASK/BUG/STORY precedence regardless of RPC completion order.
        return Flux.fromIterable(ALL_ISSUE_TYPES)
                .flatMapSequential(type -> workflowClient.getWorkflowForProject(projectId, type, context)
                        .map(workflow -> WorkflowTypeResolution.found(type, workflow.getStatuses()))
                        .onErrorResume(StatusRuntimeException.class, error -> {
                            if (error.getStatus().getCode() != Status.Code.NOT_FOUND) return Mono.error(error);
                            log.warn("[{}] Workflow missing for {} / {}", context.requestId(), projectId, type);
                            return Mono.just(new WorkflowTypeResolution(type, null, error));
                        }))
                .collectList().flatMap(results -> results.stream().anyMatch(WorkflowTypeResolution::isFound)
                        ? Mono.just(results) : Mono.error(results.getFirst().notFoundError()));
    }

    private record WorkflowTypeResolution(IssueTypeDto issueType, List<WorkflowStatusDto> statuses, StatusRuntimeException notFoundError) {
        static WorkflowTypeResolution found(IssueTypeDto type, List<WorkflowStatusDto> statuses) {
            return new WorkflowTypeResolution(type, statuses, null);
        }
        boolean isFound() { return statuses != null; }
    }

    private List<WorkflowStatusDto> mergeStatusesByKey(List<List<WorkflowStatusDto>> statusesPerType) {
        Map<String, WorkflowStatusDto> statusesByKey = new LinkedHashMap<>();

        for (List<WorkflowStatusDto> statuses : statusesPerType) {
            for (WorkflowStatusDto status : statuses) {
                statusesByKey.putIfAbsent(status.getStatusKey(), status);
            }
        }

        return statusesByKey.values().stream()
                .sorted(Comparator.comparingInt(WorkflowStatusDto::getSortOrder))
                .toList();
    }

    /**
     * Обогащает задачи доски метками ({id, name, color}) и профилем исполнителя
     * (displayName, avatarUrl), затем группирует их по колонкам workflow.
     * Сбой обогащения (метки или профили) не должен ронять доску — только warn в лог;
     * задача карточки остаётся без соответствующих полей.
     */
    private Mono<BoardResponseDto> enrichAndBuildBoard(
            UUID projectId,
            List<WorkflowStatusDto> statuses,
            List<BoardIssueData> issues,
            GatewayContext context
    ) {
        return Mono.zip(
                resolveLabelsById(projectId, issues, context),
                resolveUsersById(issues, context)
        ).map(tuple -> buildBoard(projectId, statuses, issues, tuple.getT1(), tuple.getT2(), context));
    }

    private Mono<Map<UUID, ProjectLabelResponseDto>> resolveLabelsById(
            UUID projectId,
            List<BoardIssueData> issues,
            GatewayContext context
    ) {
        boolean hasLabels = issues.stream().anyMatch(data -> !data.labelIds().isEmpty());
        if (!hasLabels) {
            return Mono.just(Map.of());
        }

        return labelClient.listProjectLabels(projectId.toString(), context)
                .map(response -> response.getItems().stream()
                        .collect(Collectors.toMap(ProjectLabelResponseDto::getId, label -> label, (first, duplicate) -> first)))
                .onErrorResume(e -> {
                    log.warn("[{}] Failed to load project labels for board, labels left unresolved: {}",
                            context.requestId(), e.getMessage());
                    return Mono.just(Map.of());
                });
    }

    private Mono<Map<UUID, UserDetails>> resolveUsersById(
            List<BoardIssueData> issues,
            GatewayContext context
    ) {
        List<UUID> assigneeIds = issues.stream()
                .map(data -> data.issue().getAssignee())
                .filter(Objects::nonNull)
                .map(BoardUserDto::getId)
                .distinct()
                .toList();

        if (assigneeIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return profileClient.getUserDetailsByIds(assigneeIds, context)
                .map(response -> response.getUserDetailsList().stream()
                        .collect(Collectors.toMap(user -> UUID.fromString(user.getUserId()), user -> user, (first, duplicate) -> first)))
                .onErrorResume(e -> {
                    log.warn("[{}] Failed to load user profiles for board, assignee displayName left unresolved: {}",
                            context.requestId(), e.getMessage());
                    return Mono.just(Map.of());
                });
    }

    private BoardResponseDto buildBoard(
            UUID projectId,
            List<WorkflowStatusDto> statuses,
            List<BoardIssueData> issues,
            Map<UUID, ProjectLabelResponseDto> labelsById,
            Map<UUID, UserDetails> usersById,
            GatewayContext context
    ) {
        issues.forEach(data -> enrichIssue(data, labelsById, usersById, context));

        Map<String, List<BoardIssueDto>> issuesByStatus = issues.stream()
                .collect(Collectors.groupingBy(
                        BoardIssueData::statusKey, LinkedHashMap::new,
                        Collectors.mapping(BoardIssueData::issue, Collectors.toList())
                ));

        List<BoardColumnDto> columns = statuses.stream()
                .sorted(Comparator.comparingInt(WorkflowStatusDto::getSortOrder))
                .map(status -> {
                    List<BoardIssueDto> issuesForColumn = issuesByStatus.remove(status.getStatusKey());
                    return boardMapper.toBoardColumn(
                            status,
                            issuesForColumn != null ? issuesForColumn : List.of()
                    );
                })
                .toList();

        if (!issuesByStatus.isEmpty()) {
            log.warn(
                    "[{}] Found issues with statuses outside of workflow, moved to unplacedIssues: {}",
                    context.requestId(),
                    issuesByStatus.keySet()
            );
        }

        List<BoardIssueDto> unplacedIssues = issuesByStatus.values().stream()
                .flatMap(List::stream)
                .toList();

        return boardMapper.toBoardResponse(projectId, columns, unplacedIssues);
    }

    private void enrichIssue(
            BoardIssueData data,
            Map<UUID, ProjectLabelResponseDto> labelsById,
            Map<UUID, UserDetails> usersById,
            GatewayContext context
    ) {
        List<IssueLabelResponseDto> labels = data.labelIds().stream()
                .map(labelId -> {
                    ProjectLabelResponseDto label = labelsById.get(labelId);
                    if (label == null) {
                        log.warn("[{}] Label {} not found in project labels, skipping", context.requestId(), labelId);
                        return null;
                    }
                    var labelDto = new IssueLabelResponseDto();
                    labelDto.setId(label.getId());
                    labelDto.setName(label.getName());
                    labelDto.setColor(label.getColor());
                    return labelDto;
                })
                .filter(Objects::nonNull)
                .toList();
        data.issue().setLabels(labels);

        BoardUserDto assignee = data.issue().getAssignee();
        if (assignee != null) {
            UserDetails user = usersById.get(assignee.getId());
            if (user == null) {
                log.warn("[{}] User profile {} not found, assignee displayName left null",
                        context.requestId(), assignee.getId());
                return;
            }
            assignee.setDisplayName(user.getDisplayName());
            if (user.hasAvatar()) {
                assignee.setAvatarUrl(user.getAvatar().getDownloadUrl());
            }
        }
    }
}
