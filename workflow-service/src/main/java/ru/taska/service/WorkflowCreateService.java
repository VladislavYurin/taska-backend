package ru.taska.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.ActionMetadata;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.mapper.WorkflowMapper;

/** Legacy in-process callers use the same recoverable binding coordinator as the REST API. */
@Service
@RequiredArgsConstructor
public class WorkflowCreateService {
    private final WorkflowBindingCoordinator bindings;
    private final WorkflowMapper mapper;
    public Mono<WorkflowAggregate> validateAndCreateWorkflow(String requestId,String nodeId,UUID actor,WorkflowCreationDto dto) {
        return Mono.deferContextual(context -> bindings.create(action.GrpcActionContext.enrich(
                        Header.newBuilder().setRequestId(requestId).setNodeId(nodeId),ActionMetadata.current(context)).build(),actor,dto)
                .map(result -> mapper.fromWorkflowProto(result.getWorkflow(),dto.getProjectId())));
    }
}
