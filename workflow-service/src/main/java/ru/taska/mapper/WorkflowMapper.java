package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.workflow.v1.GetWorkflowForProjectResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;

@Component
public class WorkflowMapper {

    public GetWorkflowForProjectResponse toWorkflowProto(WorkflowAggregate aggregate) {
        WorkflowEntity entity = aggregate.workflow();
        return GetWorkflowForProjectResponse.newBuilder()
                .setId(entity.getId().toString())
                .setName(entity.getName())
                .setVersion(entity.getVersion())
                .setCreatedAt(entity.getCreatedAt().toString())
                .setUpdatedAt(entity.getUpdatedAt().toString())
                .addAllStatuses(aggregate.statuses().stream().map(this::toWorkflowStatusProto).toList())
                .addAllTransitions(aggregate.transitions().stream().map(this::toWorkflowTransitionProto).toList())
                .build();
    }

    private WorkflowStatus toWorkflowStatusProto(StatusEntity status) {
        return WorkflowStatus.newBuilder()
                .setId(status.getId().toString())
                .setStatusKey(status.getStatusKey())
                .setName(status.getName())
                .setCategory(status.getCategory())
                .setSortOrder(status.getSortOrder())
                .setCreatedAt(status.getCreatedAt().toString())
                .setUpdatedAt(status.getUpdatedAt().toString())
                .build();
    }

    private WorkflowTransition toWorkflowTransitionProto(TransitionEntity transition) {
        return WorkflowTransition.newBuilder()
                .setId(transition.getId().toString())
                .setFromStatusId(transition.getFromStatusId().toString())
                .setToStatusId(transition.getToStatusId().toString())
                .setName(transition.getName())
                .setSortOrder(transition.getSortOrder())
                .setCreatedAt(transition.getCreatedAt().toString())
                .setUpdatedAt(transition.getUpdatedAt().toString())
                .build();
    }
}