package ru.taska.transport.grpc;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import io.r2dbc.spi.R2dbcException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.Workflow;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.service.WorkflowService;
import validator.GrpcRequestValidators;

import java.util.UUID;


@Slf4j
@Service
@AllArgsConstructor
public class GrpcWorkflowService extends ReactorWorkflowServiceGrpc.WorkflowServiceImplBase {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;

    @Override
    public Mono<Workflow> getWorkflowForProject(Mono<GetWorkflowForProjectRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getIssueType(), "body.issueType")
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();
                    String issueType = t.getT4();

                    log.info("[{}][{}] getWorkflowForProject: projectId={}, issueType={}", requestId, nodeId, projectId, issueType);

                    return workflowService.getWorkflow(projectId, issueType)
                            .doOnNext(w -> log.info("[{}][{}] workflow found: workflowId={}", requestId, nodeId, w.workflow().getId()));
                })
                .map(workflowMapper::toWorkflowProto)
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        _ -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> !(e instanceof io.grpc.StatusRuntimeException),
                        e -> log.error("getWorkflowForProject failed" + e.getMessage()))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }
}