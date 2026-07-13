package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.domain.IssueType;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;

@Component
public class WorkflowMapper {

    public WorkflowResponse toWorkflowProto(WorkflowAggregate aggregate) {
        WorkflowEntity entity = aggregate.workflow();
        return WorkflowResponse.newBuilder()
                .setId(entity.getId().toString())
                .setName(entity.getName())
                .setVersion(entity.getVersion())
                .setCreatedAt(entity.getCreatedAt().toString())
                .setUpdatedAt(entity.getUpdatedAt().toString())
                .addAllStatuses(aggregate.statuses().stream().map(this::toWorkflowStatusProto).toList())
                .addAllTransitions(aggregate.transitions().stream().map(this::toWorkflowTransitionProto).toList())
                .build();
    }

    public IssueType toDomainIssueType(ru.taska.api.workflow.v1.IssueType proto) {
        return switch (proto) {
            case ISSUE_TYPE_TASK -> IssueType.TASK;
            case ISSUE_TYPE_STORY -> IssueType.STORY;
            case ISSUE_TYPE_BUG -> IssueType.BUG;
            default -> null;
        };
    }

    private WorkflowStatus toWorkflowStatusProto(StatusEntity status) {
        return WorkflowStatus.newBuilder()
                .setId(status.getId().toString())
                .setStatusKey(status.getStatusKey())
                .setName(status.getName())
                .setCategory(toProtoStatusCategory(status.getCategory()))
                .setSortOrder(status.getSortOrder())
                .setCreatedAt(status.getCreatedAt().toString())
                .setUpdatedAt(status.getUpdatedAt().toString())
                .build();
    }

    private StatusCategory toProtoStatusCategory(ru.taska.domain.StatusCategory category) {
        if (category == null) return StatusCategory.STATUS_CATEGORY_UNSPECIFIED;
        return switch (category) {
            case TODO -> StatusCategory.STATUS_CATEGORY_TODO;
            case IN_PROGRESS -> StatusCategory.STATUS_CATEGORY_IN_PROGRESS;
            case DONE -> StatusCategory.STATUS_CATEGORY_DONE;
        };
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