package ru.taska.service;

import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;

/** Enriches legacy RPC mutation replies without changing their protobuf response type. */
@Component
@RequiredArgsConstructor
public class WorkflowMutationResponse {
    private final WorkflowBindingRepository bindings;
    private final TransitionRepository transitions;
    private final ru.taska.mapper.WorkflowMapper mapper;
    private final ru.taska.transport.grpc.project.GrpcProjectServiceClient projects;

    // Resolve registry order before performing a write, so a failed lookup cannot hide a committed mutation.
    public Mono<java.util.List<ru.taska.api.project.v1.ProjectIssueTypeResponse>> issueTypes(
            ru.taska.api.common.v1.Header header, String projectId, String actorId) {
        return Mono.zip(validator.GrpcRequestValidators.parseUuidOrInvalidArgument(projectId, "projectId"),
                        validator.GrpcRequestValidators.parseUuidOrInvalidArgument(actorId, "actorUserId"))
                .flatMap(ids -> projects.listIssueTypeDetails(header.getRequestId(), header.getNodeId(), ids.getT1(), ids.getT2())
                        .doOnNext(types -> types.forEach(IssueTypeBindingIdentity::generation)));
    }

    public Mono<WorkflowResponse> enrich(WorkflowResponse response, java.util.List<ru.taska.api.project.v1.ProjectIssueTypeResponse> orderedTypes) {
        UUID id = UUID.fromString(response.getId());
        return Mono.zip(bindings.findByWorkflowId(id).collectList(),
                        transitions.findByWorkflowIdOrderBySortOrderAsc(id).collectList())
                .map(data -> {
                    var builder = response.toBuilder().clearStatuses().addAllStatuses(response.getStatusesList().stream()
                            .sorted(Comparator.comparingInt(ru.taska.api.workflow.v1.WorkflowStatus::getSortOrder)).toList())
                            .clearTransitions();
                    data.getT2().forEach(t -> builder.addTransitions(mapper.toWorkflowTransitionProto(t)));
                    var orderedKeys = orderedTypes.stream()
                            .filter(type -> data.getT1().stream().anyMatch(binding -> IssueTypeBindingIdentity.matches(binding, type)))
                            .map(ru.taska.api.project.v1.ProjectIssueTypeResponse::getCode).toList();
                    builder.clearIssueTypes().clearIssueTypeKeys();
                    orderedKeys.forEach(key -> builder.addIssueTypes(switch (key) {
                        case "TASK" -> IssueType.ISSUE_TYPE_TASK;
                        case "BUG" -> IssueType.ISSUE_TYPE_BUG;
                        case "STORY" -> IssueType.ISSUE_TYPE_STORY;
                        default -> IssueType.ISSUE_TYPE_UNSPECIFIED;
                    }));
                    builder.addAllIssueTypeKeys(orderedKeys);
                    return builder.setIsShared(false).build();
                });
    }
}
