package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real loopback TLS transport: generated test credentials never enter Git. */
class TenantTransportTest extends RpcContextFixture {
  @TempDir Path certificates;

  void openssl(String... args) throws Exception {
    var command = new ArrayList<>(List.of("openssl"));
    command.addAll(List.of(args));
    var p =
        new ProcessBuilder(command)
            .directory(certificates.toFile())
            .redirectErrorStream(true)
            .start();
    var output = p.getInputStream().readAllBytes();
    assertTrue(p.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, p.exitValue(), new String(output));
  }

  void certificates() throws Exception {
    openssl(
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
        "/CN=test-ca");
    for (String service : List.of("a", "b")) {
      openssl(
          "req",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-keyout",
          service + ".key",
          "-out",
          service + ".csr",
          "-subj",
          "/CN=localhost");
      Files.writeString(
          certificates.resolve(service + ".ext"),
          "subjectAltName=DNS:localhost,URI:spiffe://taska/test/"
              + service
              + "\nextendedKeyUsage=serverAuth,clientAuth\n");
      openssl(
          "x509",
          "-req",
          "-in",
          service + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-out",
          service + ".crt",
          "-days",
          "1",
          "-extfile",
          service + ".ext");
    }
  }

  @Test
  void realMtlsCallInstallsReactorContextBeforeHandler() throws Exception {
    certificates();
    var hits = new AtomicInteger();
    var method =
        io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(RPC)
            .setRequestMarshaller(
                ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor)))
            .setResponseMarshaller(
                ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor)))
            .build();
    var definition =
        ServerServiceDefinition.builder("test.Echo")
            .addMethod(
                method,
                ServerCalls.asyncUnaryCall(
                    (req, observer) -> {
                      hits.incrementAndGet();
                      RpcContextBridge.inbound(
                              VerifiedTenantContext.current()
                                  .publishOn(reactor.core.scheduler.Schedulers.parallel()))
                          .subscribe(
                              v -> {
                                assertEquals(TENANT, v.tenantId());
                                observer.onNext(req);
                                observer.onCompleted();
                              },
                              observer::onError);
                    }))
            .build();
    var ssl =
        GrpcSslContexts.forServer(
                certificates.resolve("b.crt").toFile(), certificates.resolve("b.key").toFile())
            .trustManager(certificates.resolve("ca.crt").toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    Server server =
        NettyServerBuilder.forAddress(new java.net.InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(
                ServerInterceptors.intercept(
                    definition,
                    new TenantServerInterceptor(recipient, Map.of(RPC, POLICY), "test")))
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
      var c = context();
      var m = message(c, "payload");
      var parent = sender.authorize(c, RPC, POLICY).block();
      var secured =
          ClientInterceptors.intercept(
              channel, new TenantClientInterceptor(sender, Map.of(RPC, POLICY)));
      var options =
          CallOptions.DEFAULT
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .withOption(RpcContextBridge.OUTBOUND, parent);
      var response = ClientCalls.blockingUnaryCall(secured, method, options, m);
      assertEquals("payload", response.getField(descriptor.findFieldByName("payload")));
      assertEquals(TENANT, RpcContextSecurity.context(response).getContext().getTenantId());
      assertEquals(1, hits.get());
      // Legitimate transport retries receive a fresh attempt, preserving the business payload.
      var retry = ClientCalls.blockingUnaryCall(secured, method, options, m);
      assertNotEquals(
          RpcContextSecurity.context(response).getContext().getAssertionId(),
          RpcContextSecurity.context(retry).getContext().getAssertionId());
      assertEquals(2, hits.get());
      // A plaintext metadata assertion never replaces authenticated transport identity.
      var raw =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  ClientCalls.blockingUnaryCall(
                      channel,
                      method,
                      CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                      m));
      assertEquals(Status.Code.UNAUTHENTICATED, raw.getStatus().getCode());
      assertEquals(2, hits.get());
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
