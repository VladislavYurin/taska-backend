package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.entitlement.GrpcEntitlementSource;
import ru.taska.entitlement.GrpcMembershipAccess;

class GrpcEntitlementSourceTest extends RpcContextFixture {
  @TempDir Path certificates;

  @Test
  void reconcilesOverMtlsWithTenantMinimumAndCurrentServiceAuthority() throws Exception {
    var tls = new TenantTransportTest();
    tls.certificates = certificates;
    tls.certificates();
    String rpc = EntitlementServiceGrpc.getGetEntitlementSnapshotMethod().getFullMethodName();
    var policy = new RpcContextSecurity.Policy(true, false);
    var calls = new AtomicInteger();
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RpcContextSecurity.Authority authority =
        (r, method, p) ->
            Mono.just(
                allowed
                    && rpc.equals(method)
                    && r.getContext().getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE
                    && "getEntitlementSnapshot".equals(r.getContext().getAction())
                    && TENANT.equals(r.getContext().getTenantId()));
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.fromSupplier(() -> consumed.add(issuer + ":" + jti));
    var outbound = new RpcContextSecurity("a", clock, keys, replay, authority);
    var inbound = new RpcContextSecurity("billing-service", clock, keys, replay, authority);
    String membershipRpc =
        TenantMembershipServiceGrpc.getBatchValidateMembershipsMethod().getFullMethodName();
    var state =
        new java.util.concurrent.atomic.AtomicReference<>(Membership.StateValue.STATE_ACTIVE);
    var role =
        new java.util.concurrent.atomic.AtomicReference<>(Membership.RoleValue.ROLE_TENANT_ADMIN);
    var membershipCalls = new AtomicInteger();
    RpcContextSecurity.Authority membershipAuthority =
        (r, m, p) ->
            Mono.just(
                allowed
                    && membershipRpc.equals(m)
                    && "batchValidateMemberships".equals(r.getContext().getAction())
                    && r.getContext().getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE
                    && r.getContext().getScopesList().contains("memberships:read"));
    var membershipOutbound = new RpcContextSecurity("a", clock, keys, replay, membershipAuthority);
    var membershipInbound =
        new RpcContextSecurity("tenant-service", clock, keys, replay, membershipAuthority);
    var membershipHandler =
        new ReactorTenantMembershipServiceGrpc.TenantMembershipServiceImplBase() {
          @Override
          public Mono<MembershipValidationBatch> batchValidateMemberships(
              Mono<BatchValidateMembershipsRpcRequest> requests) {
            return RpcContextBridge.inbound(
                requests.flatMap(
                    request ->
                        VerifiedTenantContext.current()
                            .map(
                                v -> {
                                  assertEquals(TENANT, v.tenantId());
                                  assertEquals(List.of(ACTOR), request.getUserIdsList());
                                  membershipCalls.incrementAndGet();
                                  return MembershipValidationBatch.newBuilder()
                                      .setTenantId(TENANT)
                                      .setTenantMembershipVersion("2")
                                      .setTenantLifecycle(Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
                                      .addItems(
                                          MembershipValidation.newBuilder()
                                              .setUserId(ACTOR)
                                              .setFound(true)
                                              .setMembership(
                                                  MembershipSnapshot.newBuilder()
                                                      .setTenantId(TENANT)
                                                      .setUserId(ACTOR)
                                                      .setVersion("2")
                                                      .setTenantMembershipVersion("2")
                                                      .setState(state.get())
                                                      .setRole(role.get())))
                                      .build();
                                })));
          }
        };
    var handler =
        new ReactorEntitlementServiceGrpc.EntitlementServiceImplBase() {
          @Override
          public Mono<Entitlement> getEntitlementSnapshot(
              Mono<GetEntitlementSnapshotRequest> requests) {
            return RpcContextBridge.inbound(
                requests.flatMap(
                    request ->
                        VerifiedTenantContext.current()
                            .map(
                                context -> {
                                  assertEquals(TENANT, context.tenantId());
                                  assertEquals("12", request.getMinimumVersion());
                                  assertEquals(TENANT, request.getTenantId());
                                  calls.incrementAndGet();
                                  return Entitlement.newBuilder()
                                      .setTenantId(TENANT)
                                      .setVersion("12")
                                      .build();
                                })));
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        certificates.resolve("b.crt").toFile(),
                        certificates.resolve("b.key").toFile())
                    .trustManager(certificates.resolve("ca.crt").toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                ServerInterceptors.intercept(
                    handler, new TenantServerInterceptor(inbound, Map.of(rpc, policy), "test")))
            .addService(
                ServerInterceptors.intercept(
                    membershipHandler,
                    new TenantServerInterceptor(
                        membershipInbound, Map.of(membershipRpc, policy), "test")))
            .build()
            .start();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("localhost", server.getPort())
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(certificates.resolve("ca.crt").toFile())
                    .keyManager(
                        certificates.resolve("a.crt").toFile(),
                        certificates.resolve("a.key").toFile())
                    .build())
            .build();
    try {
      var parent = sender.authorize(context(), RPC, POLICY).block();
      var source =
          new GrpcEntitlementSource(
              ReactorEntitlementServiceGrpc.newReactorStub(channel), outbound, clock, "a");
      var request = source.current(UUID.fromString(TENANT), 12);
      assertEquals("12", request.contextWrite(parent::install).block().getVersion());
      assertEquals("12", request.contextWrite(parent::install).block().getVersion());
      assertEquals(2, calls.get());
      var access =
          new GrpcMembershipAccess(
              ReactorTenantMembershipServiceGrpc.newReactorStub(channel),
              membershipOutbound,
              clock,
              "a",
              (actor, member, lifecycle, use) -> {
                if (use == EntitlementProjection.Use.NEW_RESOURCE
                    && member.getRole() != Membership.RoleValue.ROLE_TENANT_ADMIN)
                  return Mono.error(
                      new ru.taska.exception.DomainException(
                          ru.taska.exception.DomainStatus.PERMISSION_DENIED, "role revoked"));
                return Mono.just(Set.of(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE));
              });
      var admission =
          access
              .current(parent, EntitlementProjection.Use.NEW_RESOURCE)
              .contextWrite(parent::install);
      assertTrue(admission.block().contains(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE));
      role.set(Membership.RoleValue.ROLE_MEMBER);
      assertThrows(ru.taska.exception.DomainException.class, admission::block);
      assertTrue(
          access
              .current(parent, EntitlementProjection.Use.READ)
              .contextWrite(parent::install)
              .block()
              .contains(RestrictionCode.RESTRICTION_CODE_OWNER_PAUSE));
      state.set(Membership.StateValue.STATE_SUSPENDED);
      assertThrows(
          ru.taska.exception.DomainException.class,
          () ->
              access
                  .current(parent, EntitlementProjection.Use.READ)
                  .contextWrite(parent::install)
                  .block());
      assertEquals(4, membershipCalls.get());
      allowed = false;
      assertThrows(
          StatusRuntimeException.class, () -> request.contextWrite(parent::install).block());
      assertEquals(2, calls.get());
      allowed = true;
      assertThrows(
          RuntimeException.class,
          () -> source.current(UUID.randomUUID(), 12).contextWrite(parent::install).block());
      assertEquals(2, calls.get());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
