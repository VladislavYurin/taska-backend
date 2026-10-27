package ru.taska.domain;

import java.util.Map;
import java.util.UUID;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.IssueTypeDeletionIntent;

/** Immutable command and original attribution, plus the durable coordinator state. */
public record IssueTypeDeletion(UUID id, UUID projectId, String source, UUID sourceGeneration,
    String target, UUID targetGeneration, UUID actor, Map<String,String> mappings, String state,
    String requestId, String nodeId, ActionMetadata attribution, String errorStatus,
    String errorMessage, Map<String,String> errorDetails, boolean reservationReleased) {
  public boolean matches(String requestedTarget, Map<String,String> requestedMappings, UUID expected) {
    return java.util.Objects.equals(target, requestedTarget) && mappings.equals(requestedMappings)
        && (expected == null || sourceGeneration.equals(expected));
  }
  public IssueTypeDeletionIntent toProto() {
    var builder = IssueTypeDeletionIntent.newBuilder().setOperationId(id.toString())
        .setProjectId(projectId.toString()).setSourceCode(source).setSourceGeneration(sourceGeneration.toString())
        .setActorUserId(actor.toString()).putAllStatusMappings(mappings).setState(state)
        .setOriginalHeader(action.GrpcActionContext.enrich(Header.newBuilder()
            .setRequestId(requestId).setNodeId(nodeId), attribution));
    if (target != null) builder.setTargetCode(target).setTargetGeneration(targetGeneration.toString());
    return builder.build();
  }
}
