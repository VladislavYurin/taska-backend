package ru.taska.tenant;

import static ru.taska.quota.ProjectQuotaWire.*;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol.*;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class ProjectQuotaGrpcService
    extends ReactorQuotaAuthorityServiceGrpc.QuotaAuthorityServiceImplBase {
  private final ProjectQuotaStore store;

  private final StorageQuotaStore storage;

  @org.springframework.beans.factory.annotation.Autowired
  public ProjectQuotaGrpcService(ProjectQuotaStore store, StorageQuotaStore storage) {
    this.store = store;
    this.storage = storage;
  }

  public ProjectQuotaGrpcService(ProjectQuotaStore store) {
    this(store, null);
  }

  private StorageQuotaStore storage() {
    if (storage == null)
      throw new DomainException(DomainStatus.UNAVAILABLE, "STORAGE_NOT_CONFIGURED");
    return storage;
  }

  @Override
  public Mono<QuotaReservation> reserveQuota(Mono<ReserveQuotaRpcRequest> requests) {
    return errors(
        RpcContextBridge.inbound(
            requests.flatMap(
                r -> {
                  var p = r.getPayload();
                  if (p.getKind() == ReserveQuotaRequest.KindValue.KIND_STORAGE_BYTES) {
                    if (!p.hasProjectId() || !p.hasIssueId()) return Mono.error(invalid());
                    return storage()
                        .reserve(
                            new ru.taska.quota.StorageQuotaProtocol.Request(
                                uuid(p.getOperationId()),
                                uuid(p.getResourceId()),
                                uuid(p.getProjectId()),
                                uuid(p.getIssueId()),
                                number(p.getQuantity()),
                                instant(p.getExpiresAt())))
                        .map(ru.taska.quota.StorageQuotaWire::encode);
                  }
                  if (p.hasIssueId()
                      || p.getKind() != ReserveQuotaRequest.KindValue.KIND_PROJECT
                      || !"1".equals(p.getQuantity())
                      || p.hasProjectId()) return Mono.error(invalid());
                  return store
                      .reserve(
                          uuid(p.getOperationId()),
                          uuid(p.getResourceId()),
                          instant(p.getExpiresAt()))
                      .map(ru.taska.quota.ProjectQuotaWire::encode);
                })));
  }

  @Override
  public Mono<QuotaReservation> getQuotaReservation(Mono<GetQuotaReservationRequest> requests) {
    return errors(
        RpcContextBridge.inbound(
            requests.flatMap(
                r -> {
                  if (r.getKind() == QuotaReservation.KindValue.KIND_STORAGE_BYTES)
                    return storage()
                        .get(uuid(r.getOperationId()))
                        .map(ru.taska.quota.StorageQuotaWire::encode);
                  if (r.getKind() != QuotaReservation.KindValue.KIND_UNSPECIFIED
                      && r.getKind() != QuotaReservation.KindValue.KIND_PROJECT)
                    return Mono.error(invalid());
                  return store
                      .get(uuid(r.getOperationId()))
                      .map(ru.taska.quota.ProjectQuotaWire::encode);
                })));
  }

  @Override
  public Mono<QuotaReservation> commitQuota(Mono<FinalizeQuotaRpcRequest> requests) {
    return finalize(requests, true);
  }

  @Override
  public Mono<QuotaReservation> releaseQuota(Mono<FinalizeQuotaRpcRequest> requests) {
    return finalize(requests, false);
  }

  private Mono<QuotaReservation> finalize(Mono<FinalizeQuotaRpcRequest> requests, boolean commit) {
    return errors(
        RpcContextBridge.inbound(
            requests.flatMap(
                r -> {
                  if (r.getKind() == QuotaReservation.KindValue.KIND_STORAGE_BYTES) {
                    if (r.getPayload().hasActualQuantity())
                      return Mono.error(invalid()); // Primary owner supplies actual bytes.
                    return storage()
                        .get(uuid(r.getPayload().getOperationId()))
                        .flatMap(
                            current -> {
                              if (!current.id().equals(uuid(r.getReservationId()))
                                  || !current
                                      .resource()
                                      .equals(uuid(r.getPayload().getResourceId()))
                                  || current.fence() != number(r.getFence()))
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.FAILED_PRECONDITION, "STALE_FENCE"));
                              return commit
                                  ? storage().commit(current)
                                  : storage().release(current);
                            })
                        .map(ru.taska.quota.StorageQuotaWire::encode);
                  }
                  if (r.getKind() != QuotaReservation.KindValue.KIND_UNSPECIFIED
                      && r.getKind() != QuotaReservation.KindValue.KIND_PROJECT)
                    return Mono.error(invalid());
                  if (r.getPayload().hasActualQuantity()
                      && !"1".equals(r.getPayload().getActualQuantity()))
                    return Mono.error(invalid());
                  return store
                      .get(uuid(r.getPayload().getOperationId()))
                      .flatMap(
                          current -> {
                            if (!current.id().equals(uuid(r.getReservationId()))
                                || !current.resource().equals(uuid(r.getPayload().getResourceId()))
                                || current.fence() != number(r.getFence()))
                              return Mono.error(
                                  new DomainException(
                                      DomainStatus.FAILED_PRECONDITION, "STALE_FENCE"));
                            return commit ? store.commit(current) : store.release(current);
                          })
                      .map(ru.taska.quota.ProjectQuotaWire::encode);
                })));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
