package ru.taska.billing;

import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CreatePlanVersionRpcRequest;
import ru.taska.api.b2b.v1.GetSubscriptionRpcRequest;
import ru.taska.api.b2b.v1.ListBillingPlansRpcRequest;
import ru.taska.api.b2b.v1.ListSubscriptionPeriodsRpcRequest;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.PlanVersionPage;
import ru.taska.api.b2b.v1.PublishPlanVersionRpcRequest;
import ru.taska.api.b2b.v1.ReactorBillingServiceGrpc;
import ru.taska.api.b2b.v1.ResolvePublishedPlanRpcRequest;
import ru.taska.api.b2b.v1.Subscription;
import ru.taska.api.b2b.v1.SubscriptionPeriodPage;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

@Service
public final class BillingCatalogRpc extends ReactorBillingServiceGrpc.BillingServiceImplBase {
  private final BillingCatalog catalog;

  private final SubscriptionStore subscriptions;

  public BillingCatalogRpc(BillingCatalog catalog, SubscriptionStore subscriptions) {
    this.catalog = catalog;
    this.subscriptions = subscriptions;
  }

  private static <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<Subscription> getSubscription(Mono<GetSubscriptionRpcRequest> request) {
    return inbound(request.flatMap(subscriptions::get));
  }

  @Override
  public Mono<SubscriptionPeriodPage> listSubscriptionPeriods(
      Mono<ListSubscriptionPeriodsRpcRequest> request) {
    return inbound(request.flatMap(subscriptions::periods));
  }

  @Override
  public Mono<PlanVersion> resolvePublishedPlan(Mono<ResolvePublishedPlanRpcRequest> request) {
    return inbound(request.flatMap(catalog::resolve));
  }

  @Override
  public Mono<PlanVersionPage> listBillingPlans(Mono<ListBillingPlansRpcRequest> request) {
    return inbound(request.flatMap(catalog::list));
  }

  @Override
  public Mono<PlanVersion> createPlanVersion(Mono<CreatePlanVersionRpcRequest> request) {
    return inbound(request.flatMap(catalog::create));
  }

  @Override
  public Mono<PlanVersion> publishPlanVersion(Mono<PublishPlanVersionRpcRequest> request) {
    return inbound(request.flatMap(catalog::publish));
  }
}
