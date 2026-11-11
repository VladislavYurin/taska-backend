package ru.taska.capability;

import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.*;
import java.util.*;
import java.util.function.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Narrow primary reads using operator-managed service identities and exact purposes. */
public final class ManagedDomainReads implements AutoCloseable {
  private final ManagedServiceContexts contexts;
  private final RpcContextSecurity security;
  private final Map<String, ManagedChannel> channels;

  public ManagedDomainReads(
      ManagedServiceContexts contexts,
      RpcContextSecurity security,
      Map<String, ManagedChannel> channels) {
    this.contexts = contexts;
    this.security = security;
    this.channels = Map.copyOf(channels);
  }

  public ManagedChannel channel(String audience) {
    var channel = channels.get(audience);
    if (channel == null)
      throw new DomainException(DomainStatus.UNAVAILABLE, "Primary not configured");
    return channel;
  }

  public <Q extends Message, R> Mono<R> service(
      String purpose,
      String audience,
      String rpc,
      String action,
      UUID tenant,
      Set<String> scopes,
      Function<RequestContext, Q> request,
      BiFunction<Q, ClientInterceptor, Mono<R>> send) {
    var policy = new RpcContextSecurity.Policy(tenant != null, false);
    return contexts
        .authorize(purpose, audience, rpc, action, tenant, scopes, policy)
        .flatMap(
            verified -> {
              Q body = request.apply(RpcContextBridge.details(verified));
              return security
                  .sign(verified, body, rpc, policy)
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return send.apply(body, MetadataUtils.newAttachHeadersInterceptor(headers));
                      });
            })
        .timeout(Duration.ofSeconds(5));
  }

  public Mono<MembershipValidationBatch> memberships(UUID tenant, Collection<UUID> users) {
    var ids = users.stream().distinct().map(UUID::toString).toList();
    if (ids.isEmpty() || ids.size() > 100)
      return Mono.error(
          new DomainException(DomainStatus.INVALID_ARGUMENT, "Bounded membership IDs required"));
    return service(
            "membership",
            "tenant-service",
            TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod().getFullMethodName(),
            "batchValidateMemberships",
            tenant,
            Set.of("memberships:read"),
            c ->
                BatchValidateMembershipsRpcRequest.newBuilder()
                    .setContext(c)
                    .setTenantId(tenant.toString())
                    .addAllUserIds(ids)
                    .build(),
            (q, auth) ->
                ReactorTenantMembershipServiceGrpc.newReactorStub(channel("tenant-service"))
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .batchValidateMemberships(q))
        .map(
            result -> {
              if (!tenant.toString().equals(result.getTenantId())
                  || result.getItemsCount() != ids.size()
                  || !new HashSet<>(ids)
                      .equals(
                          result.getItemsList().stream()
                              .map(MembershipValidation::getUserId)
                              .collect(java.util.stream.Collectors.toSet())))
                throw new DomainException(
                    DomainStatus.UNAVAILABLE, "Invalid primary membership result");
              return result;
            });
  }

  public Mono<MembershipValidationBatch> actorMembership(VerifiedTenantContext actor) {
    if (actor.actorId() == null || actor.tenantId() == null) return Mono.error(denied());
    return memberships(UUID.fromString(actor.tenantId()), List.of(UUID.fromString(actor.actorId())))
        .map(
            result -> {
              var row = result.getItems(0);
              var claims = RpcContextBridge.details(actor).getContext();
              if (!row.getFound()
                  || !row.hasMembership()
                  || row.getMembership().getState() != Membership.StateValue.STATE_ACTIVE
                  || !row.getMembership().getVersion().equals(claims.getMembershipGeneration())
                  || !actor.tenantId().equals(row.getMembership().getTenantId())) throw denied();
              return result;
            });
  }

  public Mono<Entitlement> entitlement(UUID tenant, long minimum) {
    return service(
        "billing",
        "billing-service",
        EntitlementServiceGrpc.getGetEntitlementSnapshotMethod().getFullMethodName(),
        "getEntitlementSnapshot",
        tenant,
        Set.of("read"),
        c -> {
          var b =
              GetEntitlementSnapshotRequest.newBuilder()
                  .setContext(c)
                  .setTenantId(tenant.toString());
          if (minimum > 0) b.setMinimumVersion(Long.toString(minimum));
          return b.build();
        },
        (q, auth) ->
            ReactorEntitlementServiceGrpc.newReactorStub(channel("billing-service"))
                .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(auth)
                .getEntitlementSnapshot(q));
  }

  public Mono<PlatformConfigurationSnapshot> configuration() {
    return service(
        "admin",
        "tenant-service",
        PlatformServiceGrpc.getGetPlatformConfigurationSnapshotMethod().getFullMethodName(),
        "getPlatformConfigurationSnapshot",
        null,
        Set.of("read"),
        c -> GetPlatformConfigurationSnapshotRequest.newBuilder().setContext(c).build(),
        (q, auth) ->
            ReactorPlatformServiceGrpc.newReactorStub(channel("tenant-service"))
                .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                .withInterceptors(auth)
                .getPlatformConfigurationSnapshot(q));
  }

  public Mono<CapabilityFacts.PlanKind> plan(String version) {
    return service(
            "billing",
            "billing-service",
            BillingServiceGrpc.getReadReferencedPlanVersionMethod().getFullMethodName(),
            "readReferencedPlanVersion",
            null,
            Set.of("read", "catalog:read"),
            c ->
                ResolvePublishedPlanRpcRequest.newBuilder()
                    .setContext(c)
                    .setPlanVersionId(version)
                    .build(),
            (q, auth) ->
                ReactorBillingServiceGrpc.newReactorStub(channel("billing-service"))
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .readReferencedPlanVersion(q))
        .map(
            plan -> {
              if (!version.equals(plan.getId())
                  || (plan.getState() != PlanVersion.StateValue.STATE_PUBLISHED
                      && plan.getState() != PlanVersion.StateValue.STATE_RETIRED))
                throw new DomainException(DomainStatus.UNAVAILABLE, "Referenced plan unavailable");
              return switch (plan.getKind()) {
                case KIND_FREE -> CapabilityFacts.PlanKind.FREE;
                case KIND_PAID -> CapabilityFacts.PlanKind.PAID;
                default ->
                    throw new DomainException(DomainStatus.UNAVAILABLE, "Plan kind unavailable");
              };
            });
  }

  private static DomainException denied() {
    return new DomainException(
        DomainStatus.PERMISSION_DENIED, "Current company membership required");
  }

  @Override
  public void close() {
    channels.values().forEach(ManagedChannel::shutdownNow);
  }
}
