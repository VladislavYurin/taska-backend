package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.OwnershipTransferReceipt;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.ReactorTenantOwnershipTransferServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;

/** Внутренний receipt не является публичным разрешением на смену владельца. */
@Service
public final class OwnershipTransferGrpc
    extends ReactorTenantOwnershipTransferServiceGrpc.TenantOwnershipTransferServiceImplBase {
  private final ObjectProvider<TenantOwnershipTransfers> transfers;

  public OwnershipTransferGrpc(ObjectProvider<TenantOwnershipTransfers> transfers) {
    this.transfers = transfers;
  }

  private TenantOwnershipTransfers store() {
    var value = transfers.getIfAvailable();
    if (value == null)
      throw new DomainException(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    return value;
  }

  @Override
  public Mono<OwnershipTransferReceipt> inspect(Mono<OwnershipTransferRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(q -> store().inspect(q)))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<OwnershipTransferReceipt> recover(Mono<OwnershipTransferRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(q -> store().recover(q)))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
