package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import io.r2dbc.spi.R2dbcException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.IssueStatus;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.TransitionViolation;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.api.workflow.v1.ValidateTransitionResponseBody;
import ru.taska.api.workflow.v1.Workflow;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.StatusMapper;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.service.WorkflowService;
import validator.GrpcRequestValidators;

import java.util.UUID;
import java.util.stream.Collectors;


@Slf4j
@GrpcService
@AllArgsConstructor
public class GrpcWorkflowService extends ReactorWorkflowServiceGrpc.WorkflowServiceImplBase {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final StatusMapper statusMapper;

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
                .transform(GrpcExceptionHandler.withErrorHandling("getWorkflowForProject"));
    }

    @Override
    public Mono<ValidateTransitionResponse> validateTransition(Mono<ValidateTransitionRequest> request) {
        return request
                .flatMap(req -> {
                    // Validate all fields
                    return Mono.zip(
                            objects -> {
                                // Combine all validated values into a single tuple
                                return new Object[]{
                                        objects[0], // requestId
                                        objects[1], // nodeId
                                        objects[2], // transitionId
                                        objects[3], // payload
                                        objects[4], // actorUserId
                                        objects[5], // issueId
                                        objects[6], // projectId
                                        objects[7], // issueType
                                        objects[8]  // statusKey
                                };
                            },
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getTransitionId(), "body.transition_id"),
                            Mono.just(req.getBody().getPayload()),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getActorUserId(), "body.actor_user_id"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueSnapshot().getIssueId(), "body.issue_snapshot.issue_id"),
                            GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueSnapshot().getProjectId(), "body.issue_snapshot.project_id"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getIssueSnapshot().getIssueType(), "body.issue_snapshot.issue_type"),
                            GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getIssueSnapshot().getStatusKey(), "body.issue_snapshot.status_key")
                    );
                })
                .flatMap(values -> {
                    String requestId = (String) values[0];
                    String nodeId = (String) values[1];
                    String transitionId = (String) values[2];
                    String payload = (String) values[3];
                    String actorUserId = (String) values[4];
                    UUID issueId = (UUID) values[5];
                    UUID projectId = (UUID) values[6];
                    String issueType = (String) values[7];
                    String currentStatusKey = (String) values[8];

                    log.info("[{}][{}] validateTransition: projectId={}, issueType={}, transitionId={}, issueId={}, currentStatusKey={}",
                            requestId, nodeId, projectId, issueType, transitionId, issueId, currentStatusKey);

                    return workflowService.validateTransition(
                            requestId,
                            nodeId,
                            projectId,
                            issueType,
                            UUID.fromString(transitionId),
                            currentStatusKey,
                            payload,
                            UUID.fromString(actorUserId)
                    )
                   .map(dto -> Tuples.of(dto, requestId, nodeId));
                })
                .map(tuple  -> {
                    ValidateTransitionResponseDto dto = tuple.getT1();
                    String requestId = tuple.getT2();
                    String nodeId = tuple.getT3();

                    IssueStatus toStatus = dto.getToStatusKey();
                    ValidateTransitionResponseBody.Builder bodyBuilder = ValidateTransitionResponseBody
                            .newBuilder()
                            .setIsValid(dto.isValid())
                            .setToStatusKey(toStatus);
                    Header headerResponse = Header
                            .newBuilder()
                            .setRequestId(requestId)
                            .setNodeId(nodeId)
                            .build();

                    if (dto.getViolations() != null && !dto.getViolations().isEmpty()) {
                        bodyBuilder.addAllTransitionViolations(
                                dto.getViolations().stream()
                                        .map(v -> TransitionViolation.newBuilder()
                                                .setTransitionViolationValue(v.getViolation() != null ? v.getViolation().toString() : "")
                                                .setMessage(v.getMessage() != null ? v.getMessage() : "")
                                                .build())
                                        .collect(Collectors.toList())
                        );
                    }
                    return ValidateTransitionResponse.newBuilder()
                            .setHeader(headerResponse)
                            .setBody(bodyBuilder.build())
                            .build();
                })
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        ex -> new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable"))
                .doOnError(e -> !(e instanceof io.grpc.StatusRuntimeException), e -> log.error("validateTransition failed", e))
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }
}
