package ru.taska.projectstorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.quota.ProjectQuotaWire;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
public class ProjectInitializationGrpc
    extends ReactorProjectInitializationServiceGrpc.ProjectInitializationServiceImplBase {
  private final ProjectCreation creation;

  public ProjectInitializationGrpc(ProjectCreation creation) {
    this.creation = creation;
  }

  @Override
  public Mono<ProjectInitializationDescriptor> describe(Mono<ProjectInitializationRequest> q) {
    return RpcContextBridge.inbound(
            q.flatMap(v -> creation.describe(ProjectQuotaWire.decode(v.getReservation()))))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<ProjectResponse> recover(Mono<RecoverProjectCreationRequest> q) {
    return RpcContextBridge.inbound(
            q.flatMap(v -> creation.recover(ProjectQuotaWire.uuid(v.getOperationId()))))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
