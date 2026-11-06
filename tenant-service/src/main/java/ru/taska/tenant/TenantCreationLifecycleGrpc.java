package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantCreationLifecycleGrpc
    extends ReactorTenantCreationLifecycleServiceGrpc.TenantCreationLifecycleServiceImplBase {
  private final TenantCreationLifecycle lifecycle;

  public TenantCreationLifecycleGrpc(TenantCreationLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public Mono<Tenant> expireTenantCreation(Mono<ExpireTenantCreationRequest> request) {
    return inbound(
        request.flatMap(r -> lifecycle.expire(TenantRegistryService.uuid(r.getTenantId()))));
  }

  @Override
  public Mono<TenantInitialPaymentResult> applyTenantInitialPaymentDecision(
      Mono<ApplyTenantInitialPaymentDecisionRequest> request) {
    return inbound(request.flatMap(lifecycle::apply));
  }

  private static <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
