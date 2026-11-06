package ru.taska.billing;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Primary Root lifecycle; signed requests require a supplied mTLS stub/current service authority.
 */
public final class GrpcTenantPaymentState implements PaymentSettlementDependencies.TenantAuthority {
  private final ReactorTenantPaymentStateServiceGrpc.ReactorTenantPaymentStateServiceStub stub;
  private final SignedQuotaClient client;

  public GrpcTenantPaymentState(
      ReactorTenantPaymentStateServiceGrpc.ReactorTenantPaymentStateServiceStub stub,
      RpcContextSecurity security,
      Clock clock) {
    this.stub = stub;
    this.client = new SignedQuotaClient(security, clock, "billing-service", "tenant-service");
  }

  public Mono<TenantPaymentState> current(UUID tenant) {
    return client.call(
        tenant,
        TenantPaymentStateServiceGrpc.getGetTenantPaymentStateMethod().getFullMethodName(),
        "getTenantPaymentState",
        c ->
            GetTenantPaymentStateRequest.newBuilder()
                .setContext(c)
                .setTenantId(tenant.toString())
                .build(),
        (r, h) ->
            stub.withInterceptors(h)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getTenantPaymentState(r));
  }
}
