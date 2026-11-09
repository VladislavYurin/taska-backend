package ru.taska.security.trust;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.grpc.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.ManagedRpcPeerInterceptor;

class RpcTrustControlBoundaryTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @Test
  void emptyPrimaryResultsFailExplicitlyForEveryControlMethod() {
    var backend = mock(AuthRpcTrustBackend.class);
    when(backend.allowed(any(), anyString(), any())).thenReturn(Mono.empty());
    when(backend.consume(anyString(), anyString(), anyString(), any())).thenReturn(Mono.empty());
    when(backend.currentKey(anyString(), anyString(), anyString(), isNull()))
        .thenReturn(Mono.empty());
    var control = new RpcTrustControlGrpcService(backend);
    var context =
        RequestContext.newBuilder()
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setIssuerService("project-service")
                    .setAudienceService("auth-service"))
            .build();
    Context.current()
        .withValue(ManagedRpcPeerInterceptor.PEER, "project-service")
        .run(
            () -> {
              for (var result :
                  java.util.List.of(
                      control.checkRpcAuthority(
                          Mono.just(
                              CheckRpcAuthorityRequest.newBuilder()
                                  .setContext(context)
                                  .setRpc("owned/Run")
                                  .build())),
                      control.consumeRpcAssertion(
                          Mono.just(
                              ConsumeRpcAssertionRequest.newBuilder()
                                  .setRecipient("project-service")
                                  .setIssuer("auth-service")
                                  .setAssertionId(java.util.UUID.randomUUID().toString())
                                  .setRetainUntil(java.time.Instant.now().toString())
                                  .build())),
                      control.checkRpcKey(
                          Mono.just(
                              CheckRpcKeyRequest.newBuilder()
                                  .setRecipient("project-service")
                                  .setIssuer("project-service")
                                  .setKid("one")
                                  .build())))) {
                assertEquals(
                    Status.Code.UNAVAILABLE,
                    Status.fromThrowable(assertThrows(StatusRuntimeException.class, result::block))
                        .getCode());
              }
            });
  }

  @Test
  void asynchronousCleanupDoesNotOverlapAndCanRunAgainAfterCompletionOrError() {
    var backend = mock(AuthRpcTrustBackend.class);
    Sinks.Empty<Void> blocked = Sinks.empty();
    when(backend.cleanup())
        .thenReturn(
            blocked.asMono(),
            Mono.empty(),
            Mono.error(new IllegalStateException("owned failure")),
            Mono.empty());
    var cleanup = new AuthRpcTrustConfiguration.RpcReplayCleanup(backend);
    cleanup.cleanup();
    cleanup.cleanup();
    verify(backend, times(1)).cleanup();
    blocked.tryEmitEmpty();
    cleanup.cleanup();
    cleanup.cleanup();
    cleanup.cleanup();
    verify(backend, times(4)).cleanup();
  }

  @Test
  @SuppressWarnings("unchecked")
  void secondUnaryMessageBeforePeerVerificationNeverReachesTheHandler() throws Exception {
    var fixture =
        new ru.taska.security.tenant.ManagedTrustFixture(directory, java.util.List.of("a", "b"));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var trust = org.mockito.Mockito.mock(ru.taska.security.tenant.ManagedRpcTrust.class);
    org.mockito.Mockito.when(
            trust.peerAllowed(
                org.mockito.ArgumentMatchers.eq("a"), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(5, java.util.concurrent.TimeUnit.SECONDS);
              return true;
            });
    var ssl = org.mockito.Mockito.mock(javax.net.ssl.SSLSession.class);
    try (var input = java.nio.file.Files.newInputStream(fixture.path("a.crt"))) {
      org.mockito.Mockito.when(ssl.getPeerCertificates())
          .thenReturn(
              new java.security.cert.Certificate[] {
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(input)
              });
    }
    io.grpc.ServerCall<RequestContext, RequestContext> call =
        org.mockito.Mockito.mock(io.grpc.ServerCall.class);
    org.mockito.Mockito.when(call.getAttributes())
        .thenReturn(
            io.grpc.Attributes.newBuilder()
                .set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, ssl)
                .build());
    org.mockito.Mockito.when(call.getMethodDescriptor())
        .thenReturn(
            io.grpc.MethodDescriptor.<RequestContext, RequestContext>newBuilder()
                .setFullMethodName("owned/Run")
                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                .setRequestMarshaller(
                    io.grpc.protobuf.ProtoUtils.marshaller(RequestContext.getDefaultInstance()))
                .setResponseMarshaller(
                    io.grpc.protobuf.ProtoUtils.marshaller(RequestContext.getDefaultInstance()))
                .build());
    var hits = new java.util.concurrent.atomic.AtomicInteger();
    var listener =
        new ManagedRpcPeerInterceptor(trust, "test")
            .interceptCall(
                call,
                new io.grpc.Metadata(),
                (c, h) -> {
                  hits.incrementAndGet();
                  return new io.grpc.ServerCall.Listener<>() {};
                });
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      listener.onMessage(RequestContext.getDefaultInstance());
      listener.onMessage(RequestContext.getDefaultInstance());
      listener.onHalfClose();
      org.mockito.Mockito.verify(call)
          .close(
              org.mockito.ArgumentMatchers.argThat(s -> s.getCode() == Status.Code.UNAUTHENTICATED),
              org.mockito.ArgumentMatchers.any());
      assertEquals(0, hits.get());
    } finally {
      release.countDown();
    }
  }
}
