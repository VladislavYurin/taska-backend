package ru.taska.transport.grpc;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.WorkflowMutationRequest;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.WorkflowMutation;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.service.WorkflowMutationService;
import ru.taska.service.WorkflowMutationService.Operation;
import validator.GrpcRequestValidators;

@Service
@RequiredArgsConstructor
public class WorkflowMutationGrpcService {
    private final WorkflowMutationService service;
    private final WorkflowMapper mapper;
    private final ru.taska.service.WorkflowBindingCoordinator bindings;

    public Mono<ProjectWorkflow> mutate(Operation operation, Mono<WorkflowMutationRequest> request) {
        return request.flatMap(req -> Mono.zip(
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getProjectId(), "projectId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getWorkflowId(), "workflowId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(req.getActorUserId(), "actorUserId"),
                        statusId(operation, req), transitionId(operation, req))
                .map(ids -> new WorkflowMutation(req.getHeader().getRequestId(), req.getHeader().getNodeId(),
                        ids.getT3(), ids.getT1(), ids.getT2(), req.hasVersion() ? req.getVersion() : null,
                        req.hasStatusId() ? ids.getT4() : null, req.hasTransitionId() ? ids.getT5() : null,
                        req.hasName() ? req.getName() : null, category(req),
                        req.hasSortOrder() ? req.getSortOrder() : null, req.hasHidden() ? req.getHidden() : null,
                        req.hasTargetStatusKey() ? req.getTargetStatusKey() : null, req.getStatusMappingsMap()))
                .flatMap(command -> {
                    if (req.getReplaceIssueTypes()) {
                        if (operation!=Operation.UPDATE_WORKFLOW) return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"issueTypes are only valid for updating a workflow"));
                        return bindings.replace(req.getHeader(),command.project(),command.workflow(),command.actor(),command.version(),
                                command.name(),req.getIssueTypeKeysList(),req.getStatusMappingsMap());
                    }
                    if (req.getIssueTypeKeysCount()>0 || (operation==Operation.UPDATE_WORKFLOW&&!req.getStatusMappingsMap().isEmpty()))
                        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,"statusMappings require an issueTypes replacement"));
                    return service.mutate(operation,command).map(mapper::toProjectWorkflowProto);
                })
                .contextWrite(context -> action.GrpcActionContext.install(context, req.getHeader())));
    }

    private Mono<UUID> statusId(Operation op, WorkflowMutationRequest req) {
        return op == Operation.UPDATE_STATUS || op == Operation.DELETE_STATUS
                ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getStatusId(), "statusId")
                : Mono.just(new UUID(0, 0));
    }

    private Mono<UUID> transitionId(Operation op, WorkflowMutationRequest req) {
        return op == Operation.UPDATE_TRANSITION || op == Operation.DELETE_TRANSITION
                ? GrpcRequestValidators.parseUuidOrInvalidArgument(req.getTransitionId(), "transitionId")
                : Mono.just(new UUID(0, 0));
    }

    private StatusCategory category(WorkflowMutationRequest req) {
        if (!req.hasCategory()) return null;
        return switch (req.getCategory()) {
            case STATUS_CATEGORY_TODO -> StatusCategory.TODO;
            case STATUS_CATEGORY_IN_PROGRESS -> StatusCategory.IN_PROGRESS;
            case STATUS_CATEGORY_DONE -> StatusCategory.DONE;
            default -> throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid status category");
        };
    }
}
