package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.AddIssueLabelRequest;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.CreateProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.DeleteProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssueLabelsRequest;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListIssuesForBoardRequest;
import ru.taska.api.issue.v1.ListIssuesForBoardResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.RemoveIssueLabelRequest;
import ru.taska.api.issue.v1.RemoveIssueLabelResponse;
import ru.taska.api.issue.v1.SearchIssuesRequest;
import ru.taska.api.issue.v1.SearchIssuesResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.api.issue.v1.UpdateProjectLabelRequest;
import ru.taska.domain.dto.labels.LabelCommands;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.mapper.LabelMapper;
import ru.taska.service.IssueService;
import ru.taska.service.IssueWatcherService;
import ru.taska.service.LabelService;
import ru.taska.service.transition.IssueTransitionService;
import ru.taska.transport.grpc.logging.GrpcIssueLogging;
import validator.GrpcRequestValidators;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ru.taska.transport.grpc.logging.GrpcIssueLogging.logOnError;
import static ru.taska.transport.grpc.logging.GrpcIssueLogging.logValidationError;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueService {

    private final IssueService issueService;
    private final IssueWatcherService issueWatcherService;
    private final IssueTransitionService issueTransitionService;
    private final IssueMapper issueMapper;
    private final LabelService labelService;
    private final LabelMapper labelMapper;

    @TrackMetrics(counter = "issue-service_create-issue_grpc_counter",
            timer = "issue-service_create-issue_grpc_timer")
    public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.validateIdempotencyKey(
                                        req.getBody().getIdempotencyKey(), "body.idempotencyKey"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getProjectId(), "body.projectId"
                                ),
                                GrpcRequestValidators.requireSpecifiedOrInvalidArgument(
                                        req.getBody().getIssueType(), "body.issueType"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getSummary(), "body.summary"
                                ),
                                GrpcRequestValidators.requireSpecifiedOrInvalidArgument(
                                        req.getBody().getPriority(), "body.priority"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getReporterId(), "body.reporterId"
                                ))
                        .doOnError(StatusRuntimeException.class, logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "createIssue"
                        ))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            String idempotencyKey = t.getT3();
                            UUID projectId = t.getT4();
                            IssueType issueType = t.getT5();
                            String summary = t.getT6();
                            IssuePriority priority = t.getT7();
                            UUID reporterId = t.getT8();

                            String description = req.getBody().getDescription();

                            log.info("[{}][{}] createIssue: idempotencyKey={}, projectId={}, issueType={}, summary={}, priority={}, reporterId={}",
                                    requestId, nodeId, idempotencyKey, projectId, issueType, summary, priority, reporterId);

                            return issueService.createIssue(
                                            requestId,
                                            nodeId,
                                            idempotencyKey,
                                            projectId,
                                            issueMapper.toDomainIssueType(issueType),
                                            summary,
                                            description,
                                            issueMapper.toDomainIssuePriority(priority),
                                            reporterId
                                    )
                                    .doOnNext(issue ->
                                            log.info("[{}][{}] createIssue: successfully created, issueId={}",
                                                    requestId, nodeId, issue.getId())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "createIssue")
                                    );
                        }))
                .map(issueMapper::toIssueProto);
    }

    @TrackMetrics(counter = "issue-service_get-issue_grpc_counter",
            timer = "issue-service_get-issue_grpc_timer")
    public Mono<IssueWithHistoryResponse> getIssue(Mono<GetIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(
                                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "getIssue")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] getIssue: issueId={}, actorUserId={}",
                                    requestId, nodeId, issueId, actorUserId);

                            return issueService.getIssue(
                                            requestId,
                                            nodeId,
                                            issueId,
                                            actorUserId
                                    )
                                    .flatMap(issueWithHistory -> issueWatcherService
                                            .getWatchState(issueId, actorUserId)
                                            .map(watchState -> issueMapper.toIssueDetailsProto(
                                                    issueWithHistory, watchState
                                            ))
                                    )
                                    .doOnSuccess(e ->
                                            log.info("[{}][{}] getIssue: successfully found, issueId={}, actorUserId={}",
                                                    requestId, nodeId, issueId, actorUserId)
                                    )
                                    .doOnError(logOnError(requestId, nodeId, "getIssue"));
                        }));
    }

    @TrackMetrics(counter = "issue-service_list-issues_grpc_counter",
            timer = "issue-service_list-issues_grpc_timer")
    public Mono<ListIssuesResponse> listIssues(Mono<ListIssuesRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"
                                        ),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"
                                        ),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getProjectId(), "body.projectId"
                                        ),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId"
                                        ),
                                        req.getBody().hasAssigneeId()
                                                ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getAssigneeId(), "body.assigneeId").map(Optional::of)
                                                : Mono.just(Optional.<UUID>empty()),
                                        req.getBody().hasLabelId()
                                                ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getLabelId(), "body.labelId").map(Optional::of)
                                                : Mono.just(Optional.<UUID>empty())
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(
                                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listIssues")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();
                                    UUID projectId = t.getT3();
                                    UUID actorUserId = t.getT4();
                                    UUID assigneeId = t.getT5().orElse(null);
                                    UUID labelId = t.getT6().orElse(null);
                                    String statusKey = req.getBody().hasStatusKey()
                                            ? req.getBody().getStatusKey()
                                            : null;
                                    Integer pageSize = req.getBody().hasPageSize()
                                            ? req.getBody().getPageSize()
                                            : null;
                                    Integer page = req.getBody().hasPage()
                                            ? req.getBody().getPage()
                                            : null;

                                    log.info("[{}][{}] listIssues: projectId={}, actorUserId={}, status={}, assigneeId={}, page={}, pageSize={}, labelId={}",
                                            requestId, nodeId,
                                            projectId,
                                            actorUserId,
                                            statusKey,
                                            assigneeId,
                                            page,
                                            pageSize,
                                            labelId
                                    );

                                    return issueService.listIssues(
                                                    requestId,
                                                    nodeId,
                                                    projectId,
                                                    actorUserId,
                                                    statusKey,
                                                    assigneeId,
                                                    labelId,
                                                    page,
                                                    pageSize
                                            )
                                            .map(result -> ListIssuesResponse.newBuilder()
                                                    .addAllIssues(
                                                            result.items().stream()
                                                                    .map(issueWithLabels->issueMapper.toIssueProto(
                                                                            issueWithLabels.issue(),
                                                                            issueWithLabels.labels()
                                                                    ))
                                                                    .toList()
                                                    )
                                                    .setTotalCount((int) result.totalCount())
                                                    .build()
                                            )
                                            .doOnNext(result ->
                                                    log.info("[{}][{}] listIssues: successfully found {} issues",
                                                            requestId, nodeId, result.getTotalCount())
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "listIssues")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_assign-issue_grpc_counter",
            timer = "issue-service_assign-issue_grpc_timer")
    public Mono<IssueResponse> assignIssue(Mono<AssignIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getAssigneeId(), "body.assigneeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(
                                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "assignIssue")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID assigneeId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] assignIssue: issueId={}, assigneeId={}, actorUserId={}",
                                    requestId, nodeId, issueId, assigneeId, actorUserId);

                            return issueService.assignIssue(
                                            requestId,
                                            nodeId,
                                            issueId,
                                            assigneeId,
                                            actorUserId
                                    )
                                    .doOnSuccess(e ->
                                            log.info("[{}][{}] assignIssue: successfully assigned, issueId={}",
                                                    requestId, nodeId, issueId)
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "assignIssue")
                                    );
                        }))
                .map(issueMapper::toIssueProto);
    }


    /**
     * Удаляет задачу на основе Mono<{@link ru.taska.api.issue.v1.DeleteIssueRequest}>
     *
     * @param request .proto с айди задачи и инициатором удаления
     * @return Mono<{@link DeleteIssueResponse}> с соответствующими параметрами созданного проекта
     */
    @TrackMetrics(counter = "issue-service_delete-issue_grpc_counter",
            timer = "issue-service_delete-issue_grpc_timer")
    public Mono<DeleteIssueResponse> deleteIssue(Mono<DeleteIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(
                                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "deleteIssue")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] deleteIssue: issueId = {}, actorUserId = {}",
                                    requestId, nodeId, issueId, actorUserId);

                            return issueService.deleteIssue(
                                    requestId,
                                    nodeId,
                                    issueId,
                                    actorUserId
                            );
                        })
                        .map(issueMapper::toDeleteIssueProto));
    }

    /**
     * Обновляет задачу на основе Mono<{@link ru.taska.api.issue.v1.UpdateIssueRequest}>
     *
     * @param request .proto с параметрами на обновление задачи
     * @return Mono<{@link UpdateIssueResponse}> с соответствующими параметрами созданного проекта
     */
    @TrackMetrics(counter = "issue-service_update-issue_grpc_counter",
            timer = "issue-service_update-issue_grpc_timer")
    public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueId(), "body.issueId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getSummary(), "body.summary"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getDescription(), "body.description"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getPriority(), "body.priority")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID issueId = t.getT3();
                    UUID actorUserId = t.getT4();
                    String summary = t.getT5();
                    String description = t.getT6();
                    ru.taska.domain.IssuePriority priority = issueMapper.toDomainIssuePriority(t.getT7());

                    log.info("[{}][{}] updateIssue: issueId = {}, actorUserId = {}, summary = {}, description = {}, priority = {}",
                            requestId, nodeId, issueId, actorUserId, summary, description, priority);
                    return issueService.updateIssue(requestId, nodeId, issueId, actorUserId, summary, description, priority);
                })
                .map(issueMapper::toUpdateIssueProto);
    }

    /**
     * Выполняет переход задачи по workflow.
     *
     * @param request {@link Mono} с запросом {@link TransitionIssueRequest} с данными для перехода задачи по workflow.
     * @return {@link Mono} с ответом {@link IssueWithHistoryResponse}, включающим обновленные данные задачи с историей изменений.
     */
    @TrackMetrics(counter = "issue-service_transition-issue_grpc_counter",
            timer = "issue-service_transition-issue_grpc_timer")
    public Mono<IssueWithHistoryResponse> transitionIssue(Mono<TransitionIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getTransitionId(), "body.transitionId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                Mono.just(req.getBody().getPayload())
                        )
                        .doOnError(StatusRuntimeException.class,
                                GrpcIssueLogging.logValidationError(
                                        req.getHeader().getRequestId(),
                                        req.getHeader().getNodeId(),
                                        "transitionIssue"
                                ))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID transitionId = t.getT4();
                            UUID actorUserId = t.getT5();
                            String payload = t.getT6();

                            log.info("[{}][{}] transitionIssue: issueId={}, transitionId={}, actorUserId={}",
                                    requestId, nodeId, issueId, transitionId, actorUserId);

                            return issueTransitionService.transitionIssue(
                                            requestId,
                                            nodeId,
                                            issueId,
                                            transitionId,
                                            actorUserId,
                                            payload
                                    )
                                    .doOnNext(issueWithHistory ->
                                            log.info(
                                                    "[{}][{}] transitionIssue: issue successfully transitioned, id={}, statusKey={}",
                                                    requestId, nodeId,
                                                    issueWithHistory.getIssue().getId(),
                                                    issueWithHistory.getIssue().getStatusKey())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "transitionIssue")
                                    );
                        }))
                .map(issueMapper::toIssueDetailsProto);
    }


