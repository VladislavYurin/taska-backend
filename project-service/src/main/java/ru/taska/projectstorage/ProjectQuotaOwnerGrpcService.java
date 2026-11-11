package ru.taska.projectstorage;

import mapper.GrpcExceptionMapper;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaWire;
import ru.taska.security.tenant.RpcContextBridge;

@org.springframework.grpc.server.service.GrpcService
@Profile("tenant-storage")
public class ProjectQuotaOwnerGrpcService
    extends ReactorProjectQuotaOwnerServiceGrpc.ProjectQuotaOwnerServiceImplBase {
  private final ProjectQuotaOwner owner;

  public ProjectQuotaOwnerGrpcService(ProjectQuotaOwner owner) {
    this.owner = owner;
  }

  @Override
  public Mono<ProjectQuotaOwnerReceipt> inspect(Mono<ProjectQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(
                    q ->
                        owner
                            .inspect(ProjectQuotaWire.decode(q.getReservation()))
                            .switchIfEmpty(
                                Mono.error(
                                    new DomainException(
                                        DomainStatus.NOT_FOUND, "OWNER_RECEIPT_NOT_FOUND"))))
                .map(ProjectQuotaWire::encode)));
  }

  @Override
  public Mono<ProjectQuotaOwnerReceipt> cancel(Mono<ProjectQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(q -> owner.cancel(ProjectQuotaWire.decode(q.getReservation())))
                .map(ProjectQuotaWire::encode)));
  }

  @Override
  public Mono<ProjectQuotaOwnerReceipt> open(Mono<ProjectQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(q -> owner.open(ProjectQuotaWire.decode(q.getReservation())))
                .map(ProjectQuotaWire::encode)));
  }

  @Override
  public Mono<ProjectQuotaOwnerInventory> inventory(Mono<ProjectQuotaOwnerInventoryRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(
                    q ->
                        owner.inventory(
                            ProjectQuotaWire.uuid(q.getTenantId()),
                            q.hasAcceptedReservation()
                                ? ProjectQuotaWire.decode(q.getAcceptedReservation())
                                : null))
                .map(ProjectQuotaWire::encode)));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
