package ru.taska.mapper;

import org.mapstruct.*;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
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
            @ValueMapping(source = "UNSPECIFIED", target = "UNSPECIFIED"),
            @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = "UNSPECIFIED")})
    ProjectRole toProjectRole(ru.taska.api.project.v1.ProjectRole role);

    /**
     * Маппит {@link ProjectRole} в транспортный {@link ru.taska.api.project.v1.ProjectRole}
     *
     * @param role роль участника из enum ProjectRole
     * @return {@link ru.taska.api.project.v1.ProjectRole} из enum, используемого в grpc .proto
     */
    @ValueMappings({
            @ValueMapping(source = "ADMIN", target = "ADMIN"),
            @ValueMapping(source = "MEMBER", target = "MEMBER"),
            @ValueMapping(source = "VIEWER", target = "VIEWER"),
            @ValueMapping(source = "UNSPECIFIED", target = "UNSPECIFIED"),
            @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = "UNSPECIFIED")})
    ru.taska.api.project.v1.ProjectRole toGrpcRole(ProjectRole role);

    /**
     * Билдер, который создает транспортный {@link ru.taska.api.project.v1.CheckProjectMemberRoleResponse}
     *
     * @param role          роль участника из {@link ru.taska.api.project.v1.ProjectRole}
     * @param isMember      boolean, сигнализирующий о том, является ли пользователь участником проекта
     * @param projectExists boolean, сигнализирующий о том, существует ли такой проект
     * @return {@link ru.taska.api.project.v1.CheckProjectMemberRoleResponse} DTO для передачи в grpc
     */
    default CheckProjectMemberRoleResponse toCheckProjectRoleResponse(
            ru.taska.api.project.v1.ProjectRole role,
            boolean isMember,
            boolean projectExists
    ) {
        return CheckProjectMemberRoleResponse.newBuilder()
                .setRole(role)
                .setIsMember(isMember)
                .setProjectExists(projectExists)
                .build();
    }
}
