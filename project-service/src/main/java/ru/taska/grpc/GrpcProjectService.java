package ru.taska.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.exception.ProjectAlreadyExistsException;
import ru.taska.mapper.ProjectMapper;
import ru.taska.service.ProjectService;

@GrpcService
@RequiredArgsConstructor
public class GrpcProjectService extends ReactorProjectServiceGrpc.ProjectServiceImplBase {
    private final ProjectService projectService;
    private final ProjectMapper projectMapper;

    @Override
    public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> request) {
        return request
                .flatMap(req -> projectService.createProject(
                        req.getProjectKey(),
                        req.getName(),
                        req.getUserId()
                ))
                .map(projectMapper::toResponse)
                .onErrorMap(ProjectAlreadyExistsException.class, ex -> io.grpc.Status.ALREADY_EXISTS
                        .withDescription(ex.getMessage())
                        .asRuntimeException());
    }

    @Override
    public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> request) {
        return request
                .flatMap(req -> projectService.getProject(req.getProjectId()))
                .map(projectMapper::toResponse);
    }

    @Override
    public Mono<ListMyProjectsResponse> listMyProjects(Mono<ListMyProjectsRequest> request) {
        return request
                .flatMap(req -> projectService.listMyProjects(req.getUserId())
                        .map(projectMapper::toResponse)
                        .collectList()
                )
                .map(projectsList -> ListMyProjectsResponse.newBuilder()
                        .addAllProjects(projectsList)
                        .build());
    }
}