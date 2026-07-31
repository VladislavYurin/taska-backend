package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.issue.v1.AddIssueCommentRequest;
import ru.taska.api.issue.v1.AddIssueCommentResponse;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentRequest;
import ru.taska.api.issue.v1.DeleteIssueCommentResponse;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.ListIssueCommentsRequest;
import ru.taska.api.issue.v1.ListIssueCommentsResponse;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.TransitionIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueCommentRequest;
import ru.taska.api.issue.v1.UpdateIssueCommentResponse;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.exception.DomainException;
import ru.taska.mapper.CommentMapper;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.CommentService;
import ru.taska.service.IssueService;
import ru.taska.service.transition.IssueTransitionProcessor;
import validator.GrpcRequestValidators;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueService {

    private final IssueService issueService;
    private final IssueTransitionProcessor issueTransitionProcessor;
    private final IssueMapper issueMapper;
    private final CommentService commentService;
    private final CommentMapper commentMapper;

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
     * @return        {@link Mono} с ответом {@link IssueWithHistoryResponse}, включающим обновленные данные задачи с историей изменений.
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

    @TrackMetrics(counter = "issue-service_add-issue-comment_grpc_counter",
            timer = "issue-service_add-issue-comment_grpc_timer")
    public Mono<AddIssueCommentResponse> addIssueComment(Mono<AddIssueCommentRequest> request) {
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
                                        req.getBody().getAuthorUserId(), "body.authorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getBody(), "body.body"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "addIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID authorUserId = t.getT4();
                            String body = t.getT5();

                            log.info("[{}][{}] addIssueComment: issueId={}, authorUserId={}",
                                    requestId, nodeId, issueId, authorUserId);

                            return commentService.addComment(requestId, nodeId,issueId, authorUserId, body)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] addIssueComment: successfully added, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "addIssueComment")
                                    );
                        }))
                .map(commentMapper::toAddCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("addIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_update-issue-comment_grpc_counter",
            timer = "issue-service_update-issue-comment_grpc_timer")
    public Mono<UpdateIssueCommentResponse> updateIssueComment(Mono<UpdateIssueCommentRequest> request) {
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
                                        req.getBody().getCommentId(), "body.commentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getBody(), "body.body"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "updateIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID commentId = t.getT4();
                            UUID actorUserId = t.getT5();
                            String body = t.getT6();



                            log.info("[{}][{}] updateIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    requestId, nodeId, issueId, commentId, actorUserId);

                            return commentService.updateComment(requestId, nodeId, issueId, commentId, actorUserId, body)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] updateIssueComment: successfully updated, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "updateIssueComment")
                                    );
                        }))
                .map(commentMapper::toUpdateCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_delete-issue-comment_grpc_counter",
            timer = "issue-service_delete-issue-comment_grpc_timer")
    public Mono<DeleteIssueCommentResponse> deleteIssueComment(Mono<DeleteIssueCommentRequest> request) {
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
                                        req.getBody().getCommentId(), "body.commentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ))
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "deleteIssueComment")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID commentId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] deleteIssueComment: issueId={}, commentId={}, actorUserId={}",
                                    requestId, nodeId, issueId, commentId, actorUserId);

                            return commentService.deleteComment(requestId, nodeId, issueId, commentId, actorUserId)
                                    .doOnSuccess(comment ->
                                            {
                                                assert comment != null;
                                                log.info("[{}][{}] deleteIssueComment: successfully deleted, commentId={}",
                                                        requestId, nodeId, comment.getId());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "deleteIssueComment")
                                    );
                        }))
                .map(commentMapper::toDeleteCommentResponse)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteIssueComment"));
    }

    @TrackMetrics(counter = "issue-service_list-issue-comments_grpc_counter",
            timer = "issue-service_list-issue-comments_grpc_timer")
    public Mono<ListIssueCommentsResponse> listIssueComments(Mono<ListIssueCommentsRequest> request) {
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
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listIssueComments")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            Integer pageSize = req.getBody().hasPageSize()
                                    ? req.getBody().getPageSize()
                                    : null;
                            Integer page = req.getBody().hasPage()
                                    ? req.getBody().getPage()
                                    : null;

                            log.info("[{}][{}] listIssueComments: issueId={}, actorUserId={}, page={}, pageSize={}",
                                    requestId, nodeId, issueId, actorUserId, page, pageSize);

                            return commentService.listComments(requestId, nodeId, issueId, actorUserId, page, pageSize)
                                    .map(result -> ListIssueCommentsResponse.newBuilder()
                                            .addAllComments(
                                                    result.items().stream()
                                                            .map(commentMapper::toCommentProto)
                                                            .toList()
                                            )
                                            .setTotalCount((int) result.totalCount())
                                            .build()
                                    )
                                    .doOnSuccess(result ->
                                            {
                                                assert result != null;
                                                log.info("[{}][{}] listIssueComments: successfully found {} comments",
                                                        requestId, nodeId, result.getTotalCount());
                                            }
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "listIssueComments")
                                    );
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssueComments"));
    }
}
