package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.AddProjectMemberRequest;
import ru.taska.api.project.v1.AddProjectMemberRequestBody;
import ru.taska.api.project.v1.ChangeProjectMemberRoleRequest;
import ru.taska.api.project.v1.ChangeProjectMemberRoleRequestBody;
import ru.taska.api.project.v1.CreateProjectRequest;
import ru.taska.api.project.v1.CreateProjectRequestBody;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.GetProjectRequestBody;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsRequestBody;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.RmProjectMemberRequest;
import ru.taska.api.project.v1.RmProjectMemberRequestBody;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.AddProjectMemberRequestDto;
import ru.taska.domain.dto.ChangeProjectMemberRoleRequestDto;
import ru.taska.domain.dto.CreateProjectRequestDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.mapper.ProjectMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с project-service.
 * Преобразует REST request в gRPC request.
 * Формирует protobuf-запросы, вызывает gRPC-методы и преобразует ответы в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcProjectServiceClient {

    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;
    private final ProjectMapper projectMapper;
    private final GrpcClientProperties properties;

    /**
     * Вызов создания нового проекта из Rest request
     * @param request параметры создаваемого проекта (projectKey, name)
     * @param context контекст запроса (содержит userId из JWT)
     * @return Rest DTO созданного проекта
     */
    public Mono<ProjectResponseDto> createProject(
            Mono<CreateProjectRequestDto> request,
            GatewayContext context
    ){
        log.info("[{}] Calling createProject",context.requestId());

        return request.flatMap(requestDto -> dynamicStub().createProject(
                CreateProjectRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                CreateProjectRequestBody.newBuilder()
                                        .setProjectKey(requestDto.getProjectKey())
                                        .setName(requestDto.getName())
                                        .setUserId(context.userContext().userId())
                        )
                        .build()
                )
        ).map(projectMapper::toRestProjectResponse);
    }

    /**
     * Вызов получения проекта
     * @param projectId идентификатор проекта
     * @param context контекст запроса
     *
     * @return Rest DTO полученного проекта
     */
    public Mono<ProjectResponseDto> getProject(
            String projectId,
            GatewayContext context
    ){
        log.info("[{}] Calling getProject",context.requestId());

        return dynamicStub().getProject(
                GetProjectRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                GetProjectRequestBody.newBuilder()
                                        .setProjectId(projectId)
                                        .setActorUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).map(projectMapper::toRestProjectResponse);
    }

    /**
     * Вызов получения списка проектов пользователя
     * @param context контекст запроса
     * @return Rest DTO списка проектов
     */

    public Mono<ListMyProjectResponseDto> listMyProjects(
            GatewayContext context
    ){
        log.info("[{}] Calling listUserProject",context.requestId());

        return dynamicStub().listMyProjects(
                ListMyProjectsRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                ListMyProjectsRequestBody.newBuilder()
                                        .setUserId(context.userContext().userId())
                                        .build()
                        )
                        .build()
        ).map(projectMapper::toRestListMyProjectsResponse);
    }


    /**
     * Вызов добавления участника в проект
     * @param projectId идентификатор проекта
     * @param request параметры создаваемого проекта
     * @param context контекст запроса
     * @return Rest DTO добавленного нового участника проекта
     */
    public Mono<ProjectMemberResponseDto> addProjectMember(
            String projectId,
            Mono<AddProjectMemberRequestDto> request,
            GatewayContext context
    ){
        log.info("[{}] Calling addProjectMember",context.requestId());

        return request.flatMap(requestDto -> dynamicStub().addProjectMember(
                AddProjectMemberRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                AddProjectMemberRequestBody.newBuilder()
                                        .setProjectId(projectId)
                                        .setRole(projectMapper.toGrpcProjectRole(
                                                requestDto.getRole() != null ? requestDto.getRole().toString() : null
                                        ))
                                        .setAddedMemberId(requestDto.getUserId())
                                        .setActorUserId(context.userContext().userId())
                                        .build())
                        .build()
                )
        ).map(projectMapper::toRestAddProjectMemberResponse);
    }

    /**
     * Вызов изменения роли участника в проекте
     * @param projectId идентификатор проекта
     * @param userId идентификатор участника
     * @param request параметры создаваемого проекта
     * @param context контекст запроса
     * @return Rest DTO изменения участника проекта
     */
    public Mono<ProjectMemberResponseDto> changeProjectMemberRole(
            String projectId,
            String userId,
            Mono<ChangeProjectMemberRoleRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling changeProjectMemberRole", context.requestId());

        return request.flatMap(requestDto-> dynamicStub().changeProjectMemberRole(
                ChangeProjectMemberRoleRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(
                                ChangeProjectMemberRoleRequestBody.newBuilder()

                                        .setProjectId(projectId)
                                        .setChangedMemberId(userId)
                                        .setActorUserId(context.userContext().userId())
                                        .setRole(projectMapper.toGrpcProjectRole(
                                                requestDto.getRole() != null ? requestDto.getRole().toString() : null
                                        ))
                                        .build()
                        )
                        .build()
                )
        ).map(projectMapper::toRestChangeProjectMemberRoleResponse);
    }

    /**
     * Вызов удаления участника проекта
     * @param projectId идентификатор проекта
     * @param userId идентификатор участника
     * @param context контекст запроса
     * @return
     */
    public Mono<Void> removeProjectMember(
            String projectId,
            String userId,
            GatewayContext context
    ){
        return dynamicStub().rmProjectMember(
                RmProjectMemberRequest.newBuilder()
                        .setHeader(buildGrpcHeader(context))
                        .setBody(RmProjectMemberRequestBody.newBuilder()
                                .setProjectId(projectId)
                                .setDeletedMemberId(userId)
                                .setActorUserId(context.userContext().userId())
                                .build())
                        .build()
        ).then();
    }

    /**
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub dynamicStub() {
        return projectServiceStub.withDeadlineAfter(properties.projectService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }
    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
