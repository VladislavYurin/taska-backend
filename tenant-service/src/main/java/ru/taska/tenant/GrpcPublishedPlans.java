package ru.taska.tenant;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.BillingServiceGrpc;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.api.b2b.v1.ReactorBillingServiceGrpc.ReactorBillingServiceStub;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.ResolvePublishedPlanRpcRequest;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.api.common.v1.Header;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextSecurity;

/**
 * Resolve a current public offer before accepting a creation; replay uses durable registry facts.
 */
public final class GrpcPublishedPlans implements RegistryDependencies.PublishedPlans {
  private final ReactorBillingServiceStub billing;
  private final RpcContextSecurity security;
  private final Clock clock;

  public GrpcPublishedPlans(
      ReactorBillingServiceStub billing, RpcContextSecurity security, Clock clock) {
    this.billing = billing;
    this.security = security;
    this.clock = clock;
  }

  @Override
  public Mono<RegistryDependencies.PublishedPlan> resolve(UUID plan) {
    return Mono.defer(
        () -> {
          var now = clock.instant();
          var context =
              RequestContext.newBuilder()
                  .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                  .setContext(
                      TrustedTenantContext.newBuilder()
                          .setContextVersion(1)
                          .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                          .setActorGeneration("service-v1")
                          .setIssuerService("tenant-service")
                          .setAudienceService("billing-service")
                          .setAction("resolvePublishedPlan")
                          .addScopes("read")
                          .addScopes("catalog:resolve")
                          .setIssuedAt(now.toString())
                          .setExpiresAt(now.plusSeconds(30).toString())
                          .setAssertionId(UUID.randomUUID().toString()))
                  .build();
          var request =
              ResolvePublishedPlanRpcRequest.newBuilder()
                  .setContext(context)
                  .setPlanVersionId(plan.toString())
                  .build();
          String rpc = BillingServiceGrpc.getResolvePublishedPlanMethod().getFullMethodName();
          var policy = new RpcContextSecurity.Policy(false, false);
          return security
              .authorize(context, rpc, policy)
              .flatMap(v -> security.sign(v, request, rpc, policy))
              .flatMap(
                  assertion -> {
                    var headers = new Metadata();
                    headers.put(
                        Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                        assertion);
                    return billing
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .resolvePublishedPlan(request);
                  })
              .onErrorMap(
                  e ->
                      io.grpc.Status.fromThrowable(e).getCode() == io.grpc.Status.Code.NOT_FOUND
                          ? new ru.taska.exception.DomainException(
                              DomainStatus.INVALID_ARGUMENT,
                              "VALIDATION_FAILED",
                              java.util.Map.of(
                                  "code", "VALIDATION_FAILED", "field", "planVersionId"))
                          : e)
              .switchIfEmpty(
                  Mono.error(
                      TenantRegistryService.fail(
                          DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
              .flatMap(
                  response ->
                      !response.getId().equals(plan.toString())
                              || response.getState() != PlanVersion.StateValue.STATE_PUBLISHED
                              || !java.util.Set.of(
                                      PlanVersion.KindValue.KIND_FREE,
                                      PlanVersion.KindValue.KIND_PAID)
                                  .contains(response.getKind())
                          ? Mono.error(
                              TenantRegistryService.fail(
                                  DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))
                          : Mono.just(
                              new RegistryDependencies.PublishedPlan(
                                  plan, response.getKind() == PlanVersion.KindValue.KIND_FREE)));
        });
  }
}
