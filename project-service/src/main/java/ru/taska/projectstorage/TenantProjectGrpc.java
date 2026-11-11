package ru.taska.projectstorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.*;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
public class TenantProjectGrpc extends ReactorProjectServiceGrpc.ProjectServiceImplBase {
  private final TenantProjectApi api;
  private final ProjectCreation creation;

  public TenantProjectGrpc(TenantProjectApi api, ProjectCreation creation) {
    this.api = api;
    this.creation = creation;
  }

  private static <T> Mono<T> call(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<ProjectResponse> createProject(Mono<CreateProjectRequest> q) {
    return call(q.flatMap(creation::create));
  }

  @Override
  public Mono<ProjectResponse> getProject(Mono<GetProjectRequest> q) {
    return call(q.flatMap(v -> api.get(v.getBody().getProjectId(), v.getBody().getActorUserId())));
  }

  @Override
  public Mono<ProjectResponse> updateProject(Mono<UpdateProjectRequest> q) {
    return call(q.flatMap(v -> api.update(v.getBody())));
  }

  @Override
  public Mono<ProjectContextResponse> getProjectContext(Mono<GetProjectContextRequest> q) {
    return call(
        q.flatMap(v -> api.context(v.getBody().getProjectId(), v.getBody().getActorUserId())));
  }

  @Override
  public Mono<ListMyProjectsResponse> listMyProjects(Mono<ListMyProjectsRequest> q) {
    return call(q.flatMap(v -> api.list(v.getBody().getUserId())));
  }

  @Override
  public Mono<CheckProjectMemberRoleResponse> checkProjectMemberRole(
      Mono<CheckProjectMemberRoleRequest> q) {
    return call(q.flatMap(v -> api.check(v.getBody().getProjectId(), v.getBody().getUserId())));
  }

  @Override
  public Mono<ProjectKeyResponse> getProjectKeyInternal(Mono<GetProjectKeyInternalRequest> q) {
    return call(q.flatMap(v -> api.key(v.getBody().getProjectId())));
  }

  @Override
  public Mono<GetProjectInfoBatchInternalResponse> getProjectInfoBatchInternal(
      Mono<GetProjectInfoBatchInternalRequest> q) {
    return call(q.flatMap(v -> api.batch(v.getBody().getProjectIdsList())));
  }

  @Override
  public Mono<ListProjectMemberResponse> getProjectMembers(Mono<GetListProjectMemberRequest> q) {
    return call(
        q.flatMap(v -> api.members(v.getBody().getProjectId(), v.getBody().getActorUserId())));
  }

  @Override
  public Mono<GetProjectMemberStatsBatchResponse> getProjectMemberStatsBatch(
      Mono<GetProjectMemberStatsBatchRequest> q) {
    return call(q.flatMap(v -> api.stats(v.getProjectIdsList(), v.getActorUserId())));
  }

  @Override
  public Mono<ListProjectIssueTypesResponse> listProjectIssueTypes(
      Mono<ProjectIssueTypesRequest> q) {
    return call(q.flatMap(v -> api.types(v.getProjectId(), v.getActorUserId())));
  }

  @Override
  public Mono<HasProjectMembershipInternalResponse> hasProjectMembershipInternal(
      Mono<HasProjectMembershipInternalRequest> q) {
    return call(
        q.flatMap(v -> api.list(v.getUserId()))
            .map(
                v ->
                    HasProjectMembershipInternalResponse.newBuilder()
                        .setMember(v.getProjectResponseCount() > 0)
                        .build()));
  }

  @Override
  public Mono<AddProjectMemberResponse> addProjectMember(Mono<AddProjectMemberRequest> q) {
    return call(
        q.flatMap(
            v -> {
              var b = v.getBody();
              return api.change(
                      b.getProjectId(),
                      b.getActorUserId(),
                      b.getAddedMemberId(),
                      b.getRole(),
                      "ADD")
                  .thenReturn(
                      AddProjectMemberResponse.newBuilder()
                          .setProjectId(b.getProjectId())
                          .setAddedMemberId(b.getAddedMemberId())
                          .setRole(b.getRole())
                          .build());
            }));
  }

  @Override
  public Mono<RmProjectMemberResponse> rmProjectMember(Mono<RmProjectMemberRequest> q) {
    return call(
        q.flatMap(
            v -> {
              var b = v.getBody();
              return api.change(
                      b.getProjectId(),
                      b.getActorUserId(),
                      b.getDeletedMemberId(),
                      ProjectRole.PROJECT_ROLE_UNSPECIFIED,
                      "REMOVE")
                  .thenReturn(
                      RmProjectMemberResponse.newBuilder()
                          .setProjectId(b.getProjectId())
                          .setDeletedMemberId(b.getDeletedMemberId())
                          .build());
            }));
  }

  @Override
  public Mono<ChangeProjectMemberRoleResponse> changeProjectMemberRole(
      Mono<ChangeProjectMemberRoleRequest> q) {
    return call(
        q.flatMap(
            v -> {
              var b = v.getBody();
              return api.change(
                      b.getProjectId(),
                      b.getActorUserId(),
                      b.getChangedMemberId(),
                      b.getRole(),
                      "CHANGE")
                  .thenReturn(
                      ChangeProjectMemberRoleResponse.newBuilder()
                          .setProjectId(b.getProjectId())
                          .setChangedMemberId(b.getChangedMemberId())
                          .setRole(b.getRole())
                          .build());
            }));
  }
}
