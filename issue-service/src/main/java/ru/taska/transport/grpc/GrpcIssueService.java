package ru.taska.transport.grpc;

import exception.DomainException;
import exception.DomainStatus;
import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.CreateIssueRequest;
import ru.taska.api.issue.v1.GetIssueRequest;
import ru.taska.api.issue.v1.IssueDetails;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.ListIssuesRequest;
import ru.taska.api.issue.v1.ListIssuesResponse;
import ru.taska.api.issue.v1.ReactorIssueServiceGrpc;
import ru.taska.domain.IssueStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.service.IssueService;
import validator.GrpcRequestValidators;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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
                ).flatMap(t -> {
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
                    );
                }))
                .map(issueMapper::toIssueProto)
                .transform(withErrorHandling("createIssue"));
    }

    @Override
    public Mono<IssueDetails> getIssue(Mono<GetIssueRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getIssueId(), "issueId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID issueId = t.getT3();

                    log.info("[{}][{}] getIssue: issueId={}", requestId, nodeId, issueId);

                    return issueService.getIssue(issueId);
                }))
                .map(issueMapper::toIssueDetailsProto)
                .transform(withErrorHandling("getIssue"));
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
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();
                    UUID assigneeId = t.getT4().orElse(null);
                    IssueStatus status = req.hasStatus() ? issueMapper.toDomainIssueStatus(req.getStatus()) : null;

                    log.info("[{}][{}] listIssues: projectId={}, status={}, assigneeId={}",
                            requestId, nodeId, projectId, status, assigneeId);

                    return issueService.listIssues(projectId, status, assigneeId)
                            .map(issueMapper::toIssueShortProto)
                            .collectList()
                            .map(issues -> ListIssuesResponse.newBuilder().addAllIssues(issues).build());
                }))
                .transform(withErrorHandling("listIssues"));
    }

    private <T> Function<Mono<T>, Mono<T>> withErrorHandling(String operationName) {
        return mono -> mono
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        e -> {
                            log.error("{}: database error", operationName, e);
                            return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
                        })
                .onErrorMap(e -> !(e instanceof DomainException) && !(e instanceof StatusRuntimeException),
                        e -> {
                            log.error("{}: unexpected error", operationName, e);
                            return new DomainException(DomainStatus.INTERNAL, "Internal error");
                        })
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }
}
