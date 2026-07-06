package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AssignIssueRequest;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueRequest;
import ru.taska.api.issue.v1.DeleteIssueResponse;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.api.issue.v1.UpdateIssueRequest;
import ru.taska.api.issue.v1.UpdateIssueResponse;
import ru.taska.domain.IssueStatus;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.IssueService;
import validator.GrpcRequestValidators;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class GrpcIssueService extends ReactorIssueServiceGrpc.IssueServiceImplBase {

    private final IssueService issueService;
    private final IssueMapper issueMapper;

    @Override
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
                .map(issueMapper::toIssueProto)
                .transform(GrpcExceptionHandler.withErrorHandling("createIssue"));
    }

    @Override
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
                                        req.getBody().getProjectId(), "body.projectId"
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
                            UUID projectId = t.getT3();
                            UUID issueId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] getIssue: projectId={}, issueId={}, actorUserId={}",
                                    requestId, nodeId, projectId, issueId, actorUserId);

                            return issueService.getIssue(
                                            requestId,
                                            nodeId,
                                            projectId,
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
                .map(issueMapper::toIssueDetailsProto)
                .transform(GrpcExceptionHandler.withErrorHandling("getIssue"));
    }

    @Override
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
                            IssueStatus status = req.getBody().hasStatus()
                                    ? issueMapper.toDomainIssueStatus(req.getBody().getStatus())
                                    : null;
                            Integer pageSize = req.getBody().hasPageSize()
                                    ? req.getBody().getPageSize()
                                    : null;
                            Integer page = req.getBody().hasPage()
                                    ? req.getBody().getPage()
                                    : null;

                            log.info("[{}][{}] listIssues: projectId={}, actorUserId={}, status={}, assigneeId={}, " +
                                            "page={}, pageSize={}",
                                    requestId, nodeId, projectId, actorUserId, status, assigneeId, page, pageSize);

                            return issueService.listIssues(
                                            requestId,
                                            nodeId,
                                            projectId,
                                            actorUserId,
                                            status,
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
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssues"));
    }

    @Override
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
                                        req.getBody().getProjectId(), "body.projectId"
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
                            UUID projectId = t.getT3();
                            UUID issueId = t.getT4();
                            UUID assigneeId = t.getT5();
                            UUID actorUserId = t.getT6();

                            log.info("[{}][{}] assignIssue: projectId={}, issueId={}, assigneeId={}, actorUserId={}",
                                    requestId, nodeId, projectId, issueId, assigneeId, actorUserId);

                            return issueService.assignIssue(
                                            requestId,
                                            nodeId,
                                            projectId,
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
                .map(issueMapper::toIssueProto)
                .transform(GrpcExceptionHandler.withErrorHandling("assignIssue"));
    }


    /**
     * Удаляет задачу на основе Mono<{@link ru.taska.api.issue.v1.DeleteIssueRequest}>
     *
     * @param request .proto с айди задачи и инициатором удаления
     * @return Mono<{@link DeleteIssueResponse}> с соответствующими параметрами созданного проекта
     */
    @Override
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
                                        req.getBody().getProjectId(), "body.projectId"
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
                            UUID projectId = t.getT3();
                            UUID issueId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] deleteIssue: projectId={}, issueId = {}, actorUserId = {}",
                                    requestId, nodeId, projectId, issueId, actorUserId);

                            return issueService.deleteIssue(
                                    requestId,
                                    nodeId,
                                    projectId,
                                    issueId,
                                    actorUserId
                            );
                        })
                        .map(issueMapper::toDeleteIssueProto)
                        .transform(GrpcExceptionHandler.withErrorHandling("deleteIssue")));
    }

    /**
     * Обновляет задачу на основе Mono<{@link ru.taska.api.issue.v1.UpdateIssueRequest}>
     *
     * @param request .proto с параметрами на обновление задачи
     * @return Mono<{@link UpdateIssueResponse}> с соответствующими параметрами созданного проекта
     */
    @Override
    public Mono<UpdateIssueResponse> updateIssue(Mono<UpdateIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueId(), "body.issueId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getSummary(), "body.summary"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getDescription(), "body.description"),
                        GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getPriority(), "body.priority")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();
                    UUID issueId = t.getT4();
                    UUID actorUserId = t.getT5();
                    String summary = t.getT6();
                    String description = t.getT7();
                    ru.taska.domain.IssuePriority priority = issueMapper.toDomainIssuePriority(t.getT8());

                    log.info("[{}][{}] updateIssue: projectId = {}, issueId = {}, actorUserId = {}, summary = {}, description = {}, priority = {}",
                            requestId, nodeId, issueId, actorUserId, summary, description, priority);
                    return issueService.updateIssue(requestId, nodeId, projectId, issueId, actorUserId, summary, description, priority);
                })
                .map(issueMapper::toUpdateIssueProto)
                .transform(GrpcExceptionHandler.withErrorHandling("updateIssue"));
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
