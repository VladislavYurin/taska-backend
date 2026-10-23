package ru.taska.transport.grpc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowMutationRequest;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ProjectWorkflowDto;
import ru.taska.domain.dto.WorkflowPatchBody;
import ru.taska.mapper.WorkflowMapper;

@Component
@RequiredArgsConstructor
public class GrpcWorkflowMutationClient {
    public enum Operation { UPDATE_WORKFLOW, UPDATE_STATUS, DELETE_STATUS, UPDATE_TRANSITION, DELETE_TRANSITION, DELETE_WORKFLOW }
    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub stub;
    private final GrpcClientProperties properties;
    private final WorkflowMapper mapper;

    public Mono<ProjectWorkflowDto> mutate(Operation op, UUID project, UUID workflow, UUID status, UUID transition,
                                           WorkflowPatchBody body, Integer version, String target, GatewayContext context) {
        var request = WorkflowMutationRequest.newBuilder().setProjectId(project.toString()).setWorkflowId(workflow.toString())
                .setActorUserId(context.userContext().userId()).setHeader(Header.newBuilder()
                        .setRequestId(context.requestId()).setNodeId(context.nodeId()));
        if (status != null) request.setStatusId(status.toString());
        if (transition != null) request.setTransitionId(transition.toString());
        if (version != null) request.setVersion(version);
        if (target != null) request.setTargetStatusKey(target);
        if (body != null) {
            if (body.getVersion() != null) request.setVersion(body.getVersion());
            if (body.getName() != null) request.setName(body.getName());
            if (body.getCategory() != null) request.setCategory(StatusCategory.valueOf("STATUS_CATEGORY_" + body.getCategory().getValue()));
            if (body.getSortOrder() != null) request.setSortOrder(body.getSortOrder());
            if (body.getHidden() != null) request.setHidden(body.getHidden());
            request.putAllStatusMappings(body.getStatusMappings());
        }
        var rpc = stub.withDeadlineAfter(properties.workflowService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
        var built = request.build();
        return (switch (op) {
            case UPDATE_WORKFLOW -> rpc.updateWorkflow(built);
            case UPDATE_STATUS -> rpc.updateWorkflowStatus(built);
            case DELETE_STATUS -> rpc.deleteWorkflowStatus(built);
            case UPDATE_TRANSITION -> rpc.updateWorkflowTransition(built);
            case DELETE_TRANSITION -> rpc.deleteWorkflowTransition(built);
            case DELETE_WORKFLOW -> rpc.deleteProjectWorkflow(built);
        }).map(mapper::toProjectWorkflowDto);
    }
}
