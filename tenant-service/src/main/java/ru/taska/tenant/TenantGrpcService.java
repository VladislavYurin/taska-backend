package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantGrpcService extends ReactorTenantServiceGrpc.TenantServiceImplBase {
  private final TenantRegistryService registry;
  private final TenantMembershipService memberships;
  private final TenantMembershipMutationService mutations;

  public TenantGrpcService(TenantRegistryService registry, TenantMembershipService memberships,
      TenantMembershipMutationService mutations) {
    this.registry = registry;
    this.memberships = memberships;
    this.mutations = mutations;
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

  @Override
  public Mono<MembershipPage> listTenantMembers(Mono<ListTenantMembersRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(memberships::list))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Membership> setTenantMemberRole(Mono<SetTenantMemberRoleRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(mutations::setRole))
        .onErrorMap(ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Operation> leaveTenant(Mono<LeaveTenantRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(mutations::leave))
        .onErrorMap(ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
