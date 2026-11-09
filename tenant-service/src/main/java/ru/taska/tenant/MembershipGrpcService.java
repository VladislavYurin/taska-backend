package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class MembershipGrpcService
    extends ReactorTenantMembershipServiceGrpc.TenantMembershipServiceImplBase {
  private final TenantMembershipService memberships;

  public MembershipGrpcService(TenantMembershipService memberships) {
    this.memberships = memberships;
  }

  @Override
  public Mono<MembershipSnapshot> getMembership(Mono<GetMembershipRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(memberships::get))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<MembershipValidationBatch> batchValidateMemberships(
      Mono<BatchValidateMembershipsRpcRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(memberships::batch))
        .onErrorMap(
            ru.taska.exception.DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
