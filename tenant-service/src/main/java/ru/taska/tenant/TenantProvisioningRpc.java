package ru.taska.tenant;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ReactorTenantProvisioningServiceGrpc;
import ru.taska.api.b2b.v1.ResumeTenantProvisioningRequest;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class TenantProvisioningRpc
    extends ReactorTenantProvisioningServiceGrpc.TenantProvisioningServiceImplBase {
  private final TenantProvisioning provisioning;

  public TenantProvisioningRpc(TenantProvisioning provisioning) {
    this.provisioning = provisioning;
  }

  @Override
  public Mono<Operation> resumeTenantProvisioning(Mono<ResumeTenantProvisioningRequest> request) {
    return RpcContextBridge.inbound(
            request.flatMap(
                r ->
                    provisioning.resume(
                        TenantRegistryService.uuid(r.getTenantId()),
                        TenantRegistryService.uuid(r.getOperationId()))))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
