package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.billing.ingress.*;
import ru.taska.security.tenant.RpcContextSecurity;

/** Real PostgreSQL + production mTLS/JWS policies, self SDK and durable settlement evidence. */
@Testcontainers
@Timeout(90)
class ProviderInboxRpcIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final ProviderInboxIntegrationTest f = new ProviderInboxIntegrationTest();

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
  void clean() {
    f.clean();
  }

  void certs(Path dir) throws Exception {
    BillingCatalogIntegrationTest.openssl(
        dir,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt",
        "-days",
        "1",
        "-subj",
        "/CN=inbox-fixture-ca");
    for (String name : List.of("payment-adapter", "billing-service")) {
      BillingCatalogIntegrationTest.openssl(
          dir,
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr",
          "-subj",
          "/CN=localhost");
      Files.writeString(
          dir.resolve(name + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + name
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      BillingCatalogIntegrationTest.openssl(
          dir,
          "x509",
          "-req",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          name + ".crt",
          "-days",
          "1",
          "-extfile",
          name + ".ext");
    }
  }

  io.grpc.ManagedChannel channel(Path certs, String identity, int port) throws Exception {
    return io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress("localhost", port)
        .sslContext(
            io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                .trustManager(certs.resolve("ca.crt").toFile())
                .keyManager(
                    certs.resolve(identity + ".crt").toFile(),
                    certs.resolve(identity + ".key").toFile())
                .build())
        .build();
  }

  RequestContext context(String issuer, String action, String scope, UUID tenant) {
    var now = java.time.Instant.now();
    var c =
        TrustedTenantContext.newBuilder()
            .setContextVersion(1)
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setActorGeneration("service-1")
            .setIssuerService(issuer)
            .setAudienceService("billing-service")
            .setAction(action)
            .addScopes("write")
            .addScopes(scope)
            .setIssuedAt(now.toString())
            .setExpiresAt(now.plusSeconds(30).toString())
            .setAssertionId(UUID.randomUUID().toString());
    if (tenant != null) c.setTenantId(tenant.toString());
    return RequestContext.newBuilder()
        .setHeader(Header.newBuilder().setRequestId(UUID.randomUUID().toString()))
        .setContext(c)
        .build();
  }

  @Test
  void verifiedTransportAckThenAsyncSelfProcessingHasOneFinancialEffectAfterLostRootAck(
      @TempDir Path dir) throws Exception {
    certs(dir);
    var crypto = KeyPairGenerator.getInstance("Ed25519");
    var pairs =
        Map.of(
            "payment-adapter",
            crypto.generateKeyPair(),
            "billing-service",
            crypto.generateKeyPair());
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("fixture", pairs.get(issuer).getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return kid.equals("fixture") && pairs.containsKey(issuer)
                ? pairs.get(issuer).getPublic()
                : null;
          }
        };
    var allowed = new AtomicBoolean(true);
    var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    RpcContextSecurity.Authority authority =
        (ctx, rpc, policy) ->
            Mono.just(
                allowed.get()
                    && ctx.getContext().getActorGeneration().equals("service-1")
                    && pairs.containsKey(ctx.getContext().getIssuerService())
                    && ctx.getContext().getCredentialKind()
                        == CredentialKind.CREDENTIAL_KIND_SERVICE);
    RpcContextSecurity.ReplayGuard replay =
        (issuer, jti, until) -> Mono.just(seen.add(issuer + ":" + jti));
    var receiver =
        new RpcContextSecurity("billing-service", Clock.systemUTC(), keys, replay, authority);
    var adapter =
        new RpcContextSecurity("payment-adapter", Clock.systemUTC(), keys, replay, authority);
    var factory = new DefaultListableBeanFactory();
    factory.registerSingleton("trust", receiver);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(factory.getBeanProvider(RpcContextSecurity.class), "test");
    var worker = new AtomicReference<GrpcProviderInboxWorker>();
    var inbox =
        new ProviderInbox(f.store, f.registry, (t, e) -> worker.get().apply(t, e), 1024, 120);
    var server =
        io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(
                new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(
                        dir.resolve("billing-service.crt").toFile(),
                        dir.resolve("billing-service.key").toFile())
                    .trustManager(dir.resolve("ca.crt").toFile())
                    .clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(new ProviderInboxGrpc(inbox), interceptor))
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new PaymentSettlementGrpc(f.base.settlement), interceptor))
            .build()
            .start();
    var adapterChannel = channel(dir, "payment-adapter", server.getPort());
    var self = channel(dir, "billing-service", server.getPort());
    try {
      var plain =
          ProviderInboxServiceGrpc.newBlockingStub(adapterChannel)
              .withDeadlineAfter(15, TimeUnit.SECONDS);
      var request =
          ObserveProviderWebhookRequest.newBuilder()
              .setContext(
                  context("payment-adapter", "observeProviderWebhook", "inbox:ingest", null))
              .setProvider(f.identity.provider())
              .setProviderAccountId(f.identity.account())
              .setEnvironment(ProviderEnvironment.PROVIDER_ENVIRONMENT_TEST)
              .setRawBody(com.google.protobuf.ByteString.copyFrom(f.webhook.rawBody()))
              .setSignature(f.webhook.signature())
              .build();
      String rpc = ProviderInboxServiceGrpc.getObserveProviderWebhookMethod().getFullMethodName();
      var policy = new RpcContextSecurity.Policy(false, true);
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class, () -> plain.observeProviderWebhook(request))
              .getStatus()
              .getCode());
      var signed =
          plain.withInterceptors(
              MetadataUtils.newAttachHeadersInterceptor(
                  BillingCatalogIntegrationTest.signed(adapter, request, policy, rpc)));
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () ->
                      signed.observeProviderWebhook(
                          request.toBuilder().setSignature("forged").build()))
              .getStatus()
              .getCode());
      var acceptedRequest =
          request.toBuilder()
              .setContext(
                  context("payment-adapter", "observeProviderWebhook", "inbox:ingest", null))
              .build();
      // Sign the exact request sent below; assertions are single-use and body-bound.
      var accepted =
          plain
              .withInterceptors(
                  MetadataUtils.newAttachHeadersInterceptor(
                      BillingCatalogIntegrationTest.signed(adapter, acceptedRequest, policy, rpc)))
              .observeProviderWebhook(acceptedRequest);
      assertEquals("RECEIVED", accepted.getState());
      assertEquals(0, f.count("payment_facts"));
      var bind =
          RegisterProviderPaymentRequest.newBuilder()
              .setContext(context("billing-service", "registerProviderPayment", "inbox:map", A))
              .setTenantId(A.toString())
              .setInvoiceId(f.base.invoice.toString())
              .setProvider(f.identity.provider())
              .setProviderAccountId(f.identity.account())
              .setEnvironment(ProviderEnvironment.PROVIDER_ENVIRONMENT_TEST)
              .setProviderPaymentId(f.payment.providerPaymentId())
              .build();
      ProviderInboxServiceGrpc.newBlockingStub(self)
          .withDeadlineAfter(15, TimeUnit.SECONDS)
          .withInterceptors(
              MetadataUtils.newAttachHeadersInterceptor(
                  BillingCatalogIntegrationTest.signed(
                      receiver,
                      bind,
                      new RpcContextSecurity.Policy(true, true),
                      ProviderInboxServiceGrpc.getRegisterProviderPaymentMethod()
                          .getFullMethodName())))
          .registerProviderPayment(bind);
      worker.set(
          new GrpcProviderInboxWorker(
              ReactorProviderInboxServiceGrpc.newReactorStub(self),
              ReactorPaymentSettlementServiceGrpc.newReactorStub(self),
              receiver,
              Clock.systemUTC(),
              "service-1"));
      var pending = new ProviderInboxStore.Pending(UUID.fromString(accepted.getId()), A);
      f.base.loseDelivery.set(true);
      assertThrows(
          io.grpc.StatusRuntimeException.class, () -> worker.get().process(pending).block(WAIT));
      assertEquals(1, f.count("payment_facts"));
      assertEquals(1, f.count("payment_settlements"));
      f.base.loseDelivery.set(false);
      worker.get().process(pending).block(WAIT);
      worker.get().process(pending).block(WAIT);
      assertEquals(1, f.count("provider_payment_proofs"));
      assertEquals(1, f.count("payment_facts"));
      assertEquals(1, f.count("subscription_periods"));
      assertTrue(f.store.pending(50).block(WAIT).isEmpty());
      allowed.set(false);
      assertThrows(
          io.grpc.StatusRuntimeException.class, () -> worker.get().process(pending).block(WAIT));
      assertEquals(1, f.count("payment_facts"));
    } finally {
      adapterChannel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      self.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
