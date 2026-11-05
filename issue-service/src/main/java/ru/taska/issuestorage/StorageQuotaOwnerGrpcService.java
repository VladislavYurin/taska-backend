package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.StorageQuotaWire;
import ru.taska.security.tenant.RpcContextBridge;

@Service
@Profile("tenant-storage")
public final class StorageQuotaOwnerGrpcService
    extends ReactorStorageQuotaOwnerServiceGrpc.StorageQuotaOwnerServiceImplBase {
  private final StorageQuotaOwner owner;

  public StorageQuotaOwnerGrpcService(StorageQuotaOwner owner) {
    this.owner = owner;
  }

  @Override
  public Mono<StorageQuotaOwnerReceipt> inspect(Mono<StorageQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(
                    q ->
                        owner
                            .inspect(StorageQuotaWire.decode(q.getReservation()))
                            .switchIfEmpty(
                                Mono.error(
                                    new DomainException(
                                        DomainStatus.NOT_FOUND, "OWNER_RECEIPT_NOT_FOUND"))))
                .map(StorageQuotaWire::encode)));
  }

  @Override
  public Mono<StorageQuotaOwnerReceipt> cancel(Mono<StorageQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(q -> owner.cancel(StorageQuotaWire.decode(q.getReservation())))
                .map(StorageQuotaWire::encode)));
  }

  @Override
  public Mono<StorageQuotaOwnerReceipt> activate(Mono<StorageQuotaOwnerRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(q -> owner.activate(StorageQuotaWire.decode(q.getReservation())))
                .map(StorageQuotaWire::encode)));
  }

  @Override
  public Mono<StorageQuotaManifestPage> manifests(Mono<StorageQuotaManifestRequest> r) {
    return errors(
        RpcContextBridge.inbound(
            r.flatMap(
                    q -> {
                      long version;
                      try {
                        version = Long.parseLong(q.getSnapshotVersion());
                        if (version < 0 || !Long.toString(version).equals(q.getSnapshotVersion()))
                          throw new IllegalArgumentException();
                      } catch (RuntimeException e) {
                        throw ru.taska.quota.ProjectQuotaWire.invalid();
                      }
                      return owner.manifests(
                          ru.taska.quota.ProjectQuotaWire.uuid(q.getTenantId()),
                          q.hasAfter() ? ru.taska.quota.ProjectQuotaWire.uuid(q.getAfter()) : null,
                          version);
                    })
                .map(StorageQuotaWire::encode)));
  }

  private static <T> Mono<T> errors(Mono<T> work) {
    return work.onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
