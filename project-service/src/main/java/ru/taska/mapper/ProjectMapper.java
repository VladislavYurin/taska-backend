package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectLabelResponse;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectWorkflowEntryResponse;
import ru.taska.api.project.v1.WorkflowDataResponse;
import ru.taska.api.project.v1.WorkflowStatusResponse;
import ru.taska.api.project.v1.WorkflowTransitionResponse;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectContextDto;
import ru.taska.domain.dto.projectContext.ProjectLabelDto;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowStatusDto;
import ru.taska.domain.dto.projectContext.WorkflowTransitionDto;

import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ProjectMemberMapper.class}
)
public interface ProjectMapper {

    /**
     * Маппинг {@link Project} в {@link ProjectResponse}
     * param {@link Project}
     * return {@link ProjectResponse}
     */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "projectKey", source = "projectKey")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "archivedAt", source = "archivedAt", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    ProjectResponse toProjectResponse(Project project);

    /**
     * Маппинг {@link ProjectCheckMembershipDto} в {@link ProjectResponse}
     * param {@link ProjectCheckMembershipDto}
     * return {@link ProjectResponse}
     */
    @Mapping(target = "id", source = "project_id")
    @Mapping(target = "projectKey", source = "project_key")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "created_by")
    @Mapping(target = "createdAt", source = "created_at")
    @Mapping(target = "updatedAt", source = "updated_at")
    @Mapping(target = "archivedAt", source = "archived_at", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "currentUserRole", source = "role", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    ProjectResponse toProjectResponse(ProjectCheckMembershipDto project);

    @Mapping(target = "id", source = "project_id")
    @Mapping(target = "projectKey", source = "project_key")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "created_by")
    @Mapping(target = "createdAt", source = "created_at")
    @Mapping(target = "updatedAt", source = "updated_at")
    @Mapping(target = "archivedAt", source = "archived_at")
    Project toProject(ProjectCheckMembershipDto dto);

    /// ───────── ProjectMemberDetailsDto → ProjectMemberDetailsResponse ─────────
    /// Реализацию MapStruct сгенерирует через uses = {ProjectMemberMapper.class}
    ProjectMemberDetailsResponse toProjectMemberResponse(ProjectMemberDetailsDto dto);

    default ProjectContextResponse toProjectContextResponse(ProjectContextDto dto) {
        ProjectContextResponse.Builder builder = ProjectContextResponse.newBuilder()
                .setProject(toProjectResponse(dto.project()));

        dto.members().forEach(m -> builder.addMembers(toProjectMemberResponse(m)));
        dto.labels().forEach(l -> builder.addLabels(toProjectLabelResponse(l)));
        dto.workflows().forEach(w -> builder.addWorkflows(toProjectWorkflowResponse(w)));

        return builder.build();
    }

    /// ───────── HELPERS ─────────

    /**
     * Конвертация UUID в String
     */
    default String mapUuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * Конвертация Java Instant в Protobuf Timestamp
     */
    default Timestamp mapInstantToTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    default ProjectLabelResponse toProjectLabelResponse(ProjectLabelDto dto) {
        return ProjectLabelResponse.newBuilder()
                .setId(mapUuidToString(dto.id()))
                .setName(dto.name())
                .setColor(dto.color())
                .build();
    }

    default ProjectWorkflowEntryResponse toProjectWorkflowResponse(ProjectWorkflowDto dto) {
        return ProjectWorkflowEntryResponse.newBuilder()
                .setIssueType(dto.issueType())
                .setWorkflow(toWorkflowDataResponse(dto.workflow()))
                .build();
    }

    default WorkflowDataResponse toWorkflowDataResponse(WorkflowDto dto) {
        WorkflowDataResponse.Builder builder = WorkflowDataResponse.newBuilder()
                .setId(dto.id())
                .setName(dto.name())
                .setVersion(dto.version())
                .setCreatedAt(mapInstantToTimestamp(dto.createdAt()))
                .setUpdatedAt(mapInstantToTimestamp(dto.updatedAt()));

        dto.statuses().forEach(s -> builder.addStatuses(toWorkflowStatusResponse(s)));
        dto.transitions().forEach(t -> builder.addTransitions(toWorkflowTransitionResponse(t)));

        return builder.build();
    }

    default WorkflowStatusResponse toWorkflowStatusResponse(WorkflowStatusDto dto) {
        return WorkflowStatusResponse.newBuilder()
                .setId(dto.id())
                .setStatusKey(dto.statusKey())
                .setName(dto.name())
                .setCategory(dto.category())
                .setSortOrder(dto.sortOrder())
                .build();
    }

    default WorkflowTransitionResponse toWorkflowTransitionResponse(WorkflowTransitionDto dto) {
        return WorkflowTransitionResponse.newBuilder()
                .setId(dto.id())
                .setFromStatusId(dto.fromStatusId())
                .setToStatusId(dto.toStatusId())
                .setName(dto.name())
                .setSortOrder(dto.sortOrder())
                .build();
    }
    default GlobalRole toGlobalRole(GlobalRoleProto proto){
        if (proto == null) {
            return GlobalRole.USER;
        }
        return switch (proto) {
            case GLOBAL_ROLE_GLOBAL_ADMIN -> GlobalRole.GLOBAL_ADMIN;
            case GLOBAL_ROLE_USER -> GlobalRole.USER;
            default -> GlobalRole.USER;
        };
    }
}