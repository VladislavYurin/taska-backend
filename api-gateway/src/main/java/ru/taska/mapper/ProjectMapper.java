package ru.taska.mapper;


import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.AvatarResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ListProjectMemberResponse;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectLabelResponse;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ProjectWorkflowEntryResponse;
import ru.taska.api.project.v1.WorkflowStatusResponse;
import ru.taska.api.project.v1.WorkflowTransitionResponse;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.AvatarDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ListProjectMemberDetailsDto;
import ru.taska.domain.dto.ProjectContextResponseDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectMemberRoleDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.domain.dto.ProjectWorkflowEntryDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.domain.dto.WorkflowTransitionDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProjectMapper {

    /// ================= gRPC TO REST Dto ====================

    /**
     * ProjectResponse -> ProjectResponseDto
     */
    public ProjectResponseDto toRestProjectResponse (ProjectResponse protoDto){
        var restDto = new ProjectResponseDto();
        restDto.setId(protoDto.getId());
        restDto.setProjectKey(protoDto.getProjectKey());
        restDto.setName(protoDto.getName());
        restDto.setCreatedBy(protoDto.getCreatedBy());
        restDto.setCreatedAt(toOffsetDateTime(protoDto.getCreatedAt()));
        restDto.setUpdatedAt(toOffsetDateTime(protoDto.getUpdatedAt()));
        if (protoDto.hasArchivedAt()){
            restDto.setArchivedAt(toOffsetDateTime(protoDto.getArchivedAt()));
        }
        if (protoDto.hasCurrentUserRole()) {
            restDto.setCurrentUserRole(toRestProjectRole(protoDto.getCurrentUserRole()));
        }
        return restDto;
    }

    /**
     * ListMyProjectsResponse -> ListProjectResponseDto
     */
    public ListMyProjectResponseDto toRestListMyProjectsResponse(ListMyProjectsResponse protoDto) {
        var restDto = new ListMyProjectResponseDto();
        List<ProjectResponseDto> items = new ArrayList<>();

        protoDto.getProjectResponseList()
                .forEach(project -> items.add(this.toRestProjectResponse(project)));

        restDto.setItems(items);
        return restDto;
    }

    /**
     * AddProjectMemberResponse -> ProjectMemberResponseDto
     */
    public ProjectMemberResponseDto toRestAddProjectMemberResponse(AddProjectMemberResponse protoDto) {
        var restDto = new ProjectMemberResponseDto();
        restDto.setProjectId(protoDto.getProjectId());
        restDto.setUserId(protoDto.getAddedMemberId());
        restDto.setRole(toRestProjectRole(protoDto.getRole()));
        return restDto;
    }

    /**
     * ChangeProjectMemberRoleResponse ->ProjectMemberResponseDto
     */
    public ProjectMemberResponseDto toRestChangeProjectMemberRoleResponse(ChangeProjectMemberRoleResponse protoDto) {
        var restDto = new ProjectMemberResponseDto();
        restDto.setProjectId(protoDto.getProjectId());
        restDto.setUserId(protoDto.getChangedMemberId());
        restDto.setRole(toRestProjectRole(protoDto.getRole()));
        return restDto;
    }

    /**
     * ListProjectMemberResponse -> ListProjectMemberDetailsDto
     */
    public ListProjectMemberDetailsDto toListProjectMemberDetailsDto(ListProjectMemberResponse listProjectMemberResponse) {
        ListProjectMemberDetailsDto restDto = new ListProjectMemberDetailsDto();
        List<ProjectMemberDetailsDto> members = new ArrayList<>();

        listProjectMemberResponse.getMembersList()
                .forEach(project -> members.add(toProjectMemberDetailsDto(project)));

        restDto.setMembers(members);
        return restDto;
    }

    /**
     * ProjectMemberDetailsResponse -> ProjectMemberDetailsDto
     */
    private ProjectMemberDetailsDto toProjectMemberDetailsDto(ProjectMemberDetailsResponse proto) {
        ProjectMemberDetailsDto dto = new ProjectMemberDetailsDto();
        dto.setUserId(proto.getUserId());
        dto.setRole(toRestProjectRole(proto.getRole()));
        dto.setDisplayName(proto.getDisplayName());
        dto.setEmail(proto.getEmail());
        if (proto.hasAvatar()) {
            dto.setAvatar(toAvatarDto(proto.getAvatar()));
        }
        if (proto.hasAddedAt()) {
            dto.setAddedAt(toOffsetDateTime(proto.getAddedAt()));
        }

        return dto;
    }

    private AvatarDto toAvatarDto(AvatarResponse avatarResponse) {
        if (avatarResponse == null) {
            return null;
        }

        AvatarDto avatarDto = new AvatarDto();
        avatarDto.setId(avatarResponse.getId());
        avatarDto.setObjectKey(avatarResponse.getObjectKey());
        avatarDto.setFileName(avatarResponse.getFileName());
        avatarDto.setContentType(avatarResponse.getContentType());
        avatarDto.setSizeBytes(avatarResponse.getSizeBytes());
        avatarDto.setDownloadUrl(avatarResponse.getDownloadUrl());

        return avatarDto;
    }

    public ProjectContextResponseDto toRestProjectContextResponse(ProjectContextResponse proto) {
        var dto = new ProjectContextResponseDto();
        dto.setProject(toRestProjectResponse(proto.getProject()));
        dto.setMembers(proto.getMembersList().stream()
                .map(this::toProjectMemberDetailsDto)
                .toList());
        dto.setLabels(proto.getLabelsList().stream()
                .map(this::toProjectLabelResponseDto)
                .toList());
        dto.setWorkflows(proto.getWorkflowsList().stream()
                .map(this::toProjectWorkflowEntryDto)
                .toList());
        return dto;
    }

    private ProjectLabelResponseDto toProjectLabelResponseDto(ProjectLabelResponse proto) {
        ProjectLabelResponseDto dto = new ProjectLabelResponseDto();
        dto.setId(UUID.fromString(proto.getId()));
        dto.setName(proto.getName());
        dto.setColor(proto.getColor());
        return dto;
    }

    private ProjectWorkflowEntryDto toProjectWorkflowEntryDto(ProjectWorkflowEntryResponse proto) {
        ProjectWorkflowEntryDto dto = new ProjectWorkflowEntryDto();
        dto.setIssueType(toRestIssueType(proto.getIssueType()));

        WorkflowResponseDto workflow = new WorkflowResponseDto();
        workflow.setId(UUID.fromString(proto.getWorkflow().getId()));
        workflow.setName(proto.getWorkflow().getName());
        workflow.setVersion(proto.getWorkflow().getVersion());
        workflow.setCreatedAt(toOffsetDateTime(proto.getWorkflow().getCreatedAt()));
        workflow.setUpdatedAt(toOffsetDateTime(proto.getWorkflow().getUpdatedAt()));
        workflow.setStatuses(proto.getWorkflow().getStatusesList().stream()
                .map(this::toWorkflowStatusDto)
                .toList());
        workflow.setTransitions(proto.getWorkflow().getTransitionsList().stream()
                .map(this::toWorkflowTransitionDto)
                .toList());
        dto.setWorkflow(workflow);
        return dto;
    }

    private WorkflowStatusDto toWorkflowStatusDto(WorkflowStatusResponse proto) {
        WorkflowStatusDto dto = new WorkflowStatusDto();
        dto.setId(UUID.fromString(proto.getId()));
        dto.setStatusKey(proto.getStatusKey());
        dto.setName(proto.getName());
        dto.setCategory(proto.getCategory());
        dto.setSortOrder(proto.getSortOrder());
        return dto;
    }

    private WorkflowTransitionDto toWorkflowTransitionDto(WorkflowTransitionResponse proto) {
        WorkflowTransitionDto dto = new WorkflowTransitionDto();
        dto.setId(UUID.fromString(proto.getId()));
        dto.setFromStatusId(UUID.fromString(proto.getFromStatusId()));
        dto.setToStatusId(UUID.fromString(proto.getToStatusId()));
        dto.setName(proto.getName());
        dto.setSortOrder(proto.getSortOrder());
        return dto;
    }

    /**
     * ProjectMemberRoleDto (REST enum) -> ProjectRole (proto enum).
     */
    public ProjectRole toGrpcProjectRole(ProjectMemberRoleDto role) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role is required");
        }
        return switch (role) {
            case ProjectMemberRoleDto.ADMIN -> ProjectRole.PROJECT_ROLE_ADMIN;
            case ProjectMemberRoleDto.MEMBER -> ProjectRole.PROJECT_ROLE_MEMBER;
            case ProjectMemberRoleDto.VIEWER -> ProjectRole.PROJECT_ROLE_VIEWER;
        };
    }

    /**
     * ProjectRole (proto enum) -> REST String.
     */
    public String toRestProjectRole(ProjectRole grpcRole) {
        if (grpcRole == null) {
            return null;
        }

        return switch (grpcRole) {
            case PROJECT_ROLE_ADMIN  -> "ADMIN";
            case PROJECT_ROLE_MEMBER -> "MEMBER";
            case PROJECT_ROLE_VIEWER -> "VIEWER";
            default -> null;
        };
    }

    /// ================ UTILITY ====================
    public OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    private IssueTypeDto toRestIssueType(String proto) {
        return switch (proto) {
            case "TASK"  -> IssueTypeDto.TASK;
            case "BUG"   -> IssueTypeDto.BUG;
            case "STORY" -> IssueTypeDto.STORY;
            default -> null;
        };
    }

    public GlobalRoleProto toGlobalRoleProto(GlobalRole role) {
        return switch (role) {
            case GlobalRole.GLOBAL_ADMIN -> GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN;
            case GlobalRole.USER -> GlobalRoleProto.GLOBAL_ROLE_USER;
            default -> GlobalRoleProto.GLOBAL_ROLE_UNSPECIFIED;
        };
    }
}
