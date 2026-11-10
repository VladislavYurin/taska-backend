package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.RpcContextBridge;

@GrpcService
public class TenantCapabilitiesGrpc
    extends ReactorTenantCapabilitiesServiceGrpc.TenantCapabilitiesServiceImplBase {
  private final ObjectProvider<TenantCapabilities> capabilities;

  public TenantCapabilitiesGrpc(ObjectProvider<TenantCapabilities> capabilities) {
    this.capabilities = capabilities;
  }

  @Override
  public Mono<Capabilities> getTenantCapabilities(Mono<GetTenantCapabilitiesRpcRequest> request) {
    return RpcContextBridge.inbound(
            request.flatMap(
                r -> {
                  var service = capabilities.getIfAvailable();
                  return service == null
                      ? Mono.error(
                          new DomainException(
                              DomainStatus.UNAVAILABLE, "Current capability sources unavailable"))
                      : service.get(r.getTenantId());
                }))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
