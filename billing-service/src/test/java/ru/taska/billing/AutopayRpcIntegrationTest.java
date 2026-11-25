package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.taska.billing.SubscriptionIntegrationTest.A;
import static ru.taska.billing.SubscriptionIntegrationTest.B;
import static ru.taska.billing.SubscriptionIntegrationTest.WAIT;

import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.MetadataUtils;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AutopayDispatchRpcRequest;
import ru.taska.api.b2b.v1.AutopayMandate;
import ru.taska.api.b2b.v1.AutopayWorkerServiceGrpc;
import ru.taska.api.b2b.v1.BillingServiceGrpc;
import ru.taska.api.b2b.v1.ReactorAutopayWorkerServiceGrpc;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.common.v1.Header;
import ru.taska.security.tenant.RpcContextSecurity;

@Testcontainers
@Timeout(90)
class AutopayRpcIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final AutopayIntegrationTest f = new AutopayIntegrationTest();
  final ProviderInboxRpcIntegrationTest transport = new ProviderInboxRpcIntegrationTest();

  @BeforeAll
  static void setup() throws Exception {
    SubscriptionIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    SubscriptionIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    f.reset();
  }

  @AfterEach
  void noLeak() {
    f.noLeak();
  }

  void gatewayCertificate(Path dir) throws Exception {
    BillingCatalogIntegrationTest.openssl(
        dir,
        "req",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "api-gateway.key",
        "-out",
        "api-gateway.csr",
        "-subj",
        "/CN=localhost");
    Files.writeString(
        dir.resolve("api-gateway.ext"),
        "subjectAltName=DNS:localhost,URI:spiffe://taska/test/api-gateway\n"
            + "extendedKeyUsage=serverAuth,clientAuth\n");
    BillingCatalogIntegrationTest.openssl(
        dir,
        "x509",
        "-req",
        "-in",
        "api-gateway.csr",
        "-CA",
        "ca.crt",
        "-CAkey",
        "ca.key",
        "-CAcreateserial",
        "-out",
        "api-gateway.crt",
        "-days",
        "1",
        "-extfile",
        "api-gateway.ext");
  }

  RequestContext humanContext() {
    var now = Instant.now();
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(
            f.fixture.human("setAutopayConsent").toBuilder()
                .setContextVersion(1)
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString())
                .setAssertionId(UUID.randomUUID().toString()))
        .build();
  }

  @Test
  void actualMtlsBindsConsentAndWorkerPayloadAndRevokesWarmAuthority(@TempDir Path dir)
      throws Exception {
    f.renewal();
    transport.certs(dir);
    gatewayCertificate(dir);
    var generator = KeyPairGenerator.getInstance("Ed25519");
    var pairs =
        Map.of(
            "api-gateway",
            generator.generateKeyPair(),
            "billing-service",
            generator.generateKeyPair());
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("fixture", pairs.get(issuer).getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return "fixture".equals(kid) && pairs.containsKey(issuer)
                ? pairs.get(issuer).getPublic()
                : null;
          }
        };
    var allowed = new AtomicBoolean(true);
    var seen = ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(seen.add(issuer + ":" + jti));
    RpcContextSecurity.Authority authority =
        (ctx, rpc, policy) ->
            Mono.just(allowed.get() && pairs.containsKey(ctx.getContext().getIssuerService()));
    var receiver =
        new RpcContextSecurity("billing-service", Clock.systemUTC(), keys, replay, authority);
    var sender = new RpcContextSecurity("api-gateway", Clock.systemUTC(), keys, replay, authority);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("trust", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(beans.getBeanProvider(RpcContextSecurity.class), "test");
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        dir.resolve("billing-service.crt").toFile(),
                        dir.resolve("billing-service.key").toFile())
                    .trustManager(dir.resolve("ca.crt").toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(new AutopayRpc(f.coordinator), interceptor))
            .addService(
                ServerInterceptors.intercept(
                    new BillingCatalogRpc(
                        f.fixture.catalog, f.fixture.f.store, f.fixture.invoices, null, f.store),
                    interceptor))
            .build()
            .start();
    ManagedChannel worker = transport.channel(dir, "billing-service", server.getPort());
    ManagedChannel gateway = transport.channel(dir, "api-gateway", server.getPort());
    ManagedChannel wrongPeer = transport.channel(dir, "payment-adapter", server.getPort());
    try {
      String consentRpc = BillingServiceGrpc.getSetAutopayConsentMethod().getFullMethodName();
      var q =
          f.request(true, "rpc-consent", f.version()).toBuilder()
              .setContext(humanContext())
              .build();
      var consentHeaders =
          BillingCatalogIntegrationTest.signed(
              sender, q, new RpcContextSecurity.Policy(true, true), consentRpc);
      var signed =
          BillingServiceGrpc.newBlockingStub(gateway)
              .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(consentHeaders));
      var result = signed.withDeadlineAfter(10, TimeUnit.SECONDS).setAutopayConsent(q);
      assertEquals(AutopayMandate.StateValue.STATE_CONSENTED, result.getState());
      var unsigned =
          AutopayDispatchRpcRequest.newBuilder()
              .setContext(
                  transport.context("billing-service", "dispatchAutopay", "payment:recurring", A))
              .setTenantId(A.toString())
              .setInvoiceId(f.renewalInvoice.toString())
              .build();
      String rpc = AutopayWorkerServiceGrpc.getDispatchAutopayMethod().getFullMethodName();
      assertEquals(
          Status.Code.UNAUTHENTICATED,
          assertThrows(
                  StatusRuntimeException.class,
                  () ->
                      AutopayWorkerServiceGrpc.newBlockingStub(worker)
                          .withDeadlineAfter(10, TimeUnit.SECONDS)
                          .dispatchAutopay(unsigned))
              .getStatus()
              .getCode());
      var headers =
          BillingCatalogIntegrationTest.signed(
              receiver, unsigned, new RpcContextSecurity.Policy(true, true), rpc);
      assertEquals(
          Status.Code.UNAUTHENTICATED,
          assertThrows(
                  StatusRuntimeException.class,
                  () ->
                      AutopayWorkerServiceGrpc.newBlockingStub(wrongPeer)
                          .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                          .withDeadlineAfter(10, TimeUnit.SECONDS)
                          .dispatchAutopay(unsigned))
              .getStatus()
              .getCode());
      var tamperSource =
          unsigned.toBuilder()
              .setContext(
                  transport.context("billing-service", "dispatchAutopay", "payment:recurring", A))
              .build();
      var tamperHeaders =
          BillingCatalogIntegrationTest.signed(
              receiver, tamperSource, new RpcContextSecurity.Policy(true, true), rpc);
      var altered = tamperSource.toBuilder().setTenantId(B.toString()).build();
      assertEquals(
          Status.Code.UNAUTHENTICATED,
          assertThrows(
                  StatusRuntimeException.class,
                  () ->
                      AutopayWorkerServiceGrpc.newBlockingStub(worker)
                          .withInterceptors(
                              MetadataUtils.newAttachHeadersInterceptor(tamperHeaders))
                          .withDeadlineAfter(10, TimeUnit.SECONDS)
                          .dispatchAutopay(altered))
              .getStatus()
              .getCode());
      var human =
          unsigned.toBuilder()
              .setContext(
                  humanContext().toBuilder()
                      .setContext(
                          humanContext().getContext().toBuilder()
                              .setAction("dispatchAutopay")
                              .addScopes("payment:recurring")))
              .build();
      var humanHeaders =
          BillingCatalogIntegrationTest.signed(
              sender, human, new RpcContextSecurity.Policy(true, true), rpc);
      assertEquals(
          Status.Code.PERMISSION_DENIED,
          assertThrows(
                  StatusRuntimeException.class,
                  () ->
                      AutopayWorkerServiceGrpc.newBlockingStub(gateway)
                          .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(humanHeaders))
                          .withDeadlineAfter(10, TimeUnit.SECONDS)
                          .dispatchAutopay(human))
              .getStatus()
              .getCode());
      assertEquals(0, f.count("payment_attempts"));
      var invoker =
          new GrpcAutopayWorker(
              ReactorAutopayWorkerServiceGrpc.newReactorStub(worker),
              receiver,
              Clock.systemUTC(),
              "1");
      var accepted = invoker.dispatch(A, f.renewalInvoice).block(WAIT);
      assertEquals("SUCCEEDED", accepted.getState());
      assertEquals(
          accepted.getAttemptId(),
          invoker.dispatch(A, f.renewalInvoice).block(WAIT).getAttemptId());
      assertEquals(1, f.count("payment_attempts"));
      allowed.set(false);
      assertThrows(
          StatusRuntimeException.class, () -> invoker.dispatch(A, f.renewalInvoice).block(WAIT));
      assertEquals(1, f.count("payment_attempts"));
    } finally {
      for (var channel : java.util.List.of(worker, gateway, wrongPeer)) {
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);
      }
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
