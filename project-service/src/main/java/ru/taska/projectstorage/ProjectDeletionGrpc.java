package ru.taska.projectstorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.DeleteProjectRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ProjectDeletionProof;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ReactorProjectDeletionServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
public final class ProjectDeletionGrpc
    extends ReactorProjectDeletionServiceGrpc.ProjectDeletionServiceImplBase {
  private final ProjectDeletions deletions;

  public ProjectDeletionGrpc(ProjectDeletions deletions) {
    this.deletions = deletions;
  }

  @Override
  public Mono<Operation> deleteProject(Mono<DeleteProjectRpcRequest> q) {
    return errors(q.flatMap(deletions::start));
  }

  @Override
  public Mono<Operation> getDeletion(Mono<ProjectDeletionRequest> q) {
    return errors(
        q.flatMap(
            r ->
                r.getProjectId().isEmpty()
                    ? deletions.get(TenantProjectApi.uuid(r.getOperationId()))
                    : deletions.get(
                        TenantProjectApi.uuid(r.getProjectId()),
                        TenantProjectApi.uuid(r.getOperationId()))));
  }

  @Override
  public Mono<ProjectDeletionProof> inspectDeletion(Mono<ProjectDeletionRequest> q) {
    return errors(
        q.flatMap(
            r ->
                deletions.inspect(
                    TenantProjectApi.uuid(r.getProjectId()),
                    TenantProjectApi.uuid(r.getOperationId()))));
  }

  @Override
  public Mono<Operation> recoverDeletion(Mono<ProjectDeletionRequest> q) {
    return errors(
        q.flatMap(
            r ->
                deletions
                    .inspect(
                        TenantProjectApi.uuid(r.getProjectId()),
                        TenantProjectApi.uuid(r.getOperationId()))
                    .then(deletions.recover(TenantProjectApi.uuid(r.getOperationId())))));
  }

  private static <T> Mono<T> errors(Mono<T> m) {
    return RpcContextBridge.inbound(m)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
