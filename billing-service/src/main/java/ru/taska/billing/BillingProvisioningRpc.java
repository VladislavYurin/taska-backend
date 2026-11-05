package ru.taska.billing;

import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.InitializeSubscriptionRequest;
import ru.taska.api.b2b.v1.ProvisionedBillingAccount;
import ru.taska.api.b2b.v1.ReactorBillingProvisioningServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class BillingProvisioningRpc
    extends ReactorBillingProvisioningServiceGrpc.BillingProvisioningServiceImplBase {
  private final SubscriptionStore subscriptions;

  public BillingProvisioningRpc(SubscriptionStore subscriptions) {
    this.subscriptions = subscriptions;
  }

  @Override
  public Mono<ProvisionedBillingAccount> initializeSubscription(
      Mono<InitializeSubscriptionRequest> request) {
    return RpcContextBridge.inbound(
            request.flatMap(
                r ->
                    subscriptions.provision(
                        canonical(r.getTenantId()),
                        canonical(r.getPlanVersionId()),
                        canonical(r.getOperationId()))))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static UUID canonical(String value) {
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw CatalogErrors.invalid();
    }
  }
}
