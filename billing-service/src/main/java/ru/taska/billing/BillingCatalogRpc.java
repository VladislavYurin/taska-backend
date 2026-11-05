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
  private final InvoiceStore invoices;

  public BillingCatalogRpc(
      BillingCatalog catalog, SubscriptionStore subscriptions, InvoiceStore invoices) {
    this.catalog = catalog;
    this.subscriptions = subscriptions;
    this.invoices = invoices;
  }

  private static <T> Mono<T> inbound(Mono<T> work) {
    return RpcContextBridge.inbound(work)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.Entitlement> getEntitlement(
      Mono<ru.taska.api.b2b.v1.GetEntitlementRpcRequest> request) {
    return inbound(request.flatMap(subscriptions.entitlements()::get));
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.Invoice> createInvoice(
      Mono<ru.taska.api.b2b.v1.CreateInvoiceRpcRequest> request) {
    return inbound(request.flatMap(invoices::create));
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.Invoice> getInvoice(
      Mono<ru.taska.api.b2b.v1.GetInvoiceRpcRequest> request) {
    return inbound(request.flatMap(invoices::get));
  }

  @Override
  public Mono<ru.taska.api.b2b.v1.InvoicePage> listInvoices(
      Mono<ru.taska.api.b2b.v1.ListInvoicesRpcRequest> request) {
    return inbound(request.flatMap(invoices::list));
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
