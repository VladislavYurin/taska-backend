package ru.taska.transport.grpc.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionRequestBody;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.domain.entity.Issue;
import ru.taska.mapper.IssueMapper;

import java.util.UUID;

/**
 * gRPC клиент для отправки запросов в workflow-service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcWorkflowServiceClient {

    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub reactorWorkflowServiceStub;
    private final IssueMapper issueMapper;

    public Mono<Void> requireStatusForType(String requestId, String nodeId, Issue issue, UUID actorUserId) {
        return reactorWorkflowServiceStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .getWorkflowForProject(ru.taska.api.workflow.v1.GetWorkflowForProjectRequest.newBuilder()
                        .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId))
                        .setBody(ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody.newBuilder()
                                .setProjectId(issue.getProjectId().toString()).setActorUserId(actorUserId.toString())
                                .setIssueType(issueMapper.toIssueValidateSnapshotProto(issue).getIssueType())
                                .setIssueTypeKey(issue.getIssueType().name())).build())
                .flatMap(workflow -> workflow.getStatusesList().stream().anyMatch(s -> s.getStatusKey().equals(issue.getStatusKey()))
                        ? Mono.empty() : Mono.error(new ru.taska.exception.DomainException(
                                ru.taska.exception.DomainStatus.FAILED_PRECONDITION,
                                "Current status is not available for the target issue type",
                                java.util.Map.of("reason", "STATUS_NOT_IN_TARGET_WORKFLOW"))));
    }

    public Mono<Void> requireInitialStatus(String requestId, String nodeId, UUID projectId,
            ru.taska.domain.IssueType issueType, UUID actorUserId, String statusKey) {
        return reactorWorkflowServiceStub.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .getWorkflowForProject(ru.taska.api.workflow.v1.GetWorkflowForProjectRequest.newBuilder()
                        .setHeader(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId))
                        .setBody(ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody.newBuilder()
                                .setProjectId(projectId.toString()).setActorUserId(actorUserId.toString())
                                .setIssueType(issueMapper.toWorkflowProtoIssueType(issueType))
                                .setIssueTypeKey(issueType.name())).build())
                .flatMap(workflow -> workflow.getStatusesList().stream().anyMatch(s -> s.getStatusKey().equals(statusKey))
                        ? Mono.empty() : Mono.error(new ru.taska.exception.DomainException(
                                ru.taska.exception.DomainStatus.FAILED_PRECONDITION,
                                "Initial status is not available for the issue type",
                                java.util.Map.of("reason", "STATUS_NOT_IN_WORKFLOW"))));
    }

    /**
     * Отправляет запрос {@link ValidateTransitionRequest} в workflow-service для валидации перехода по workflow.
     */
    public Mono<ValidateTransitionResponse> validateTransition(
            String requestId,
            String nodeId,
            Issue issue,
            UUID transitionId,
            UUID actorUserId,
            String payload
    ) {

        log.info("[{}][{}] Calling validateTransition with: issueId={}, projectId={}, transitionId={}, actorUserId={}, currentStatusKey={}",
                requestId, nodeId, issue.getId(), issue.getProjectId(), transitionId, actorUserId, issue.getStatusKey());

        var request = ValidateTransitionRequest.newBuilder()
                .setHeader(
                        Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build()
                )
                .setBody(
                        ValidateTransitionRequestBody.newBuilder()
                                .setIssueSnapshot(issueMapper.toIssueValidateSnapshotProto(issue))
                                .setTransitionId(transitionId.toString())
                                .setActorUserId(actorUserId.toString())
                                .setPayload(payload)
                                .build()
                )
                .build();

        return reactorWorkflowServiceStub.validateTransition(request);
    }
}
