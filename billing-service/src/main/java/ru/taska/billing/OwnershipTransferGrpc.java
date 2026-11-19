package ru.taska.billing;

import mapper.GrpcExceptionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.ReactorBillingOwnershipTransferServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;

/** Подготовка и завершение — разные message-bound SERVICE мутации. */
@Service
public final class OwnershipTransferGrpc
    extends ReactorBillingOwnershipTransferServiceGrpc.BillingOwnershipTransferServiceImplBase {
  private final ObjectProvider<OwnershipTransferBilling> transfers;

  public OwnershipTransferGrpc(ObjectProvider<OwnershipTransferBilling> transfers) {
    this.transfers = transfers;
  }

  private OwnershipTransferBilling store() {
    var value = transfers.getIfAvailable();
    if (value == null)
      throw new DomainException(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    return value;
  }

  @Override
  public Mono<OwnershipTransferFence> prepare(Mono<OwnershipTransferRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(q -> store().prepare(q)))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<OwnershipTransferFence> finish(Mono<OwnershipTransferRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(q -> store().finish(q)))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
