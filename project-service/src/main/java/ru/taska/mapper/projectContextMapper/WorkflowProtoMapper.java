package ru.taska.mapper.projectContextMapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowStatusDto;
import ru.taska.domain.dto.projectContext.WorkflowTransitionDto;

import java.time.Instant;

/**
 * Маппер ответов workflow-service (proto) в доменные DTO project-service.
 * Нужен, чтобы ProjectServiceImpl не знал про proto.
 */
@Component
public class WorkflowProtoMapper {

    private static final String STATUS_CATEGORY_PREFIX = "STATUS_CATEGORY_";

    public ProjectWorkflowDto toProjectWorkflowDto(String issueType, WorkflowResponse proto) {
        return new ProjectWorkflowDto(issueType, toWorkflowDto(proto));
    }

    private WorkflowDto toWorkflowDto(WorkflowResponse proto) {
        return WorkflowDto.builder()
                .id(proto.getId())
                .name(proto.getName())
                .version(proto.getVersion())
                .createdAt(parseInstant(proto.getCreatedAt()))
                .updatedAt(parseInstant(proto.getUpdatedAt()))
                .statuses(proto.getStatusesList().stream()
                        .map(this::toWorkflowStatusDto)
                        .toList()
                )
                .transitions(proto.getTransitionsList().stream()
                        .map(this::toWorkflowTransitionDto)
                        .toList()
                )
                .build();
    }

    private WorkflowStatusDto toWorkflowStatusDto(WorkflowStatus proto) {
        return WorkflowStatusDto.builder()
                .id(proto.getId())
                .statusKey(proto.getStatusKey())
                .name(proto.getName())
                .category(toStringStatusCategory(proto.getCategory()))
                .sortOrder(proto.getSortOrder())
                .build();
    }

    private WorkflowTransitionDto toWorkflowTransitionDto(WorkflowTransition proto) {
        return WorkflowTransitionDto.builder()
                .id(proto.getId())
                .fromStatusId(proto.getFromStatusId())
                .toStatusId(proto.getToStatusId())
                .name(proto.getName())
                .sortOrder(proto.getSortOrder())
                .build();
    }

    private Instant parseInstant(Timestamp time) {
        return Instant.ofEpochSecond(time.getSeconds(), time.getNanos());
    }

    private String toStringStatusCategory(StatusCategory category) {
        if (category == null || category == StatusCategory.STATUS_CATEGORY_UNSPECIFIED) {
            return "UNKNOWN";
        }
        String name = category.name();
        return name.startsWith(STATUS_CATEGORY_PREFIX)
                ? name.substring(STATUS_CATEGORY_PREFIX.length())
                : name;
    }
}
