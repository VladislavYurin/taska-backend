package ru.taska.tenant;

import com.google.protobuf.Message;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.BillingProvisioningServiceGrpc;
import ru.taska.api.b2b.v1.InitializeSubscriptionRequest;
import ru.taska.api.b2b.v1.ProvisionTenantWorkflowRequest;
import ru.taska.api.b2b.v1.ProvisionedBillingAccount;
import ru.taska.api.b2b.v1.ReactorBillingProvisioningServiceGrpc.ReactorBillingProvisioningServiceStub;
import ru.taska.api.b2b.v1.ReactorWorkflowProvisioningServiceGrpc.ReactorWorkflowProvisioningServiceStub;
import ru.taska.api.b2b.v1.WorkflowProvisioningServiceGrpc;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.ProvisioningAuthority;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Supplied stubs must use mTLS. Both services independently enforce signed service-only commands.
 */
public final class GrpcProvisioningDependencies implements TenantProvisioning.Dependencies {
  private final ReactorBillingProvisioningServiceStub billing;
  private final ReactorWorkflowProvisioningServiceStub workflow;
  private final RpcContextSecurity security;
  private final Clock clock;

  public GrpcProvisioningDependencies(
      ReactorBillingProvisioningServiceStub billing,
      ReactorWorkflowProvisioningServiceStub workflow,
      RpcContextSecurity security,
      Clock clock) {
    this.billing = billing;
    this.workflow = workflow;
    this.security = security;
    this.clock = clock;
  }

  private Mono<Metadata> headers(Message request, String rpc) {
    var context = RpcContextSecurity.context(request);
    var policy = new RpcContextSecurity.Policy(true, true);
    return security
        .authorize(context, rpc, policy)
        .flatMap(v -> security.sign(v, request, rpc, policy))
        .map(
            assertion -> {
              var headers = new Metadata();
              headers.put(
                  Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER), assertion);
              return headers;
            });
  }

  @Override
  public Mono<Void> workflow(UUID tenant, UUID operation, UUID template) {
    return Mono.defer(
        () -> {
          var request =
              ProvisionTenantWorkflowRequest.newBuilder()
                  .setContext(
                      ProvisioningAuthority.context(
                          tenant, "workflow-service", "provisionTenantWorkflow", clock))
                  .setTenantId(tenant.toString())
                  .setOperationId(operation.toString())
                  .setTemplateId(template.toString())
                  .build();
          return headers(
                  request,
                  WorkflowProvisioningServiceGrpc.getProvisionTenantWorkflowMethod()
                      .getFullMethodName())
              .flatMap(
                  h ->
                      workflow
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h))
                          .provisionTenantWorkflow(request))
              .switchIfEmpty(
                  Mono.error(
                      TenantRegistryService.fail(
                          DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
              .doOnNext(response -> TenantRegistryService.uuid(response.getTemplateCopyId()))
              .then();
        });
  }

  @Override
  public Mono<Void> billing(UUID tenant, UUID operation, UUID plan, boolean free) {
    return Mono.defer(
        () -> {
          var request =
              InitializeSubscriptionRequest.newBuilder()
                  .setContext(
                      ProvisioningAuthority.context(
                          tenant, "billing-service", "initializeSubscription", clock))
                  .setTenantId(tenant.toString())
                  .setOperationId(operation.toString())
                  .setPlanVersionId(plan.toString())
                  .build();
          return headers(
                  request,
                  BillingProvisioningServiceGrpc.getInitializeSubscriptionMethod()
                      .getFullMethodName())
              .flatMap(
                  h ->
                      billing
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h))
                          .initializeSubscription(request))
              .switchIfEmpty(
                  Mono.error(
                      TenantRegistryService.fail(
                          DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
              .flatMap(
                  response ->
                      response.getTenantId().equals(tenant.toString())
                              && response.getPlanVersionId().equals(plan.toString())
                              && response.getMode()
                                  == (free
                                      ? ProvisionedBillingAccount.Mode.MODE_FREE
                                      : ProvisionedBillingAccount.Mode.MODE_AWAITING_PAYMENT)
                          ? Mono.<Void>empty()
                          : Mono.error(
                              TenantRegistryService.fail(
                                  DomainStatus.FAILED_PRECONDITION, "INVALID_STATE")));
        });
  }
}
