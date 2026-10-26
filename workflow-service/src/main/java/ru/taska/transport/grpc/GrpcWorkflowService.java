package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import reactor.util.function.Tuple8;
import reactor.util.function.Tuples;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequest;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequest;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.domain.IssueType;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.TransitionViolation;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.api.workflow.v1.ValidateTransitionResponseBody;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.mapper.WorkflowCreationMapper;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.service.WorkflowService;
import validator.GrpcRequestValidators;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class GrpcWorkflowService{

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final WorkflowCreationMapper workflowCreationMapper;

    @TrackMetrics(counter = "workflow-service_get-Workflow-ForProject_grpc_counter",
                    timer = "workflow-service_get-Workflow-ForProject_grpc_timer")
    public Mono<WorkflowResponse> getWorkflowForProject(Mono<GetWorkflowForProjectRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                        validator.IssueTypeKeys.required(req.getBody().hasIssueTypeKey(), req.getBody().getIssueTypeKey(), req.getBody().getIssueType())
                ))
                .flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID projectId = t.getT3();
                    IssueType issueType = t.getT4();

                    log.info("[{}][{}] getWorkflowForProject: projectId={}, issueType={}", requestId, nodeId, projectId, issueType);

                    return workflowService.getWorkflow(projectId, issueType.name())
                            .doOnNext(w -> log.info("[{}][{}] workflow found: workflowId={}", requestId, nodeId, w.workflow().getId()))
                            .map(workflowMapper::toWorkflowProto)
                            .map(response -> response.toBuilder().addIssueTypeKeys(issueType.name()).build());
                });
    }

    @TrackMetrics(counter = "workflow-service_get-validate-transition_grpc_counter",
            timer = "workflow-service_get-validate-transition_grpc_timer")
    public Mono<ValidateTransitionResponse> validateTransition(Mono<ValidateTransitionRequest> request) {
        return request
                .flatMap(req -> {
                    // Validate all fields
                    return Mono.zip(
                                    GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                    GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                    GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getTransitionId(), "body.transition_id"),
                                    GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getActorUserId(), "body.actor_user_id"),
                                    GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueSnapshot().getIssueId(), "body.issue_snapshot.issue_id"),
                                    GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getIssueSnapshot().getProjectId(), "body.issue_snapshot.project_id"),
                                    validator.IssueTypeKeys.required(req.getBody().getIssueSnapshot().hasIssueTypeKey(), req.getBody().getIssueSnapshot().getIssueTypeKey(), req.getBody().getIssueSnapshot().getIssueType()),
                                    GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getBody().getIssueSnapshot().getStatusKey(), "body.issue_snapshot.status_key")
                            )
                            .map(tuple8 -> Tuples.of(tuple8, req.getBody().getPayload()))
                            .doOnError(StatusRuntimeException.class,
                                    logValidationError(
                                            req.getHeader().getRequestId(),
                                            req.getHeader().getNodeId(),
                                            "validateTransition"
                                    ))
                            .flatMap(t -> {
                                Tuple8<String, String, String, String, UUID, UUID, IssueType, String> values = t.getT1();

                                String requestId = values.getT1();
                                String nodeId = values.getT2();
                                String transitionId = values.getT3();
                                String payload = t.getT2();
                                String actorUserId = values.getT4();
                                UUID issueId = values.getT5();
                                UUID projectId = values.getT6();
                                IssueType issueType = values.getT7();
                                String currentStatusKey = values.getT8();

                                log.info("[{}][{}] validateTransition: projectId={}, issueType={}, transitionId={}, issueId={}, currentStatusKey={}",
                                        requestId, nodeId, projectId, issueType, transitionId, issueId, currentStatusKey);

                                return workflowService.validateTransition(
                                                requestId,
                                                nodeId,
                                                projectId,
                                                issueType.name(),
                                                UUID.fromString(transitionId),
                                                currentStatusKey,
                                                payload,
                                                UUID.fromString(actorUserId)
                                        )
                                        .map(dto -> Tuples.of(dto, requestId, nodeId));
                            })
                            .map(tuple -> {
                                ValidateTransitionResponseDto dto = tuple.getT1();
                                String requestId = tuple.getT2();
                                String nodeId = tuple.getT3();

                                String toStatusKey = dto.getToStatusKey();
                                ValidateTransitionResponseBody.Builder bodyBuilder = ValidateTransitionResponseBody
                                        .newBuilder()
                                        .setIsValid(dto.isValid())
                                        .setToStatusKey(toStatusKey);
                                Header headerResponse = Header
                                        .newBuilder()
                                        .setRequestId(requestId)
                                        .setNodeId(nodeId)
                                        .build();

                                if (dto.getViolations() != null && !dto.getViolations().isEmpty()) {
                                    bodyBuilder.addAllTransitionViolations(
                                            dto.getViolations().stream()
                                                    .map(v -> TransitionViolation.newBuilder()
                                                            .setTransitionViolationValue(v.getTransitionViolation() != null ? v.getTransitionViolation().toString() : "")
                                                            .setMessage(v.getMessage() != null ? v.getMessage() : "")
                                                            .build())
                                                    .collect(Collectors.toList())
                                    );
                                }
                                return ValidateTransitionResponse.newBuilder()
                                        .setHeader(headerResponse)
                                        .setBody(bodyBuilder.build())
                                        .build();
                            });
                });
    }

    @TrackMetrics(counter = "workflow-service_get-create-workflow_grpc_counter",
            timer = "workflow-service_get-create-workflow_grpc_timer")
    public Mono<WorkflowResponse> createWorkflow(Mono<CreateWorkflowRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId"),
                                req.getBody().getIssueTypeKeysCount() > 0
                                        ? Flux.fromIterable(req.getBody().getIssueTypeKeysList())
                                            .concatMap(key -> validator.IssueTypeKeys.required(true, key, ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_UNSPECIFIED)).collectList()
                                        : Flux.fromIterable(req.getBody().getIssueTypesList())
                                            .concatMap(type -> validator.IssueTypeKeys.required(false, "", type)).collectList()
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] createWorkflow: projectId={}, name={}, actorUserId={}", requestId, nodeId, projectId, req.getBody().getName(), actorUserId);

                            WorkflowCreationDto dto = workflowCreationMapper.toDomainDto(projectId, req.getBody());
                            return workflowService.createWorkflow(requestId, nodeId, actorUserId, dto);
                        }))
                .map(workflowMapper::toWorkflowProto);
    }

    @TrackMetrics(counter = "workflow-service_add-workflow-status_grpc_counter",
            timer = "workflow-service_add-workflow-status_grpc_timer")
    public Mono<WorkflowResponse> addWorkflowStatus(Mono<AddWorkflowStatusRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getWorkflowId(), "body.workflowId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId")
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "addWorkflowStatus"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            UUID workflowId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] addWorkflowStatus: projectId={}, workflowId={}, statusKey={}, actorUserId={}",
                                    requestId, nodeId, projectId, workflowId, req.getBody().getStatus().getStatusKey(), actorUserId);

                            return workflowService.addStatus(requestId, nodeId, actorUserId, projectId, workflowId,
                                    workflowCreationMapper.toStatusDto(req.getBody().getStatus()));
                        }))
                .map(workflowMapper::toWorkflowProto);
    }

    @TrackMetrics(counter = "workflow-service_add-workflow-transition_grpc_counter",
            timer = "workflow-service_add-workflow-transition_grpc_timer")
    public Mono<WorkflowResponse> addWorkflowTransition(Mono<AddWorkflowTransitionRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getWorkflowId(), "body.workflowId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId")
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "addWorkflowTransition"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID projectId = t.getT3();
                            UUID workflowId = t.getT4();
                            UUID actorUserId = t.getT5();

                            log.info("[{}][{}] addWorkflowTransition: projectId={}, workflowId={}, from={}, to={}, actorUserId={}",
                                    requestId, nodeId, projectId, workflowId,
                                    req.getBody().getTransition().getFromStatusKey(),
                                    req.getBody().getTransition().getToStatusKey(), actorUserId);

                            return workflowService.addTransition(requestId, nodeId, actorUserId, projectId, workflowId,
                                    workflowCreationMapper.toTransitionDto(req.getBody().getTransition()));
                        }))
                .map(workflowMapper::toWorkflowProto);
    }

    @TrackMetrics(counter = "workflow-service_list-project-workflows_grpc_counter",
            timer = "workflow-service_list-project-workflows_grpc_timer")
    public Mono<ListProjectWorkflowsResponse> listProjectWorkflows(Mono<ListProjectWorkflowsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getRequestId(), "header.requestId"),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(req.getHeader().getNodeId(), "header.nodeId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getProjectId(), "body.projectId"),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(req.getBody().getActorUserId(), "body.actorUserId")
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listProjectWorkflows"))
                        .flatMap(t -> {
                            log.info("[{}][{}] listProjectWorkflows: projectId={}", t.getT1(), t.getT2(), t.getT3());
                            return workflowService.listProjectWorkflows(t.getT1(), t.getT2(), t.getT3(), t.getT4());
                        }))
                .map(workflowMapper::toListProjectWorkflowsProto);
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

}