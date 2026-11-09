package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantPaymentStateGrpc
    extends ReactorTenantPaymentStateServiceGrpc.TenantPaymentStateServiceImplBase {
  private final TenantCreationLifecycle lifecycle;

  public TenantPaymentStateGrpc(TenantCreationLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public Mono<TenantPaymentState> getTenantPaymentState(
      Mono<GetTenantPaymentStateRequest> request) {
    return RpcContextBridge.inbound(
            request.flatMap(
                r -> lifecycle.paymentState(TenantRegistryService.uuid(r.getTenantId()))))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
