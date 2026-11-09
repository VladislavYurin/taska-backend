package ru.taska.security.trust;

import io.grpc.ManagedChannel;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.security.session.WebSessionStore;
import ru.taska.security.tenant.*;

/**
 * Auth primary asks tenant primary with its own purpose, never fabricated user membership claims.
 */
public final class GrpcMembershipAuthority
    implements WebSessionStore.MembershipAuthority, AutoCloseable {
  private final ManagedChannel channel;
  private final Supplier<RpcContextSecurity> security;
  private final ManagedRpcTrust trust;

  public GrpcMembershipAuthority(
      ManagedChannel channel, Supplier<RpcContextSecurity> security, ManagedRpcTrust trust) {
    this.channel = channel;
    this.security = security;
    this.trust = trust;
  }

  @Override
  public Mono<Long> current(UUID tenant, UUID user) {
    return Mono.defer(
            () -> {
              var guard = security.get();
              if (guard == null) return Mono.error(ManagedRpcTrust.unavailable());
              var now = Instant.now();
              var context =
                  RequestContext.newBuilder()
                      .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
                      .setContext(
                          TrustedTenantContext.newBuilder()
                              .setContextVersion(1)
                              .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                              .setServicePurpose("membership")
                              .setServiceIdentity("auth-service")
                              .setActorGeneration(
                                  trust.snapshot().services().get("auth-service").generation())
                              .setTenantId(tenant.toString())
                              .addScopes("memberships:read")
                              .setIssuerService("auth-service")
                              .setAudienceService("tenant-service")
                              .setAction("getMembership")
                              .setIssuedAt(now.toString())
                              .setExpiresAt(now.plusSeconds(30).toString())
                              .setAssertionId(UUID.randomUUID().toString()))
                      .build();
              var method = TenantMembershipServiceGrpc.getGetMembershipMethod().getFullMethodName();
              var policy = new RpcContextSecurity.Policy(true, false);
              return guard
                  .authorize(context, method, policy)
                  .flatMap(
                      parent -> {
                        var stub =
                            ReactorTenantMembershipServiceGrpc.newReactorStub(channel)
                                .withInterceptors(
                                    new TenantClientInterceptor(guard, Map.of(method, policy)))
                                .withOption(RpcContextBridge.OUTBOUND, parent)
                                .withDeadlineAfter(3, TimeUnit.SECONDS);
                        return stub.getMembership(
                            GetMembershipRpcRequest.newBuilder()
                                .setContext(context)
                                .setTenantId(tenant.toString())
                                .setUserId(user.toString())
                                .build());
                      })
                  .flatMap(
                      snapshot -> {
                        if (!snapshot.getTenantId().equals(tenant.toString())
                            || !snapshot.getUserId().equals(user.toString()))
                          return Mono.error(ManagedRpcTrust.unavailable());
                        if (snapshot.getState() != Membership.StateValue.STATE_ACTIVE)
                          return Mono.error(
                              new ru.taska.exception.DomainException(
                                  ru.taska.exception.DomainStatus.PERMISSION_DENIED,
                                  "MEMBERSHIP_INACTIVE"));
                        return Mono.just(Long.parseLong(snapshot.getVersion()));
                      })
                  .onErrorResume(
                      io.grpc.StatusRuntimeException.class,
                      e ->
                          e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND
                              ? Mono.empty()
                              : Mono.error(e));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  @Override
  public void close() {
    channel.shutdownNow();
  }
}
