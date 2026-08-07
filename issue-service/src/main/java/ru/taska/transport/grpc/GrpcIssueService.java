package ru.taska.transport.grpc;

import com.google.protobuf.DoubleValue;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.IssueType;
import ru.taska.domain.UpdateField;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.IssueService;
import ru.taska.service.transition.IssueTransitionProcessor;
import validator.GrpcRequestValidators;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueService {

    private final IssueService issueService;
    private final IssueTransitionProcessor issueTransitionProcessor;
    private final IssueMapper issueMapper;

    @TrackMetrics(counter = "issue-service_create-issue_grpc_counter",
            timer = "issue-service_create-issue_grpc_timer")
    public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> request) {
        return request
                .flatMap(req -> {
                    var body = req.getBody();
                    var header = req.getHeader();

                    var traceId = Mono.zip(
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                    header.getRequestId(), "header.requestId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                    header.getNodeId(), "header.nodeId")
                    );

                    var coreIssueMono = Mono.zip(
                            GrpcRequestValidators.validateIdempotencyKey(
                                    body.getIdempotencyKey(), "body.idempotencyKey"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(
                                    body.getProjectId(), "body.projectId"),
                            GrpcRequestValidators.requireSpecifiedOrInvalidArgument(
                                    body.getIssueType(), "body.issueType"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                    body.getSummary(), "body.summary"),
                            GrpcRequestValidators.requireSpecifiedOrInvalidArgument(
                                    body.getPriority(), "body.priority"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(
                                    body.getReporterId(), "body.reporterId"),
                            GrpcRequestValidators.validateAnyOptional(
                                    body.hasDescription(), body.getDescription().getValue(), "body.description"),
                            GrpcRequestValidators.validateAnyOptional(
                                    body.hasAssigneeId(), body.getAssigneeId().getValue(), "body.assigneeId")
                    );

                    var planningFields = Mono.zip(
                            GrpcRequestValidators.validateOptionalNumbers(
                                    body.hasStoryPoints(), body.getStoryPoints().getValue(), "body.story_points"),
                            GrpcRequestValidators.parseOptionalInstant(
                                    body.hasStartDate(), body.getStartDate(), "body.startDate"),
                            GrpcRequestValidators.parseOptionalInstant(
                                    body.hasDueDate(), body.getDueDate(), "body.dueDate"),
                            GrpcRequestValidators.validateOptionalNumbers(
                                    body.hasOriginalEstimateMinutes(), body.getOriginalEstimateMinutes().getValue(), "body.originalEstimateMinutes")
                    );

                    return Mono.zip(traceId, coreIssueMono, planningFields)
                            .doOnError(StatusRuntimeException.class, logValidationError(
                                    req.getHeader().getRequestId(), req.getHeader().getNodeId(), "createIssue"
                            ))
                            .flatMap(t -> {
                                // Trace id
                                String requestId = t.getT1().getT1();
                                String nodeId = t.getT1().getT2();

                                // Основные данные по задаче
                                String idempotencyKey = t.getT2().getT1();
                                UUID projectId = t.getT2().getT2();
                                IssueType issueType = issueMapper.toDomainIssueType(t.getT2().getT3());
                                String summary = t.getT2().getT4();
                                ru.taska.domain.IssuePriority priority = issueMapper.toDomainIssuePriority(t.getT2().getT5());
                                UUID reporterId = t.getT2().getT6();

                                // Извлекаем чистые значения из Optional (если пусто — будет null)
                                String description = t.getT2().getT7().orElse(null);

                                String assigneeStr = t.getT2().getT8().orElse(null);
                                UUID assigneeId = (assigneeStr != null && !assigneeStr.isBlank())
                                        ? UUID.fromString(assigneeStr)
                                        : null;

                                // Поля планировщика задачи
                                Double storyPoints = t.getT3().getT1().orElse(null);

                                Instant startDate = t.getT3().getT2().filter(i -> !i.equals(Instant.MIN)).orElse(null);
                                Instant dueDate = t.getT3().getT3().filter(i -> !i.equals(Instant.MIN)).orElse(null);

                                Long originalEstimateMinutes = t.getT3().getT4().orElse(null);

                                log.info("[{}][{}] createIssue: idempotencyKey={}, projectId={}, issueType={}, summary={}, description={}," +
                                                " assigneeId={}, priority={}, reporterId={}, storyPoints={}, startDate={}, dueDate={}, originalEstimateMinutes={}",
                                        requestId, nodeId, idempotencyKey, projectId, issueType, summary, description, assigneeId, priority, reporterId,
                                        storyPoints, startDate, dueDate, originalEstimateMinutes);

                                return issueService.createIssue(requestId, nodeId, idempotencyKey, projectId,
                                                issueType, summary, description,
                                                assigneeId, priority, reporterId,
                                                storyPoints, startDate, dueDate, originalEstimateMinutes)
                                        .doOnNext(issue ->
                                                log.info("[{}][{}] createIssue: successfully created, issueId={}",
                                                        requestId, nodeId, issue.getId())
                                        )
                                        .doOnError(DomainException.class,
                                                logOnError(requestId, nodeId, "createIssue")
                                        );
                            })
                            .map(issueMapper::toIssueProto);
                });
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
                                    .doOnSuccess(e ->
                                            log.info("[{}][{}] getIssue: successfully found, issueId={}, actorUserId={}",
                                                    requestId, nodeId, issueId, actorUserId)
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "getIssue")
                                    );
                        }))
                .map(issueMapper::toIssueDetailsProto);
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
                            String statusKey = req.getBody().hasStatusKey()
                                    ? req.getBody().getStatusKey()
                                    : null;
                            Integer pageSize = req.getBody().hasPageSize()
                                    ? req.getBody().getPageSize()
                                    : null;
                            Integer page = req.getBody().hasPage()
                                    ? req.getBody().getPage()
                                    : null;

                            log.info("[{}][{}] listIssues: projectId={}, actorUserId={}, status={}, assigneeId={}, " +
                                            "page={}, pageSize={}",
                                    requestId, nodeId, projectId, actorUserId, statusKey, assigneeId, page, pageSize);

                            return issueService.listIssues(
                                            requestId,
                                            nodeId,
                                            projectId,
                                            actorUserId,
                                            statusKey,
                                            assigneeId,
                                            page,
                                            pageSize
                                    )
                                    .map(result -> ListIssuesResponse.newBuilder()
                                            .addAllIssues(
                                                    result.items().stream()
                                                            .map(issueMapper::toIssueShortProto)
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
                        }));
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
     * Обновляет задачу на основе Mono<{@link ru.taska.api.issue.v1.UpdateIssueRequest}>
     *
     * @param request .proto с параметрами на обновление задачи
     * @return Mono<{@link UpdateIssueResponse}> с соответствующими параметрами созданного проекта
     */
    @TrackMetrics(counter = "issue-service_update-issue_grpc_counter",
            timer = "issue-service_update-issue_grpc_timer")
    public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> request) {
        return request
                .flatMap(req -> {
                    var body = req.getBody();
                    var header = req.getHeader();

                    var specificationFields = Mono.zip(
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getRequestId(), "header.requestId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getNodeId(), "header.nodeId"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(body.getIssueId(), "body.issueId"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(body.getActorUserId(), "body.actorUserId")
                    );

                    var planningFields = Mono.zip(
                            GrpcRequestValidators.validateOptionalWrapper(
                                    body.hasSummary(), body.getSummary(), StringValue::getValue, "body.summary"),
                            GrpcRequestValidators.validateOptionalWrapper(
                                    body.hasDescription(), body.getDescription(), StringValue::getValue, "body.description"),
                            GrpcRequestValidators.parseOptionalEnum(
                                    body.hasPriority(), body.getPriority(), IssuePriority.ISSUE_PRIORITY_UNSPECIFIED, "body.priority"),
                            GrpcRequestValidators.validateOptionalWrapper(
                                    body.hasStoryPoints(), body.getStoryPoints(), DoubleValue::getValue, "body.story_points"),
                            GrpcRequestValidators.parseOptionalTimestamp(body.hasStartDate(), body.getStartDate(), "body.startDate"),
                            GrpcRequestValidators.parseOptionalTimestamp(body.hasDueDate(), body.getDueDate(), "body.dueDate"),
                            GrpcRequestValidators.validateOptionalWrapper(
                                    body.hasOriginalEstimateMinutes(), body.getOriginalEstimateMinutes(), Int64Value::getValue, "body.originalEstimateMinutes"),
                            GrpcRequestValidators.validateOptionalWrapper(
                                    body.hasRemainingEstimateMinutes(), body.getRemainingEstimateMinutes(), Int64Value::getValue, "body.remainingEstimateMinutes")
                    );

                    return Mono.zip(specificationFields, planningFields)
                            .doOnError(io.grpc.StatusRuntimeException.class, logValidationError(
                                    header.getRequestId(), header.getNodeId(), "updateIssue"
                            ))
                            .flatMap(t -> {
                                String requestId = t.getT1().getT1();
                                String nodeId = t.getT1().getT2();
                                UUID issueId = t.getT1().getT3();
                                UUID actorUserId = t.getT1().getT4();

                                UpdateField<String> summary = t.getT2().getT1();
                                UpdateField<String> description = t.getT2().getT2();

                                UpdateField<IssuePriority> grpcPriority = t.getT2().getT3();
                                UpdateField<ru.taska.domain.IssuePriority> priority;

                                if (!grpcPriority.isPresent()) {
                                    priority = UpdateField.undefined();
                                } else if (grpcPriority.value() == null) {
                                    priority = UpdateField.of(null);
                                } else {
                                    priority = UpdateField.of(issueMapper.toDomainIssuePriority(grpcPriority.value()));
                                }

                                UpdateField<Double> storyPoints = t.getT2().getT4();
                                UpdateField<java.time.Instant> startDate = t.getT2().getT5();
                                UpdateField<java.time.Instant> dueDate = t.getT2().getT6();
                                UpdateField<Long> originalEstimateMinutes = t.getT2().getT7();
                                UpdateField<Long> remainingEstimateMinutes = t.getT2().getT8();

                                log.info("[{}][{}] updateIssue: issueId = {}, actorUserId = {}, summaryPresent = {}, descriptionPresent = {}, priorityPresent = {}",
                                        requestId, nodeId, issueId, actorUserId, summary.isPresent(), description.isPresent(), priority.isPresent());

                                return issueService.updateIssue(
                                        requestId, nodeId, issueId, actorUserId,
                                        summary, description, priority, storyPoints,
                                        startDate, dueDate, originalEstimateMinutes, remainingEstimateMinutes
                                ).map(issueMapper::toUpdateIssueProto);
                            });
                });
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
                                logValidationError(
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

                            return issueTransitionProcessor.transitionIssue(
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

    private Consumer<Throwable> logValidationError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof StatusRuntimeException e
                    && e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                log.error("[{}][{}] {} validation error: {}",
                        requestId, nodeId, operation, e.getStatus().getDescription());
            }
        };
    }

    private Consumer<Throwable> logOnError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof DomainException e) {
                log.error("[{}][{}] {} failed: status={}, message={}",
                        requestId, nodeId, operation, e.getStatus(), e.getMessage());
            }
        };
    }
}
