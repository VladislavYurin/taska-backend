package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantGrpcService extends ReactorTenantServiceGrpc.TenantServiceImplBase {
  private final TenantRegistryService registry;

  public TenantGrpcService(TenantRegistryService registry) {
    this.registry = registry;
  }

  @Override
  public Mono<CreateTenantResponse> createTenant(Mono<CreateTenantRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(registry::create))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Tenant> getTenant(Mono<GetTenantRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(registry::get))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
