package ru.taska.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ValueMapping;
import org.mapstruct.ValueMappings;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.domain.ProjectMember;
import ru.taska.domain.dto.ProjectMembershipInfoDto;
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
    @Mapping(source = "role", target = "role")
    @Mapping(source = "projectId", target = "projectId")
    ChangeProjectMemberRoleResponse toChangeProjectMemberRoleResponse(ProjectMember projectMember);

    /**
     * Маппит транспортный {@link ru.taska.api.project.v1.ProjectRole} в {@link ProjectRole} для сохранения в БД
     *
     * @param role роль участника из grpc .proto
     * @return {@link ProjectRole} из enum, используемого в  entity
     */
    @ValueMappings({
            @ValueMapping(source = "PROJECT_ROLE_ADMIN", target = "ADMIN"),
            @ValueMapping(source = "PROJECT_ROLE_MEMBER", target = "MEMBER"),
            @ValueMapping(source = "PROJECT_ROLE_VIEWER", target = "VIEWER"),
            @ValueMapping(source = "PROJECT_ROLE_UNSPECIFIED", target = "UNSPECIFIED"),
            @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = "UNSPECIFIED")})
    ProjectRole toProjectRole(ru.taska.api.project.v1.ProjectRole role);

    /**
     * Маппит {@link ProjectRole} в транспортный {@link ru.taska.api.project.v1.ProjectRole}
     *
     * @param role роль участника из enum ProjectRole
     * @return {@link ru.taska.api.project.v1.ProjectRole} из enum, используемого в grpc .proto
     */
    @ValueMappings({
            @ValueMapping(source = "ADMIN", target = "PROJECT_ROLE_ADMIN"),
            @ValueMapping(source = "MEMBER", target = "PROJECT_ROLE_MEMBER"),
            @ValueMapping(source = "VIEWER", target = "PROJECT_ROLE_VIEWER"),
            @ValueMapping(source = "UNSPECIFIED", target = "PROJECT_ROLE_UNSPECIFIED"),
            @ValueMapping(source = MappingConstants.ANY_UNMAPPED, target = "PROJECT_ROLE_UNSPECIFIED")})
    ru.taska.api.project.v1.ProjectRole toGrpcRole(ProjectRole role);

    /**
     * Билдер, который создает транспортный grpc объектп.
     *
     * @param {@link ProjectMembershipInfoDto} из трех элементов, содержащий:
     *               <ul>
     *               <li>{@link ProjectRole} — роль участника проекта</li>
     *               <li>{@link Boolean} — флаг, сигнализирующий о том, является ли пользователь участником проекта</li>
     *               <li>{@link Boolean} — флаг, сигнализирующий о том, существует ли такой проект</li>
     *               </ul>
     * @return {@link ru.taska.api.project.v1.CheckProjectMemberRoleResponse} из трех элементов, содержащий:
     * <ul>
     * <li>{@link ProjectRole} — роль участника проекта</li>
     * <li>{@link Boolean} — флаг, сигнализирующий о том, является ли пользователь участником проекта</li>
     * <li>{@link Boolean} — флаг, сигнализирующий о том, существует ли такой проект</li>
     * </ul>
     */
    default CheckProjectMemberRoleResponse toCheckProjectRoleResponse(ProjectMembershipInfoDto dto) {
        return CheckProjectMemberRoleResponse.newBuilder()
                .setRole(this.toGrpcRole(dto.role()))
                .setIsMember(dto.isMember())
                .setProjectExists(dto.isProjectExists())
                .build();
    }
}
