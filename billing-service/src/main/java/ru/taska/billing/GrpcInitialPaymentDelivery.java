package ru.taska.billing;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Billing has already committed. Root rechecks hard cancellation/lifecycle in its own transaction.
 */
public final class GrpcInitialPaymentDelivery
    implements PaymentSettlementDependencies.InitialPaymentDelivery {
  private final ReactorTenantCreationLifecycleServiceGrpc.ReactorTenantCreationLifecycleServiceStub
      stub;
  private final SignedQuotaClient client;

  public GrpcInitialPaymentDelivery(
      ReactorTenantCreationLifecycleServiceGrpc.ReactorTenantCreationLifecycleServiceStub stub,
      RpcContextSecurity security,
      Clock clock) {
    this.stub = stub;
    this.client = new SignedQuotaClient(security, clock, "billing-service", "tenant-service");
  }

  public Mono<TenantInitialPaymentResult> deliver(UUID tenant, UUID decision) {
    return client.call(
        tenant,
        TenantCreationLifecycleServiceGrpc.getApplyTenantInitialPaymentDecisionMethod()
            .getFullMethodName(),
        "applyTenantInitialPaymentDecision",
        new RpcContextSecurity.Policy(true, true),
        c ->
            ApplyTenantInitialPaymentDecisionRequest.newBuilder()
                .setContext(c)
                .setTenantId(tenant.toString())
                .setDecisionId(decision.toString())
                .build(),
        (r, h) ->
            stub.withInterceptors(h)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .applyTenantInitialPaymentDecision(r));
  }
}
