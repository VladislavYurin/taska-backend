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
import ru.taska.api.project.v1.GetListProjectMemberRequest;
import ru.taska.api.project.v1.GetProjectContextRequest;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalRequest;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalResponse;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.GetProjectMemberStatsBatchRequest;
import ru.taska.api.project.v1.GetProjectMemberStatsBatchResponse;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ListProjectMemberResponse;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectKeyResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.RmProjectMemberRequest;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.api.project.v1.UpdateProjectRequest;


@GrpcService
@RequiredArgsConstructor
public class GrpcProjectServiceAdapter extends ReactorProjectServiceGrpc.ProjectServiceImplBase{

    private final GrpcProjectService grpcProjectService;
    private final ProjectArchiveGrpcService archiveService;
    private final ProjectIssueTypeGrpcService issueTypeService;
    private final ru.taska.repository.ProjectMemberRepository members;
    @Override
    public Mono<ru.taska.api.project.v1.HasProjectMembershipInternalResponse> hasProjectMembershipInternal(
            Mono<ru.taska.api.project.v1.HasProjectMembershipInternalRequest> request) {
        return request.flatMap(req -> validator.GrpcRequestValidators.parseUuidOrInvalidArgument(req.getUserId(), "userId"))
                .flatMap(members::hasAnyMembership)
                .map(member -> ru.taska.api.project.v1.HasProjectMembershipInternalResponse.newBuilder().setMember(member).build())
                .transform(GrpcExceptionHandler.withErrorHandling("hasProjectMembershipInternal"));
    }


    @Override
    public Mono<GetProjectMemberStatsBatchResponse> getProjectMemberStatsBatch(
            Mono<GetProjectMemberStatsBatchRequest> request) {
        return grpcProjectService.getProjectMemberStatsBatch(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProjectMemberStatsBatch"));
    }

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
    public Mono<ProjectContextResponse> getProjectContext(Mono<GetProjectContextRequest> request) {
        return grpcProjectService.getProjectContext(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProjectContext"));
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

    @Override
    public Mono<ListProjectMemberResponse> getProjectMembers(Mono<GetListProjectMemberRequest> request) {
        return grpcProjectService.getProjectMembers(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProjectMembers"));
    }

    @Override
    public Mono<GetProjectInfoBatchInternalResponse> getProjectInfoBatchInternal(Mono<GetProjectInfoBatchInternalRequest> request) {
        return grpcProjectService.getProjectInfoBatchInternal(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getProjectInfoBatchInternal"));
    }

    @Override
    public Mono<ProjectResponse> updateProject(Mono<UpdateProjectRequest> request) {
        return grpcProjectService.updateProject(request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateProject"));
    }

    @Override
    public Mono<ru.taska.api.project.v1.ListProjectIssueTypesResponse> reorderProjectIssueTypes(
            Mono<ru.taska.api.project.v1.ReorderProjectIssueTypesRequest> request) {
        return issueTypeService.reorder(request).transform(GrpcExceptionHandler.withErrorHandling("reorderProjectIssueTypes"));
    }

    @Override
    public Mono<ru.taska.api.project.v1.ListProjectIssueTypesResponse> listProjectIssueTypes(Mono<ru.taska.api.project.v1.ProjectIssueTypesRequest> request) {
        return issueTypeService.list(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listProjectIssueTypes"));
    }

    @Override
    public Mono<ru.taska.api.project.v1.ProjectIssueTypeResponse> createProjectIssueType(Mono<ru.taska.api.project.v1.ChangeProjectIssueTypeRequest> request) {
        return issueTypeService.create(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createProjectIssueType"));
    }

    @Override
    public Mono<ru.taska.api.project.v1.ProjectIssueTypeResponse> updateProjectIssueType(Mono<ru.taska.api.project.v1.ChangeProjectIssueTypeRequest> request) {
        return issueTypeService.update(request)
                .transform(GrpcExceptionHandler.withErrorHandling("updateProjectIssueType"));
    }

    @Override
    public Mono<com.google.protobuf.Empty> deleteProjectIssueType(Mono<ru.taska.api.project.v1.ChangeProjectIssueTypeRequest> request) {
        return issueTypeService.delete(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteProjectIssueType"));
    }
    @Override
    public Mono<ru.taska.api.project.v1.DeleteProjectIssueTypeResult> deleteProjectIssueTypeWithMigration(Mono<ru.taska.api.project.v1.ChangeProjectIssueTypeRequest> request) {
        return issueTypeService.deleteWithMigration(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteProjectIssueTypeWithMigration"));
    }
    @Override
    public Mono<ru.taska.api.project.v1.ProjectResponse> archiveProject(Mono<ru.taska.api.project.v1.ArchiveProjectRequest> request) {
        return archiveService.archive(request).transform(GrpcExceptionHandler.withErrorHandling("archiveProject"));
    }

}
