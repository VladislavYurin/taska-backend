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
import ru.taska.entitlement.GrpcTenantTaskQuotaSource;

class GrpcTaskQuotaSourceTest extends RpcContextFixture {
  @TempDir Path certificates;

  @Test
  void readsOverMtlsWithBoundTenantAndCurrentServiceAuthority() throws Exception {
    var tls = new TenantTransportTest();
    tls.certificates = certificates;
    tls.certificates();
    String rpc =
        ProjectQuotaReadServiceGrpc.getGetTenantTaskQuotaSummaryMethod().getFullMethodName();
    var policy = new RpcContextSecurity.Policy(true, false);
    var calls = new AtomicInteger();
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    RpcContextSecurity.Authority authority =
        (r, m, p) ->
            Mono.just(
                allowed
                    && rpc.equals(m)
                    && r.getContext().getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE
                    && "getTenantTaskQuotaSummary".equals(r.getContext().getAction())
                    && TENANT.equals(r.getContext().getTenantId()));
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.fromSupplier(() -> consumed.add(issuer + ":" + jti));
    var outbound = new RpcContextSecurity("a", clock, keys, replay, authority);
    var inbound = new RpcContextSecurity("issue-service", clock, keys, replay, authority);
    var handler =
        new ReactorProjectQuotaReadServiceGrpc.ProjectQuotaReadServiceImplBase() {
          @Override
          public Mono<TenantTaskQuotaSummary> getTenantTaskQuotaSummary(
              Mono<GetTenantTaskQuotaSummaryRequest> requests) {
            return RpcContextBridge.inbound(
                requests.flatMap(
                    request ->
                        VerifiedTenantContext.current()
                            .map(
                                c -> {
                                  assertEquals(TENANT, c.tenantId());
                                  assertNull(c.actorId());
                                  assertEquals(TENANT, request.getTenantId());
                                  calls.incrementAndGet();
                                  return TenantTaskQuotaSummary.newBuilder()
                                      .setTenantId(TENANT)
                                      .setMaximumCreated("51")
                                      .setVersion("12")
                                      .setEntitlementVersion("3")
                                      .setOverLimit(true)
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
          new GrpcTenantTaskQuotaSource(
              ReactorProjectQuotaReadServiceGrpc.newReactorStub(channel), outbound, clock, "a");
      var request = source.current(UUID.fromString(TENANT));
      assertTrue(request.contextWrite(parent::install).block().getOverLimit());
      assertEquals("51", request.contextWrite(parent::install).block().getMaximumCreated());
      assertEquals(2, calls.get());
      allowed = false;
      assertThrows(
          StatusRuntimeException.class, () -> request.contextWrite(parent::install).block());
      assertEquals(2, calls.get());
      allowed = true;
      assertThrows(
          RuntimeException.class,
          () -> source.current(UUID.randomUUID()).contextWrite(parent::install).block());
      assertEquals(2, calls.get());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
