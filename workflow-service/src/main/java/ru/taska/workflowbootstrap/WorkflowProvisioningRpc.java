package ru.taska.workflowbootstrap;

import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ProvisionTenantWorkflowRequest;
import ru.taska.api.b2b.v1.ProvisionTenantWorkflowResponse;
import ru.taska.api.b2b.v1.ReactorWorkflowProvisioningServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.workflowstorage.TenantWorkflowStore;

@Service
@Profile("tenant-storage")
public final class WorkflowProvisioningRpc
    extends ReactorWorkflowProvisioningServiceGrpc.WorkflowProvisioningServiceImplBase {
  private final TenantWorkflowStore store;

  public WorkflowProvisioningRpc(TenantWorkflowStore store) {
    this.store = store;
  }

  public Mono<ProvisionTenantWorkflowResponse> provision(ProvisionTenantWorkflowRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = canonical(request.getTenantId()),
              operation = canonical(request.getOperationId()),
              template = canonical(request.getTemplateId());
          return VerifiedTenantContext.current()
              .flatMap(
                  v -> {
                    var claims = RpcContextBridge.details(v).getContext();
                    if (!tenant.toString().equals(v.tenantId())
                        || !"provisionTenantWorkflow".equals(v.action())
                        || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                        || !"tenant-service".equals(claims.getIssuerService())
                        || !v.scopes().contains("write"))
                      return Mono.error(
                          new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                    return store
                        .execute(s -> s.provision(operation, template))
                        .map(
                            copy ->
                                ProvisionTenantWorkflowResponse.newBuilder()
                                    .setTemplateCopyId(copy.id().toString())
                                    .build());
                  });
        });
  }

  @Override
  public Mono<ProvisionTenantWorkflowResponse> provisionTenantWorkflow(
      Mono<ProvisionTenantWorkflowRequest> request) {
    return RpcContextBridge.inbound(request.flatMap(this::provision))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static UUID canonical(String value) {
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }
}
