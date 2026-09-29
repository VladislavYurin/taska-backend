package ru.taska.transport.grpc.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.mapper.projectContextMapper.WorkflowProtoMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcWorkFlowServiceClient {
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub;
    private final GrpcClientProperties properties;
    private final WorkflowProtoMapper workflowProtoMapper;

    /**
     * Возвращает workflow проекта для конкретного типа задачи в виде доменного DTO.
     */
    public Mono<ProjectWorkflowDto> getWorkflowForProject(
            UUID projectId,
            String issueType,
            UUID actorUserId,
            String requestId,
            String nodeId
    ) {
        return dynamicStub().getWorkflowForProject(
                GetWorkflowForProjectRequest.newBuilder()
                        .setHeader(Header.newBuilder()
                                .setRequestId(requestId)
                                .setNodeId(nodeId)
                                .build())
                        .setBody(GetWorkflowForProjectRequestBody.newBuilder()
                                .setProjectId(projectId.toString())
                                .setIssueType(toGrpcIssueType(issueType))
                                .setActorUserId(actorUserId.toString())
                                .build())
                        .build()
        ).map(proto -> workflowProtoMapper.toProjectWorkflowDto(issueType, proto));
    }

    private IssueType toGrpcIssueType(String issueType) {
        if (issueType == null) {
            return IssueType.ISSUE_TYPE_UNSPECIFIED;
        }
        return switch (issueType) {
            case "TASK"  -> IssueType.ISSUE_TYPE_TASK;
            case "BUG"   -> IssueType.ISSUE_TYPE_BUG;
            case "STORY" -> IssueType.ISSUE_TYPE_STORY;
            default -> IssueType.ISSUE_TYPE_UNSPECIFIED;
        };
    }

    private ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub dynamicStub() {
        return workflowServiceStub
                .withDeadlineAfter(properties.workFlowService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
}
