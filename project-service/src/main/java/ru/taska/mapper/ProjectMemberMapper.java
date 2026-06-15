package ru.taska.mapper;

import org.mapstruct.*;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeRoleResponse;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.ProjectRole;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProjectMemberMapper {

    /**
     * Маппит сущность участника проекта {@link ru.taska.api.project.v1.ProjectRole} в DTO
     * {@link AddProjectMemberResponse} для последующей передачи по grpc
     *
     * @param projectMember сущность
     * @return {@link AddProjectMemberResponse} DTO дял передачи в grpc
     */
    @Mapping(source = "userId", target = "addedMemberId")
    @Mapping(source = "projectId", target = "projectId")
    @Mapping(source = "role", target = "role")
    AddProjectMemberResponse toAddProjectMemberResponse(ProjectMember projectMember);

    @Mapping(source = "userId", target = "deletedMemberId")
    @Mapping(source = "projectId", target = "projectId")
    RmProjectMemberResponse toRmProjectMemberResponse(ProjectMember projectMember);

    @Mapping(source = "userId", target = "changedMemberId")
    @Mapping(source = "projectId", target = "projectId")
    @Mapping(source = "role", target = "role")
    ChangeRoleResponse toChangeRoleResponse(ProjectMember projectMember);

    /**
     * Маппит транспортный {@link ru.taska.api.project.v1.ProjectRole} в {@link ProjectRole} для сохранения в БД
     *
     * @param role роль участника из grpc .proto
     * @return {@link ProjectRole} из enum, используемого в  entity
     */
    @ValueMappings({
            @ValueMapping(source = "ADMIN", target = "ADMIN"),
            @ValueMapping(source = "MEMBER", target = "MEMBER"),
            @ValueMapping(source = "VIEWER", target = "VIEWER"),
            @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = "VIEWER")})
    ProjectRole toProjectRole(ru.taska.api.project.v1.ProjectRole role);

}
