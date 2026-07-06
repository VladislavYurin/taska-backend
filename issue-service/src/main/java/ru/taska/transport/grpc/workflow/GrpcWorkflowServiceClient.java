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
import ru.taska.domain.Issue;
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
