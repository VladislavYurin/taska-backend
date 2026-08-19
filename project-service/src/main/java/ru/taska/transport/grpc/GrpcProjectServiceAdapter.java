package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.AddProjectMemberRequest;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleRequest;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.CreateProjectRequest;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ProjectKeyResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.RmProjectMemberRequest;
import ru.taska.api.project.v1.RmProjectMemberResponse;


@GrpcService
@RequiredArgsConstructor
public class GrpcProjectServiceAdapter extends ReactorProjectServiceGrpc.ProjectServiceImplBase{

    private final GrpcProjectService grpcProjectService;

    @Override
    public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> request) {
        return grpcProjectService.createProject(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createProject"));
    }

    @Override
    public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> request) {
        return grpcProjectService.getProject(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProject"));
    }

    @Override
    public Mono<ListMyProjectsResponse> listMyProjects(Mono<ListMyProjectsRequest> request) {
        return grpcProjectService.listMyProjects(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listMyProjects"));
    }

    @Override
    public Mono<AddProjectMemberResponse> addProjectMember(Mono<AddProjectMemberRequest> request) {
        return grpcProjectService.addProjectMember(request)
                .transform(GrpcExceptionHandler.withErrorHandling("addProjectMember"));
    }

    @Override
    public Mono<RmProjectMemberResponse> rmProjectMember(Mono<RmProjectMemberRequest> request) {
        return grpcProjectService.rmProjectMember(request)
                .transform(GrpcExceptionHandler.withErrorHandling("rmProjectMember"));
    }

    @Override
    public Mono<ChangeProjectMemberRoleResponse> changeProjectMemberRole(Mono<ChangeProjectMemberRoleRequest> request) {
        return grpcProjectService.changeProjectMemberRole(request)
                .transform(GrpcExceptionHandler.withErrorHandling("changeProjectMemberRole"));
    }
    @Override
    public Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole (Mono<CheckProjectMemberRoleRequest> request) {
        return grpcProjectService.checkProjectMemberRole(request)
                .transform(GrpcExceptionHandler.withErrorHandling("checkProjectRole"));
    }

    @Override
    public Mono<ProjectKeyResponse> getProjectKeyInternal(Mono<GetProjectKeyInternalRequest> request) {
        return grpcProjectService.getProjectKeyInternal(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProjectKeyInternal"));
    }

}
