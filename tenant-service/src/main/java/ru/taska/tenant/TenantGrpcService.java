package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CreateTenantResponse;
import ru.taska.api.b2b.v1.CreateTenantRpcRequest;
import ru.taska.api.b2b.v1.GetTenantOperationRpcRequest;
import ru.taska.api.b2b.v1.GetTenantRpcRequest;
import ru.taska.api.b2b.v1.ListTenantMembersRpcRequest;
import ru.taska.api.b2b.v1.MembershipPage;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ReactorTenantServiceGrpc;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantGrpcService extends ReactorTenantServiceGrpc.TenantServiceImplBase {
  private final TenantRegistryService registry;
  private final TenantMembershipService memberships;

  public TenantGrpcService(TenantRegistryService registry, TenantMembershipService memberships) {
    this.registry = registry;
    this.memberships = memberships;
  }

  @Override
  public Mono<CreateTenantResponse> createTenant(Mono<CreateTenantRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(registry::create))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Operation> getTenantOperation(Mono<GetTenantOperationRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(registry::getOperation))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Tenant> cancelTenantCreation(
      Mono<ru.taska.api.b2b.v1.CancelTenantCreationRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(registry::cancelCreation))
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

  @Override
  public Mono<MembershipPage> listTenantMembers(Mono<ListTenantMembersRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(memberships::list))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
