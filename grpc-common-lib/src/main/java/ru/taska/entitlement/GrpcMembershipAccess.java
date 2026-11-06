package ru.taska.entitlement;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Reads primary tenant authority on every invocation, independently of billing/cache freshness. */
public final class GrpcMembershipAccess implements EntitlementProjection.Access {
  public interface Policy {
    /**
     * Check current channel/grant, domain ACL and lifecycle; return independent pause/block
     * reasons.
     */
    Mono<Set<RestrictionCode>> current(
        VerifiedTenantContext actor,
        MembershipSnapshot membership,
        Tenant.LifecycleValue lifecycle,
        EntitlementProjection.Use use);
  }

  private final ReactorTenantMembershipServiceGrpc.ReactorTenantMembershipServiceStub stub;
  private final RpcContextSecurity security;
  private final Clock clock;
  private final String issuer;
  private final Policy policy;

  public GrpcMembershipAccess(
      ReactorTenantMembershipServiceGrpc.ReactorTenantMembershipServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer,
      Policy policy) {
    this.stub = Objects.requireNonNull(stub);
    this.security = Objects.requireNonNull(security);
    this.clock = Objects.requireNonNull(clock);
    this.issuer = Objects.requireNonNull(issuer);
    this.policy = Objects.requireNonNull(policy);
  }

  @Override
  public Mono<Set<RestrictionCode>> current(
      VerifiedTenantContext actor, EntitlementProjection.Use use) {
    return VerifiedTenantContext.current()
        .flatMap(
            installed -> {
              if (actor != installed || actor.actorId() == null || actor.tenantId() == null)
                return Mono.error(denied());
              var now = clock.instant();
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(
                          TrustedTenantContext.newBuilder()
                              .setContextVersion(1)
                              .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                              .setTenantId(actor.tenantId())
                              .setActorGeneration("service-v1")
                              .setIssuerService(issuer)
                              .setAudienceService("tenant-service")
                              .setAction("batchValidateMemberships")
                              .addScopes("memberships:read")
                              .setIssuedAt(now.toString())
                              .setExpiresAt(now.plusSeconds(30).toString())
                              .setAssertionId(UUID.randomUUID().toString()))
                      .build();
              var request =
                  BatchValidateMembershipsRpcRequest.newBuilder()
                      .setContext(context)
                      .setTenantId(actor.tenantId())
                      .addUserIds(actor.actorId())
                      .build();
              String rpc =
                  TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod()
                      .getFullMethodName();
              var transportPolicy = new RpcContextSecurity.Policy(true, false);
              return security
                  .authorize(context, rpc, transportPolicy)
                  .flatMap(verified -> security.sign(verified, request, rpc, transportPolicy))
                  .flatMap(
                      assertion -> {
                        var metadata = new Metadata();
                        metadata.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                            .batchValidateMemberships(Mono.just(request));
                      })
                  .switchIfEmpty(
                      Mono.error(
                          new DomainException(DomainStatus.UNAVAILABLE, "Membership unavailable")))
                  .flatMap(
                      result -> {
                        if (!actor.tenantId().equals(result.getTenantId())
                            || result.getItemsCount() != 1
                            || !actor.actorId().equals(result.getItems(0).getUserId())
                            || !result.getItems(0).getFound()
                            || !result.getItems(0).hasMembership()) return Mono.error(denied());
                        var member = result.getItems(0).getMembership();
                        if (!actor.tenantId().equals(member.getTenantId())
                            || !actor.actorId().equals(member.getUserId())
                            || member.getState() != Membership.StateValue.STATE_ACTIVE
                            || member.getRole() == Membership.RoleValue.ROLE_UNSPECIFIED
                            || member.getRole() == Membership.RoleValue.UNRECOGNIZED
                            || result.getTenantLifecycle()
                                == Tenant.LifecycleValue.LIFECYCLE_UNSPECIFIED
                            || result.getTenantLifecycle()
                                == Tenant.LifecycleValue.LIFECYCLE_DELETED
                            || result.getTenantLifecycle() == Tenant.LifecycleValue.UNRECOGNIZED
                            || !member.getVersion().matches("[1-9][0-9]*")
                            || !result
                                .getTenantMembershipVersion()
                                .equals(member.getTenantMembershipVersion())
                            || !result.getTenantMembershipVersion().matches("[1-9][0-9]*"))
                          return Mono.error(denied());
                        return policy.current(actor, member, result.getTenantLifecycle(), use);
                      });
            });
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "Current membership required");
  }
}