@TrackMetrics(counter = "issue-service_create-project-label_grpc_counter",
            timer = "issue-service_create-project-label_grpc_timer")
    public Mono<ProjectLabelResponse> createProjectLabel(Mono<CreateProjectLabelRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getProjectId(), "body.projectId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getName(), "body.name"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getColor(), "body.color"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "createProjectLabel")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();

                            log.info("[{}][{}] createProjectLabel: projectId={}, name={}, color={}",
                                    requestId, nodeId,
                                    req.getBody().getProjectId(),
                                    req.getBody().getName(),
                                    req.getBody().getColor()
                            );

                            LabelCommands.CreateProjectLabelRequestDto requestDto = labelMapper.toCreateProjectLabelRequestDto(req);

                            return labelService.createProjectLabel(requestId, nodeId, requestDto)
                                    .map(labelMapper::toProjectLabelProtoResponse)
                                    .doOnSuccess(result ->
                                            log.info("[{}][{}] createProjectLabel: successfully created, labelId={}",
                                                    requestId, nodeId, result.getId())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "createProjectLabel")
                                    );
                        })
                );
    }

    @TrackMetrics(counter = "issue-service_create-project-label_grpc_counter",
            timer = "issue-service_create-project-label_grpc_timer")
    public Mono<ProjectLabelResponse> updateProjectLabel(Mono<UpdateProjectLabelRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getLabelId(), "body.labelId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getProjectId(), "body.projectId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getName(), "body.name"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getColor(), "body.color"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "updateProjectLabel")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID labelId = t.getT3();


                            log.info("[{}][{}] updateProjectLabel: labelId={},projectId={}, name={}, color={}",
                                    requestId, nodeId,
                                    labelId,
                                    req.getBody().getProjectId(),
                                    req.getBody().getName(),
                                    req.getBody().getColor()
                            );

                            LabelCommands.UpdateProjectLabelRequestDto requestDto = labelMapper.toUpdateProjectLabelRequestDto(req);

                            return labelService.updateProjectLabel(requestId, nodeId, requestDto)
                                    .map(labelMapper::toProjectLabelProtoResponse)
                                    .doOnSuccess(result ->
                                            log.info("[{}][{}] updateProjectLabel: successfully updated, labelId={}",
                                                    requestId, nodeId, labelId)
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "updateProjectLabel")
                                    );
                        })
                );
    }

    @TrackMetrics(counter = "issue-service_delete-project-label_grpc_counter",
            timer = "issue-service_delete-project-label_grpc_timer")
    public Mono<DeleteProjectLabelResponse> deleteProjectLabel(Mono<DeleteProjectLabelRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getLabelId(), "body.labelId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getProjectId(), "body.projectId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId")
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "deleteProjectLabel")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();
                                    UUID labelId = t.getT3();

                                    log.info("[{}][{}] deleteProjectLabel: labelId={}, projectId={}",
                                            requestId, nodeId,
                                            labelId,
                                            req.getBody().getProjectId()
                                    );

                                    LabelCommands.DeleteProjectLabelRequestDto requestDto = labelMapper.toDeleteProjectLabelRequestDto(req);

                                    return labelService.deleteProjectLabel(requestId, nodeId, requestDto)
                                            .map(labelMapper::toDeleteProjectLabelProtoResponse)
                                            .doOnSuccess(result ->
                                                    log.info("[{}][{}] deleteProjectLabel: successfully deleted, labelId={}",
                                                            requestId, nodeId, labelId)
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "deleteProjectLabel")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_list-project-labels_grpc_counter",
            timer = "issue-service_list-project-labels_grpc_timer")
    public Mono<ListProjectLabelsResponse> listProjectLabels(Mono<ListProjectLabelsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getProjectId(), "body.projectId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId")
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listProjectLabels")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();

                                    log.info("[{}][{}] listProjectLabels: projectId={}",
                                            requestId, nodeId,
                                            req.getBody().getProjectId()
                                    );

                                    LabelCommands.ListProjectLabelsRequestDto requestDto = labelMapper.toListProjectLabelsRequestDto(req);

                                    return labelService.listProjectLabels(requestId, nodeId, requestDto)
                                            .map(labelMapper::toListProjectLabelsProtoResponse)
                                            .doOnSuccess(result ->
                                                    log.info("[{}][{}] listProjectLabels: successfully found {} labels",
                                                            requestId, nodeId, result.getTotalCount())
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "listProjectLabels")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_add-issue-label_grpc_counter",
            timer = "issue-service_add-issue-label_grpc_timer")
    public Mono<AddIssueLabelResponse> addIssueLabel(Mono<AddIssueLabelRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getIssueId(), "body.issueId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getLabelId(), "body.labelId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId")
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "addIssueLabel")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();
                                    String issueId = t.getT2();
                                    UUID labelId = t.getT4();

                                    log.info("[{}][{}] addIssueLabel: issueId={}, labelId={}",
                                            requestId, nodeId,
                                            issueId,
                                            labelId
                                    );

                                    LabelCommands.AddIssueLabelRequestDto requestDto = labelMapper.toAddIssueLabelRequestDto(req);

                                    return labelService.addIssueLabel(requestId, nodeId, requestDto)
                                            .map(labelMapper::toAddIssueLabelProtoResponse)
                                            .doOnSuccess(result ->
                                                    log.info("[{}][{}] addIssueLabel: successfully added label={}",
                                                            requestId, nodeId, labelId)
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "addIssueLabel")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_remove-issue-label_grpc_counter",
            timer = "issue-service_remove-issue-label_grpc_timer")
    public Mono<RemoveIssueLabelResponse> removeIssueLabel(Mono<RemoveIssueLabelRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getIssueId(), "body.issueId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getLabelId(), "body.labelId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId")
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "removeIssueLabel")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();
                                    UUID issueId = t.getT3();
                                    UUID labelId = t.getT4();

                                    log.info("[{}][{}] removeIssueLabel: issueId={}, labelId={}",
                                            requestId, nodeId,
                                            issueId,
                                            labelId
                                    );

                                    LabelCommands.RemoveIssueLabelRequestDto requestDto = labelMapper.toRemoveIssueLabelRequestDto(req);

                                    return labelService.removeIssueLabel(requestId, nodeId, requestDto)
                                            .map(labelMapper::toRemoveIssueLabelProtoResponse)
                                            .doOnSuccess(result ->
                                                    log.info("[{}][{}] removeIssueLabel: successfully removed label={}",
                                                            requestId, nodeId, labelId)
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "removeIssueLabel")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_list-issue-labels_grpc_counter",
            timer = "issue-service_list-issue-labels_grpc_timer")
    public Mono<ListIssueLabelsResponse> listIssueLabels(Mono<ListIssueLabelsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                                req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getIssueId(), "body.issueId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                                req.getBody().getActorUserId(), "body.actorUserId")
                                )
                                .doOnError(StatusRuntimeException.class,
                                        logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listIssueLabels")
                                )
                                .flatMap(t -> {
                                    String requestId = t.getT1();
                                    String nodeId = t.getT2();

                                    log.info("[{}][{}] listIssueLabels: issueId={}",
                                            requestId, nodeId,
                                            req.getBody().getIssueId()
                                    );

                                    LabelCommands.ListIssueLabelsRequestDto requestDto = labelMapper.toListIssueLabelsRequestDto(req);

                                    return labelService.listIssueLabels(requestId, nodeId, requestDto)
                                            .map(labelMapper::toListIssueLabelsProtoResponse)
                                            .doOnSuccess(result ->
                                                    log.info("[{}][{}] listIssueLabels: successfully found labels",
                                                            requestId, nodeId)
                                            )
                                            .doOnError(DomainException.class,
                                                    logOnError(requestId, nodeId, "listIssueLabels")
                                            );
                                })
                );
    }

    @TrackMetrics(counter = "issue-service_search-issues_grpc_counter",
            timer = "issue-service_search-issues_grpc_timer")
    public Mono<SearchIssuesResponse> searchIssues(Mono<SearchIssuesRequest> request) {
        return request
                .flatMap(req ->
                        Mono.zip(
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId"),
                                        GrpcRequestValidators.parseOptionalUuidOrInvalidArgument(req.getBody().hasProjectId(), req.getBody().getProjectId(), "body.projectId"),
                                        GrpcRequestValidators.parseOptionalUuidOrInvalidArgument(req.getBody().hasAssigneeId(), req.getBody().getAssigneeId(), "body.assigneeId"),
                                        GrpcRequestValidators.parseOptionalUuidOrInvalidArgument(req.getBody().hasReporterId(), req.getBody().getReporterId(), "body.reporterId"),
                                        GrpcRequestValidators.requireOptionalPositiveZeroOrInvalidArgument(req.getBody().hasPage(), req.getBody().getPage(), "body.page"),
                                        GrpcRequestValidators.requireOptionalPositiveOrInvalidArgument(req.getBody().hasPageSize(), req.getBody().getPageSize(), "body.pageSize")
                                )
                                .zipWith(
                                        Mono.zip(
                                                GrpcRequestValidators.requireOptionalSpecifiedOrInvalidArgument(req.getBody().hasPriority(), req.getBody().getPriority(), "body.priority"),
                                                GrpcRequestValidators.requireOptionalSpecifiedOrInvalidArgument(req.getBody().hasIssueType(), req.getBody().getIssueType(), "body.issueType")
                                        )
                                )
                                .doOnError(
                                        StatusRuntimeException.class,
                                        logValidationError(
                                                req.getHeader().getRequestId(),
                                                req.getHeader().getNodeId(),
                                                "searchIssues"
                                        )
                                )
                                .flatMap(t -> {
                                    var values = t.getT1();
                                    var enums = t.getT2();

                                    String requestId = values.getT1();
                                    String nodeId = values.getT2();
                                    UUID actorUserId = values.getT3();
                                    UUID projectId = values.getT4().orElse(null);
                                    UUID assigneeId = values.getT5().orElse(null);
                                    UUID reporterId = values.getT6().orElse(null);
                                    Integer page = values.getT7().orElse(null);
                                    Integer pageSize = values.getT8().orElse(null);

                                    IssuePriority protoPriority = enums.getT1().orElse(null);
                                    IssueType protoIssueType = enums.getT2().orElse(null);

                                    ru.taska.domain.IssuePriority priority = protoPriority != null
                                            ? issueMapper.toDomainIssuePriority(protoPriority)
                                            : null;

                                    ru.taska.domain.IssueType issueType = protoIssueType != null
                                            ? issueMapper.toDomainIssueType(protoIssueType)
                                            : null;

                                    String query = req.getBody().hasQuery()
                                            ? req.getBody().getQuery()
                                            : null;

                                    String statusKey = req.getBody().hasStatusKey()
                                            ? req.getBody().getStatusKey()
                                            : null;

                                    log.info(
                                            "[{}][{}] searchIssues: query={}, projectId={}, actorUserId={}, " +
                                                    "statusKey={}, assigneeId={}, reporterId={}, priority={}, " +
                                                    "issueType={}, page={}, pageSize={}",
                                            requestId, nodeId,query,projectId,actorUserId,
                                            statusKey,assigneeId,reporterId,priority,
                                            issueType,page,pageSize
                                    );

                                    return issueService.searchIssues(
                                                    requestId,
                                                    nodeId,
                                                    actorUserId,
                                                    query,
                                                    projectId,
                                                    statusKey,
                                                    assigneeId,
                                                    reporterId,
                                                    priority,
                                                    issueType,
                                                    page,
                                                    pageSize
                                            )
                                            .map(result ->
                                                    SearchIssuesResponse.newBuilder()
                                                            .addAllIssues(
                                                                    result.items().stream()
                                                                            .map(issueMapper::toIssueShortProto)
                                                                            .toList()
                                                            )
                                                            .setTotalCount((int) result.totalCount())
                                                            .build()
                                            )
                                            .doOnNext(response ->
                                                    log.info(
                                                            "[{}][{}] searchIssues: successfully found {} issues",
                                                            requestId,
                                                            nodeId,
                                                            response.getTotalCount()
                                                    )
                                            )
                                            .doOnError(
                                                    DomainException.class,
                                                    logOnError(requestId,nodeId,"searchIssues")
                                            );
                                })
                )
                .transform(GrpcExceptionHandler.withErrorHandling("searchIssues"));
    }
    @TrackMetrics(counter = "issue-service_list-issues-board_grpc_counter",
            timer = "issue-service_list-issues-board_grpc_timer")
    public Mono<ListIssuesForBoardResponse> listIssuesForBoard(Mono<ListIssuesForBoardRequest> request){
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"
                        ),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"
                        ),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getProjectId(), "body.projectId"
                        ),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"
                        ),
                                req.getBody().hasAssigneeId()
                                        ? GrpcRequestValidators.parseUuidOrInvalidArgument(req
                                        .getBody().getAssigneeId(), "body.assigneeId").map(Optional::of)
                                        : Mono.just(Optional.<UUID>empty()),
                                validateLabelIds(req.getBody().getLabelIdsList())

                ).doOnError(
                        StatusRuntimeException.class,
                        logValidationError(
                                req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(),
                                "listIssuesForBoard"
                        )
                                )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            UUID actorUserId = t.getT4();
                            UUID assigneeId = t.getT5().orElse(null);
                            String statusKey = req.getBody().hasStatusKey()
                                    ? req.getBody().getStatusKey()
                                    : null;
                            Integer pageSizePerColumn = req.getBody().hasPageSizePerColumn()
                                    ? req.getBody().getPageSizePerColumn()
                                    : null;
                            ru.taska.domain.IssueType issueType = issueMapper.toDomainIssueType(req.getBody().getIssueType());
                            List<UUID> labelIds = t.getT6().isEmpty() ? null : t.getT6();

                            log.info(
                                    "[{}][{}] listIssuesForBoard: projectId={}, actorUserId={}, statusKey={}, " +
                                            "assigneeId={}, issueType={}, includeDone={}, labelIds={}, pageSizePerColumn={}",
                                    requestId, nodeId, projectId, actorUserId, statusKey,
                                    assigneeId, issueType, req.getBody().getIncludeDone(), labelIds, pageSizePerColumn
                            );
                            return issueService.listIssueBoard(
                                    requestId, nodeId,actorUserId, projectId, statusKey, assigneeId,
                                    issueType,
                                    req.getBody().getIncludeDone(),
                                    labelIds,
                                    pageSizePerColumn
                            )
                                    .map(issues -> ListIssuesForBoardResponse.newBuilder()
                                            .addAllIssues(issues)
                                            .build())
                                    .doOnSuccess(response ->
                                            log.info("[{}][{}] listIssuesForBoard: successfully found {} issues",
                                            requestId, nodeId, response.getIssuesCount()))
                                    .doOnError(DomainException.class,
                                            logOnError(
                                                    req.getHeader().getRequestId(),
                                                    req.getHeader().getNodeId(),
                                                    "listIssuesForBoard"
                                            )
                                    );
                        })
                );
    }

    /**
     * Валидирует {@code body.labelIds}: каждый элемент должен быть валидным UUID.
     * Пустой список считается валидным (фильтр по меткам не задан).
     */
    private Mono<List<UUID>> validateLabelIds(List<String> rawLabelIds) {
        return Flux.fromIterable(rawLabelIds)
                .concatMap(raw -> GrpcRequestValidators.parseUuidOrInvalidArgument(raw, "body.labelIds"))
                .collectList();
    }
}
