package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.billing.SubscriptionIntegrationTest.*;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.ingress.*;
import ru.taska.billing.reconciliation.*;
import ru.taska.security.tenant.RpcContextSecurity;

/** Actual new mTLS/JWS worker RPC -> durable PG proof -> existing settlement RPC, no webhook. */
@Testcontainers
@Timeout(90)
class PaymentReconciliationRpcIntegrationTest {
  @Container static final PostgreSQLContainer<?> database = SubscriptionIntegrationTest.pg;
  final PaymentReconciliationIntegrationTest f = new PaymentReconciliationIntegrationTest();
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
  void clean() {
    f.clean();
  }

  @Test
  void leasedRecoveryAuthenticatesActualTransportAndSurvivesLostSettlementAck(@TempDir Path dir)
      throws Exception {
    transport.certs(dir);
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("fixture", pair.getPrivate());
          }

          public java.security.PublicKey verificationKey(String issuer, String kid) {
            return issuer.equals("billing-service") && kid.equals("fixture")
                ? pair.getPublic()
                : null;
          }
        };
    var allowed = new AtomicBoolean(true);
    var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    var trust =
        new RpcContextSecurity(
            "billing-service",
            Clock.systemUTC(),
            keys,
            (issuer, jti, until) -> Mono.just(seen.add(issuer + ":" + jti)),
            (ctx, rpc, policy) ->
                Mono.just(
                    allowed.get()
                        && ctx.getContext().getIssuerService().equals("billing-service")
                        && ctx.getContext().getActorGeneration().equals("service-1")));
    var factory = new DefaultListableBeanFactory();
    factory.registerSingleton("trust", trust);
    var interceptor =
        new BillingConfiguration()
            .registrySecurity(factory.getBeanProvider(RpcContextSecurity.class), "test");
    var worker = new AtomicReference<GrpcProviderInboxWorker>();
    var core =
        new PaymentReconciliation(
            f.jobs,
            f.attempts,
            f.operations,
            new ru.taska.billing.payment.PaymentProviderRegistry(
                Map.of(
                    new ru.taska.billing.payment.PaymentProviderRegistry.Identity(
                        f.f.identity.provider(), f.f.identity.merchant()),
                    new ru.taska.billing.payment.PaymentProviderRegistry.Registration(
                        f.provider, Set.of("v1")))),
            f.f.registry,
            f.f.store,
            (t, e) -> worker.get().apply(t, e));
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
                io.grpc.ServerInterceptors.intercept(
                    new PaymentReconciliationGrpc(core), interceptor))
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    new PaymentSettlementGrpc(f.f.base.settlement), interceptor))
            .build()
            .start();
    var channel = transport.channel(dir, "billing-service", server.getPort());
    try {
      worker.set(
          new GrpcProviderInboxWorker(
              ReactorProviderInboxServiceGrpc.newReactorStub(channel),
              ReactorPaymentSettlementServiceGrpc.newReactorStub(channel),
              trust,
              Clock.systemUTC(),
              "service-1"));
      var stub = ReactorPaymentReconciliationServiceGrpc.newReactorStub(channel);
      var job = f.claim();
      var request =
          ReconcilePaymentAttemptRequest.newBuilder()
              .setContext(
                  transport.context(
                      "billing-service", "reconcilePaymentAttempt", "payment:reconcile", A))
              .setTenantId(A.toString())
              .setAttemptId(job.attemptId().toString())
              .setLeaseToken(job.token().toString())
              .build();
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () ->
                      PaymentReconciliationServiceGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .reconcilePaymentAttempt(request))
              .getStatus()
              .getCode());
      var rpc =
          PaymentReconciliationServiceGrpc.getReconcilePaymentAttemptMethod().getFullMethodName();
      var signed =
          BillingCatalogIntegrationTest.signed(
              trust, request, new RpcContextSecurity.Policy(true, true), rpc);
      assertEquals(
          io.grpc.Status.Code.UNAUTHENTICATED,
          assertThrows(
                  io.grpc.StatusRuntimeException.class,
                  () ->
                      PaymentReconciliationServiceGrpc.newBlockingStub(channel)
                          .withDeadlineAfter(5, TimeUnit.SECONDS)
                          .withInterceptors(
                              io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(signed))
                          .reconcilePaymentAttempt(
                              request.toBuilder().setTenantId(B.toString()).build()))
              .getStatus()
              .getCode());
      assertEquals(0, f.count("payment_facts"));
      allowed.set(false);
      assertThrows(
          io.grpc.StatusRuntimeException.class,
          () -> worker.get().reconcile(stub, job).block(WAIT));
      allowed.set(true);
      f.f.base.loseDelivery.set(true);
      assertEquals("PENDING", worker.get().reconcile(stub, job).block(WAIT).getState());
      assertEquals(1, f.count("payment_facts"));
      assertEquals(1, f.count("provider_payment_proofs"));
      f.f.base.loseDelivery.set(false);
      f.advance(31);
      assertEquals("DONE", worker.get().reconcile(stub, f.claim()).block(WAIT).getState());
      assertEquals(1, f.count("payment_facts"));
      assertEquals(1, f.count("subscription_periods"));
      assertEquals(1, f.count("initial_payment_deliveries"));
      assertEquals(0, f.jobs.summary().block(WAIT).pending());
    } finally {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
