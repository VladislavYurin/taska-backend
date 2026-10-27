package ru.taska.domain;

import java.util.Map;
import java.util.UUID;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.IssueTypeCreationIntent;

/** The registry entry remains hidden until its generation binding has a durable APPLIED receipt. */
public record IssueTypeCreation(UUID id,UUID projectId,UUID actor,String requestId,String nodeId,String commandHash,
        ProjectIssueType type,UUID desiredWorkflowId,String firstTypeCode,UUID firstTypeGeneration,UUID resolvedWorkflowId,
        String state,ActionMetadata attribution,String errorStatus,String errorMessage,Map<String,String> errorDetails) {
    public IssueTypeCreationIntent toProto() {
        var out=IssueTypeCreationIntent.newBuilder().setOperationId(id.toString()).setProjectId(projectId.toString())
                .setActorUserId(actor.toString()).setCode(type.code()).setGeneration(type.generation().toString()).setState(state)
                .setFirstTypeCode(firstTypeCode).setFirstTypeGeneration(firstTypeGeneration.toString())
                .setOriginalHeader(action.GrpcActionContext.enrich(Header.newBuilder().setRequestId(requestId).setNodeId(nodeId),attribution));
        if(desiredWorkflowId!=null)out.setWorkflowId(desiredWorkflowId.toString());
        return out.build();
    }
}
