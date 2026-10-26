package ru.taska.service;

import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.entity.TransitionEntity;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;

/** Enriches legacy RPC mutation replies without changing their protobuf response type. */
@Component
@RequiredArgsConstructor
public class WorkflowMutationResponse {
    private final WorkflowBindingRepository bindings;
    private final TransitionRepository transitions;
    private final ru.taska.mapper.WorkflowMapper mapper;

    public Mono<WorkflowResponse> enrich(WorkflowResponse response) {
        UUID id = UUID.fromString(response.getId());
        return Mono.zip(bindings.findByWorkflowId(id).collectList(),
                        transitions.findByWorkflowIdOrderBySortOrderAsc(id).collectList())
                .map(data -> {
                    var builder = response.toBuilder().clearStatuses().addAllStatuses(response.getStatusesList().stream()
                            .sorted(Comparator.comparingInt(ru.taska.api.workflow.v1.WorkflowStatus::getSortOrder)).toList())
                            .clearTransitions();
                    data.getT2().forEach(t -> builder.addTransitions(mapper.toWorkflowTransitionProto(t)));
                    data.getT1().forEach(b -> builder.addIssueTypes(switch (b.getIssueType()) {
                        case "TASK" -> IssueType.ISSUE_TYPE_TASK;
                        case "BUG" -> IssueType.ISSUE_TYPE_BUG;
                        case "STORY" -> IssueType.ISSUE_TYPE_STORY;
                        default -> throw new IllegalStateException("Unsupported issue type: " + b.getIssueType());
                    }));
                    return builder.setIsShared(false).build();
                });
    }
}
