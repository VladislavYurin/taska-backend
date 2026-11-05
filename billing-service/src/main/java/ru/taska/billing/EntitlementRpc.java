package ru.taska.billing;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.GetEntitlementSnapshotRequest;
import ru.taska.api.b2b.v1.ReactorEntitlementServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class EntitlementRpc extends ReactorEntitlementServiceGrpc.EntitlementServiceImplBase {
  private final EntitlementStore entitlements;

  public EntitlementRpc(EntitlementStore entitlements) {
    this.entitlements = entitlements;
  }

  @Override
  public Mono<Entitlement> getEntitlementSnapshot(Mono<GetEntitlementSnapshotRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(entitlements::getSnapshot))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
