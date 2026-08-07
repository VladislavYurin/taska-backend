package ru.taska.mapper;


import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ProjectMapper {

    /// gRPC TO REST Dto

    /**
     * ProjectResponse -> ProjectResponseDto
     * @param protoDto
     * @return restDto
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
            restDto.setArchivedAt(JsonNullable.of(toOffsetDateTime(protoDto.getArchivedAt())));
        }
        return restDto;
    }

    /**
     * ListMyProjectsResponse -> ListProjectResponseDto
     * @param protoDto
     * @return restDto
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
     * @param protoDto
     * @return restDto
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
     * @param protoDto
     * @return restDto
     */
    public ProjectMemberResponseDto toRestChangeProjectMemberRoleResponse(ChangeProjectMemberRoleResponse protoDto) {
        var restDto = new ProjectMemberResponseDto();
        restDto.setProjectId(protoDto.getProjectId());
        restDto.setUserId(protoDto.getChangedMemberId());
        restDto.setRole(toRestProjectRole(protoDto.getRole()));
        return restDto;
    }

    /// REST строка -> ProjectRole Enum grpc
    public ProjectRole toGrpcProjectRole(String restRole) {
        if (restRole == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role is required");
        }
        return switch (restRole) {
            case "ADMIN" -> ProjectRole.PROJECT_ROLE_ADMIN;
            case "MEMBER" -> ProjectRole.PROJECT_ROLE_MEMBER;
            case "VIEWER" -> ProjectRole.PROJECT_ROLE_VIEWER;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid project role: " + restRole + ". Allowed: ADMIN, MEMBER, VIEWER"
            );
        };
    }

    /**
     * ProjectRole Enum grpc → REST строка
     */
    public String toRestProjectRole(ProjectRole grpcRole) {
        if (grpcRole == null || grpcRole == ProjectRole.PROJECT_ROLE_UNSPECIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid project role: " + grpcRole
            );
        }

        return switch (grpcRole) {
            case PROJECT_ROLE_ADMIN -> "ADMIN";
            case PROJECT_ROLE_MEMBER -> "MEMBER";
            case PROJECT_ROLE_VIEWER -> "VIEWER";
            default -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid project role: " + grpcRole
            );
        };
    }

    /// UTILITY
    public OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
