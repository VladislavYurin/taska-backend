package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.workflow.v1.CreateWorkflowRequestBody;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.WorkflowStatusCreateRequest;
import ru.taska.api.workflow.v1.WorkflowTransitionCreateRequest;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowBindingEntity;

import java.util.Map;
import java.util.UUID;

@Component
public class WorkflowCreationMapper {

    public WorkflowCreationDto toDomainDto(UUID projectId, CreateWorkflowRequestBody body) {
        return WorkflowCreationDto.builder()
                .projectId(projectId)
                .name(body.getName().trim())
                .issueTypes(body.getIssueTypeKeysCount() > 0
                        ? body.getIssueTypeKeysList().stream().map(key -> validator.IssueTypeKeys.read(true, key, IssueType.ISSUE_TYPE_UNSPECIFIED)).toList()
                        : body.getIssueTypesList().stream().map(this::toDomainIssueType).toList())
                .statuses(body.getStatusesList().stream().map(this::toStatusDto).toList())
                .transitions(body.getTransitionsList().stream().map(this::toTransitionDto).toList())
                .build();
    }

    private ru.taska.domain.IssueType toDomainIssueType(IssueType proto) {
        return switch (proto) {
            case ISSUE_TYPE_TASK -> ru.taska.domain.IssueType.TASK;
            case ISSUE_TYPE_BUG -> ru.taska.domain.IssueType.BUG;
            case ISSUE_TYPE_STORY -> ru.taska.domain.IssueType.STORY;
            default -> null;
        };
    }

    public CreateWorkflowStatusDto toStatusDto(WorkflowStatusCreateRequest proto) {
        return CreateWorkflowStatusDto.builder()
                .statusKey(proto.getStatusKey().trim())
                .name(proto.getName().trim())
                .category(toDomainCategory(proto.getCategory()))
                .sortOrder(proto.getSortOrder())
                .build();
    }

    public CreateWorkflowTransitionDto toTransitionDto(WorkflowTransitionCreateRequest proto) {
        return CreateWorkflowTransitionDto.builder()
                .fromStatusKey(proto.getFromStatusKey())
                .toStatusKey(proto.getToStatusKey())
                .name(proto.getName().trim())
                .sortOrder(proto.getSortOrder())
                .hidden(proto.getIsHidden())
                .build();
    }

    // Сохранённый статус в виде DTO создания: так изменение workflow проверяется теми же правилами, что и создание.
    public CreateWorkflowStatusDto toStatusDto(StatusEntity entity) {
        return CreateWorkflowStatusDto.builder()
                .statusKey(entity.getStatusKey())
                .name(entity.getName())
                .category(entity.getCategory())
                .sortOrder(entity.getSortOrder())
                .build();
    }

    public CreateWorkflowTransitionDto toTransitionDto(TransitionEntity entity, Map<UUID, String> statusIdToKey) {
        return CreateWorkflowTransitionDto.builder()
                .fromStatusKey(statusIdToKey.get(entity.getFromStatusId()))
                .toStatusKey(statusIdToKey.get(entity.getToStatusId()))
                .name(entity.getName())
                .sortOrder(entity.getSortOrder())
                .hidden(entity.isHidden())
                .build();
    }

    public StatusEntity toStatusEntity(UUID workflowId, CreateWorkflowStatusDto dto) {
        return StatusEntity.builder()
                .workflowId(workflowId)
                .statusKey(dto.getStatusKey())
                .name(dto.getName())
                .category(dto.getCategory())
                .sortOrder(dto.getSortOrder())
                .build();
    }

    public TransitionEntity toTransitionEntity(UUID workflowId, Map<String, UUID> statusKeyToId,
                                               CreateWorkflowTransitionDto dto) {
        return TransitionEntity.builder()
                .workflowId(workflowId)
                .fromStatusId(statusKeyToId.get(dto.getFromStatusKey()))
                .toStatusId(statusKeyToId.get(dto.getToStatusKey()))
                .name(dto.getName())
                .sortOrder(dto.getSortOrder())
                .hidden(dto.isHidden())
                .build();
    }

    public WorkflowBindingEntity toBindingEntity(UUID projectId, UUID workflowId,
                                                  ru.taska.domain.IssueType issueType) {
        return WorkflowBindingEntity.builder()
                .projectId(projectId)
                .issueType(issueType.name())
                .workflowId(workflowId)
                .build();
    }

    private StatusCategory toDomainCategory(ru.taska.api.workflow.v1.StatusCategory proto) {
        return switch (proto) {
            case STATUS_CATEGORY_TODO -> StatusCategory.TODO;
            case STATUS_CATEGORY_IN_PROGRESS -> StatusCategory.IN_PROGRESS;
            case STATUS_CATEGORY_DONE -> StatusCategory.DONE;
            default -> null;
        };
    }
}
