package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.ProjectApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.AddProjectMemberRequestDto;
import ru.taska.domain.dto.ChangeProjectMemberRoleRequestDto;
import ru.taska.domain.dto.CreateProjectRequestDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcProjectServiceClient;

/**
 * REST-контроллер для работы с проектами
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с project-сервисом через {@link GrpcProjectServiceClient}.
 */
@RestController
@RequiredArgsConstructor
public class ProjectController implements ProjectApi {

    private final GatewayRequestExecutor executor;
    private final GrpcProjectServiceClient projectClient;

    /**
     * POST /api/v1/projects
     * Создает проект → 201 CREATED
     */
    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> createProject(
            Mono<CreateProjectRequestDto> createProjectRequestDto,
            ServerWebExchange exchange
    ) {
        return createProjectRequestDto.flatMap(request -> {
                    return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                        projectClient.createProject(Mono.just(request), context))
                            .map(responseBody -> ResponseEntity.status(HttpStatus.CREATED).body(responseBody));

       });
    }

    /**
     * GET /api/v1/projects/{projectId}
     * Получает проект по ID → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ProjectResponseDto>> getProject(
            String projectId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                projectClient.getProject(projectId,context))
                        .map(ResponseEntity::ok);
    }

    /**
     * GET /api/v1/projects
     * Получает проекты текущего пользователя → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ListMyProjectResponseDto>> listMyProjects(ServerWebExchange exchange) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, projectClient::listMyProjects)
                        .map(ResponseEntity::ok);
    }

    /**
     * POST /api/v1/projects/{projectId}/members
     * Добавляет участника → 201 CREATED
     */
    @Override
    public Mono<ResponseEntity<ProjectMemberResponseDto>> addProjectMember(
            String projectId,
            Mono<AddProjectMemberRequestDto> addProjectMemberRequestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, gatewayContext ->
                projectClient.addProjectMember(projectId,addProjectMemberRequestDto,gatewayContext))
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * PATCH /api/v1/projects/{projectId}/members/{userId}
     * Меняет роль участника → 200 OK
     */
    @Override
    public Mono<ResponseEntity<ProjectMemberResponseDto>> changeProjectMemberRole(
            String projectId,
            String userId,
            Mono<ChangeProjectMemberRoleRequestDto> changeProjectMemberRoleRequestDto,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,gatewayContext ->
                projectClient.changeProjectMemberRole(projectId,userId,changeProjectMemberRoleRequestDto,gatewayContext))
                        .map(ResponseEntity::ok);
    }

    /**
     * DELETE /api/v1/projects/{projectId}/members/{userId}
     * Удаляет участника → 204 NO CONTENT
     */
    @Override
    public Mono<ResponseEntity<Void>> removeProjectMember(
            String projectId,
            String userId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED,context ->
                projectClient.removeProjectMember(projectId,userId,context))
                        .thenReturn(ResponseEntity.noContent().build());
    }
}
