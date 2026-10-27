package ru.taska.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.taska.api.common.v1.Header;
import ru.taska.api.registry.v1.WorkflowBindingIntent;
import ru.taska.api.registry.v1.WorkflowTypeMove;

/** Immutable preparation and original attribution; the state advances only through receipt decisions. */
public record WorkflowBindingChange(UUID id, UUID project, UUID workflow, UUID actor, String kind,
        String requestId, String nodeId, ActionMetadata attribution, String commandJson, Plan plan,
        String state, String errorStatus, String errorMessage, Map<String,String> errorDetails,
        byte[] result, boolean released) {
    public record Move(String code, UUID generation, UUID sourceWorkflow, UUID targetWorkflow,
                       List<String> targetStatuses) { }
    public record Plan(List<String> desiredTypes, List<Move> moves, Map<String,Integer> versions,
                       Map<String,String> mappings, String desiredName) { }

    public WorkflowBindingIntent intent() {
        var b=WorkflowBindingIntent.newBuilder().setOperationId(id.toString()).setProjectId(project.toString())
                .setWorkflowId(workflow.toString()).setActorUserId(actor.toString()).setState(state)
                .putAllStatusMappings(plan.mappings()).setOriginalHeader(action.GrpcActionContext.enrich(
                        Header.newBuilder().setRequestId(requestId).setNodeId(nodeId), attribution));
        plan.moves().forEach(m->b.addMoves(WorkflowTypeMove.newBuilder().setCode(m.code())
                .setGeneration(m.generation().toString()).setSourceWorkflowId(m.sourceWorkflow().toString())
                .setTargetWorkflowId(m.targetWorkflow().toString()).addAllTargetStatusKeys(m.targetStatuses())));
        return b.build();
    }
}
