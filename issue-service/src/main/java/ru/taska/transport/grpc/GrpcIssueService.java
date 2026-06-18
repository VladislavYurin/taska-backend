package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.*;
import ru.taska.domain.IssueStatus;
import ru.taska.exception.DomainException;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.IssueService;
import validator.GrpcRequestValidators;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcIssueService extends ReactorIssueServiceGrpc.IssueServiceImplBase {

    private final IssueService issueService;
    private final IssueMapper issueMapper;

    @Override
    public Mono<IssueResponse> createIssue(Mono<CreateIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                                GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getIssueType(), "body.issueType"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getSummary(), "body.summary"),
                                GrpcRequestValidators.requireSpecifiedOrInvalidArgument(req.getBody().getPriority(), "body.priority"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getReporterId(), "body.reporterId")
                        ).doOnError(StatusRuntimeException.class, logValidationError(req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(), "createIssue"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            IssueType issueType = t.getT4();
                            String summary = t.getT5();
                            IssuePriority priority = t.getT6();
                            UUID reporterId = t.getT7();

                            String description = req.getBody().getDescription();

                            log.info("[{}][{}] createIssue: projectId={}, issueType={}, summary={}, priority={}, reporterId={}",
                                    requestId, nodeId, projectId, issueType, summary, priority, reporterId);

                            return issueService.createIssue(
                                            projectId,
                                            issueMapper.toDomainIssueType(issueType),
                                            summary,
                                            description,
                                            issueMapper.toDomainIssuePriority(priority),
                                            reporterId
                                    ).doOnNext(issue -> log.info("[{}][{}] createIssue: successfully created, issueId={}",
                                            requestId, nodeId, issue.getId()))
                                    .doOnError(DomainException.class, logOnError(requestId, nodeId, "createIssue"));
                        }))
                .map(issueMapper::toIssueProto)
                .transform(GrpcExceptionHandler.withErrorHandling("createIssue"));
    }

    @Override
    public Mono<IssueDetails> getIssue(Mono<GetIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getIssueId(), "issueId")
                        ).doOnError(StatusRuntimeException.class, logValidationError(req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(), "getIssue"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();

                            log.info("[{}][{}] getIssue: issueId={}", requestId, nodeId, issueId);

                            return issueService.getIssue(issueId)
                                    .doOnSuccess(_ -> log.info("[{}][{}] getIssue: successfully found, issueId={}",
                                            requestId, nodeId, issueId))
                                    .doOnError(DomainException.class, logOnError(requestId, nodeId, "getIssue"));
                        }))
                .map(issueMapper::toIssueDetailsProto)
                .transform(GrpcExceptionHandler.withErrorHandling("getIssue"));
    }

    @Override
    public Mono<ListIssuesResponse> listIssues(Mono<ListIssuesRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(), "projectId"),
                                req.hasAssigneeId()
                                        ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getAssigneeId(), "assigneeId").map(Optional::of)
                                        : Mono.just(Optional.<UUID>empty())
                        ).doOnError(StatusRuntimeException.class, logValidationError(req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(), "listIssues"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            UUID assigneeId = t.getT4().orElse(null);
                            IssueStatus status = req.hasStatus() ? issueMapper.toDomainIssueStatus(req.getStatus()) : null;
                            Integer pageSize = req.hasPageSize() ? req.getPageSize() : null;
                            Integer page = req.hasPage() ? req.getPage() : null;

                            log.info("[{}][{}] listIssues: projectId={}, status={}, assigneeId={}, page={}, pageSize={}",
                                    requestId, nodeId, projectId, status, assigneeId, page, pageSize);

                            return issueService.listIssues(projectId, status, assigneeId, page, pageSize)
                                    .map(result -> ListIssuesResponse.newBuilder()
                                            .addAllIssues(result.items().stream().map(issueMapper::toIssueShortProto).toList())
                                            .setTotalCount((int) result.totalCount())
                                            .build())
                                    .doOnNext(result -> log.info("[{}][{}] listIssues: successfully found {} issues",
                                            requestId, nodeId, result.getTotalCount()))
                                    .doOnError(DomainException.class, logOnError(requestId, nodeId, "listIssues"));
                        }))
                .transform(GrpcExceptionHandler.withErrorHandling("listIssues"));
    }

    @Override
    public Mono<IssueResponse> assignIssue(Mono<AssignIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueId(), "body.issueId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getAssigneeId(), "body.assigneeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId")
                        ).doOnError(StatusRuntimeException.class, logValidationError(req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(), "assignIssue"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID assigneeId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] assignIssue: issueId={}, assigneeId={}, actorUserId={}",
                                    requestId, nodeId, issueId, assigneeId, actorUserId);
                            return issueService.assignIssue(issueId, assigneeId, actorUserId)
                                    .doOnSuccess(_ -> log.info("[{}][{}] assignIssue: successfully assigned, issueId={}",
                                            requestId, nodeId, issueId))
                                    .doOnError(DomainException.class, logOnError(requestId, nodeId, "assignIssue"));
                        }))
                .map(issueMapper::toIssueProto)
                .transform(GrpcExceptionHandler.withErrorHandling("assignIssue"));
    }



    @Override
    public Mono<DeleteIssueResponse> deleteIssue(Mono<DeleteIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueId(), "body.issueId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId")
                        ).doOnError(StatusRuntimeException.class, logValidationError(req.getHeader().getRequestId(),
                                req.getHeader().getNodeId(), "deleteIssue"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] deleteIssue: issueId = {}, actorUserId = {}", requestId, nodeId, issueId, actorUserId);
                            return issueService.deleteIssue(requestId, nodeId, issueId, actorUserId);
                        })
                        .map(issueMapper::toDeleteIssueProto)
                        .transform(GrpcExceptionHandler.withErrorHandling("deleteIssue")));
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
