package ru.taska.tenant;

import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.*;
import java.util.*;
import java.util.function.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.CapabilityFacts.PlanKind;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Purpose-bound primary reads. No user credential is forwarded to billing or configuration. */
public final class TenantCapabilitySources implements AutoCloseable {
  private final ManagedChannel channel;
  private final ManagedServiceContexts contexts;
  private final RpcContextSecurity security;
  private final PlatformFlags flags;

  public TenantCapabilitySources(
      ManagedChannel channel,
      ManagedServiceContexts contexts,
      RpcContextSecurity security,
      PlatformFlags flags) {
    this.channel = channel;
    this.contexts = contexts;
    this.security = security;
    this.flags = flags;
  }

  private <Q extends Message, R> Mono<R> billing(
      String rpc,
      String action,
      UUID tenant,
      Function<RequestContext, Q> build,
      BiFunction<Q, Metadata, Mono<R>> invoke) {
    var policy = new RpcContextSecurity.Policy(tenant != null, false);
    return contexts
        .authorize(
            "billing",
            "billing-service",
            rpc,
            action,
            tenant,
            (action.equals("readReferencedPlanVersion")
                ? Set.of("read", "catalog:read")
                : Set.of("read")),
            policy)
        .flatMap(
            verified -> {
              Q request = build.apply(RpcContextBridge.details(verified));
              return security
                  .sign(verified, request, rpc, policy)
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return invoke.apply(request, headers);
                      });
            });
  }

  public Mono<Entitlement> current(UUID tenant, long minimum) {
    return billing(
        EntitlementServiceGrpc.getGetEntitlementSnapshotMethod().getFullMethodName(),
        "getEntitlementSnapshot",
        tenant,
        context -> {
          var r =
              GetEntitlementSnapshotRequest.newBuilder()
                  .setContext(context)
                  .setTenantId(tenant.toString());
          if (minimum > 0) r.setMinimumVersion(Long.toString(minimum));
          return r.build();
        },
        (request, headers) ->
            ReactorEntitlementServiceGrpc.newReactorStub(channel)
                .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .getEntitlementSnapshot(request));
  }

  public Mono<PlanKind> plan(String version) {
    return billing(
            BillingServiceGrpc.getReadReferencedPlanVersionMethod().getFullMethodName(),
            "readReferencedPlanVersion",
            null,
            context ->
                ResolvePublishedPlanRpcRequest.newBuilder()
                    .setContext(context)
                    .setPlanVersionId(version)
                    .build(),
            (request, headers) ->
                ReactorBillingServiceGrpc.newReactorStub(channel)
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .readReferencedPlanVersion(request))
        .map(
            plan -> {
              if (!version.equals(plan.getId())
                  || (plan.getState() != PlanVersion.StateValue.STATE_PUBLISHED
                      && plan.getState() != PlanVersion.StateValue.STATE_RETIRED))
                throw new DomainException(DomainStatus.UNAVAILABLE, "Catalog version unavailable");
              return switch (plan.getKind()) {
                case KIND_FREE -> PlanKind.FREE;
                case KIND_PAID -> PlanKind.PAID;
                default ->
                    throw new DomainException(DomainStatus.UNAVAILABLE, "Catalog kind unavailable");
              };
            });
  }

  public Mono<PlatformConfigurationSnapshot> configuration() {
    return contexts
        .authorize(
            "admin",
            "tenant-service",
            PlatformServiceGrpc.getGetPlatformConfigurationSnapshotMethod().getFullMethodName(),
            "getPlatformConfigurationSnapshot",
            null,
            Set.of("read"),
            new RpcContextSecurity.Policy(false, false))
        .flatMap(verified -> RpcContextBridge.install(flags.snapshot(), verified));
  }

  public void close() {
    channel.shutdownNow();
  }
}
