package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import io.grpc.Status;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.b2b.v1.*;

class RpcContextSecurityTest extends RpcContextFixture {
  @Test
  void validMessageAndSingleUse() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    var v = recipient.verify(m, signature, RPC, "a", POLICY).block();
    assertEquals(ACTOR, v.actorId());
    assertEquals(TENANT, v.tenantId());
    assertEquals(c.getHeader().getRequestId(), v.requestId());
    rejects(recipient.verify(m, signature, RPC, "a", POLICY), Status.Code.UNAUTHENTICATED);
  }

  @Test
  void messageMethodPeerAndSignatureAreBound() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    rejects(
        recipient.verify(message(c, "changed"), signature, RPC, "a", POLICY),
        Status.Code.UNAUTHENTICATED);
    rejects(
        recipient.verify(m, signature, "other/Method", "a", POLICY), Status.Code.UNAUTHENTICATED);
    rejects(recipient.verify(m, signature, RPC, "forged", POLICY), Status.Code.UNAUTHENTICATED);
    rejects(
        recipient.verify(
            m, signature.substring(0, signature.lastIndexOf('.') + 1) + "AAAA", RPC, "a", POLICY),
        Status.Code.UNAUTHENTICATED);
    rejects(recipient.verify(m, null, RPC, "a", POLICY), Status.Code.UNAUTHENTICATED);
    assertTrue(consumed.isEmpty());
  }

  @Test
  void tenantTamperingAndCurrentAuthorityDenial() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    var changed =
        c.toBuilder()
            .setContext(c.getContext().toBuilder().setTenantId(UUID.randomUUID().toString()))
            .build();
    rejects(
        recipient.verify(message(changed, "original"), signature, RPC, "a", POLICY),
        Status.Code.UNAUTHENTICATED);
    allowed = false;
    rejects(recipient.verify(m, signature, RPC, "a", POLICY), Status.Code.PERMISSION_DENIED);
    assertTrue(consumed.isEmpty());
  }

  @Test
  void expiredAndMissingTenantNeverAuthorized() {
    var c = context();
    var expired =
        c.toBuilder()
            .setContext(
                c.getContext().toBuilder()
                    .setIssuedAt(NOW.minusSeconds(60).toString())
                    .setExpiresAt(NOW.minusSeconds(30).toString()))
            .build();
    rejects(sender.authorize(expired, RPC, POLICY), Status.Code.UNAUTHENTICATED);
    rejects(
        sender.authorize(
            c.toBuilder().setContext(c.getContext().toBuilder().clearTenantId()).build(),
            RPC,
            POLICY),
        Status.Code.UNAUTHENTICATED);
  }

  @Test
  void concurrentReplayHasOneWinner() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    var results =
        reactor.core.publisher.Flux.range(0, 32)
            .flatMap(
                i ->
                    recipient
                        .verify(m, signature, RPC, "a", POLICY)
                        .map(v -> 1)
                        .onErrorReturn(0)
                        .subscribeOn(Schedulers.parallel()),
                32)
            .collectList()
            .block();
    assertEquals(1, results.stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  void noContextLeakAcrossSchedulerAndCancellation() {
    var a = sender.authorize(context(), RPC, POLICY).block();
    var bContext = context();
    bContext =
        bContext.toBuilder()
            .setContext(bContext.getContext().toBuilder().setTenantId(UUID.randomUUID().toString()))
            .build();
    var b = sender.authorize(bContext, RPC, POLICY).block();
    var values =
        reactor.core.publisher.Flux.range(0, 100)
            .flatMap(
                i ->
                    VerifiedTenantContext.current()
                        .publishOn(Schedulers.parallel())
                        .map(VerifiedTenantContext::tenantId)
                        .contextWrite((i % 2 == 0 ? a : b)::install))
            .collectList()
            .block();
    assertEquals(50, values.stream().filter(TENANT::equals).count());
    StepVerifier.create(VerifiedTenantContext.current().then(Mono.never()).contextWrite(a::install))
        .thenCancel()
        .verify();
    StepVerifier.create(VerifiedTenantContext.current())
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void delegationCannotChangePrincipalOrExpandScopes() {
    var c = context();
    var parent = sender.authorize(c, RPC, POLICY).block();
    var changed =
        c.toBuilder().setContext(c.getContext().toBuilder().addScopes("platform")).build();
    rejects(sender.sign(parent, message(changed, "x"), RPC, POLICY), Status.Code.PERMISSION_DENIED);
  }

  @Test
  void nestedHopGetsFreshAssertionAndKeepsPrincipal() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    var verified = recipient.verify(m, signature, RPC, "a", POLICY).block();
    var next = recipient.delegate(verified, "c", "test.write", Set.of("write"));
    assertEquals("b", next.getContext().getIssuerService());
    assertEquals("c", next.getContext().getAudienceService());
    assertEquals(ACTOR, next.getContext().getActorUserId());
    assertEquals(TENANT, next.getContext().getTenantId());
    assertEquals(c.getHeader(), next.getHeader());
    assertNotEquals(c.getContext().getAssertionId(), next.getContext().getAssertionId());
    assertNotNull(recipient.sign(verified, message(next, "nested"), RPC, POLICY).block());
  }

  @Test
  void unavailableAuthorityAndReplayFailClosed() {
    var c = context();
    var m = message(c, "original");
    var signature = sign(c, m);
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            return new RpcContextSecurity.SigningKey("test-key", pair.getPrivate());
          }

          public PublicKey verificationKey(String issuer, String kid) {
            return pair.getPublic();
          }
        };
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var noAuthority =
        new RpcContextSecurity(
            "b",
            clock,
            keys,
            (issuer, jti, until) -> Mono.just(true),
            (ctx, rpc, p) -> Mono.error(new IllegalStateException("private")));
    rejects(noAuthority.verify(m, signature, RPC, "a", POLICY), Status.Code.UNAVAILABLE);
    var noReplay =
        new RpcContextSecurity(
            "b",
            clock,
            keys,
            (issuer, jti, until) -> Mono.error(new IllegalStateException("private")),
            (ctx, rpc, p) -> Mono.just(true));
    rejects(noReplay.verify(m, signature, RPC, "a", POLICY), Status.Code.UNAVAILABLE);
    assertTrue(consumed.isEmpty());
  }

  @Test
  void clientDeadlineAndCancellationStopPendingAuthority() {
    var c = context();
    var parent = sender.authorize(c, RPC, POLICY).block();
    var keys =
        new RpcContextSecurity.Keys() {
          public RpcContextSecurity.SigningKey signingKey(String issuer) {
            throw new AssertionError();
          }

          public PublicKey verificationKey(String issuer, String kid) {
            throw new AssertionError();
          }
        };
    var never =
        new RpcContextSecurity(
            "a",
            Clock.fixed(NOW, ZoneOffset.UTC),
            keys,
            (i, j, e) -> Mono.never(),
            (ctx, rpc, p) -> Mono.never());
    var interceptor = new TenantClientInterceptor(never, Map.of(RPC, POLICY));
    var method =
        io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(RPC)
            .setRequestMarshaller(
                io.grpc.protobuf.ProtoUtils.marshaller(
                    DynamicMessage.getDefaultInstance(descriptor)))
            .setResponseMarshaller(
                io.grpc.protobuf.ProtoUtils.marshaller(
                    DynamicMessage.getDefaultInstance(descriptor)))
            .build();
    var channel =
        new io.grpc.Channel() {
          public String authority() {
            return "test";
          }

          public <A, B> io.grpc.ClientCall<A, B> newCall(
              io.grpc.MethodDescriptor<A, B> m, io.grpc.CallOptions o) {
            throw new AssertionError("Transport must not start");
          }
        };
    var result = new CompletableFuture<io.grpc.Status>();
    var call =
        interceptor.interceptCall(
            method,
            io.grpc.CallOptions.DEFAULT
                .withOption(RpcContextBridge.OUTBOUND, parent)
                .withDeadlineAfter(20, TimeUnit.MILLISECONDS),
            channel);
    call.start(
        new io.grpc.ClientCall.Listener<>() {
          public void onClose(io.grpc.Status s, io.grpc.Metadata t) {
            result.complete(s);
          }
        },
        new io.grpc.Metadata());
    call.sendMessage(message(c, "pending"));
    try {
      assertEquals(Status.Code.DEADLINE_EXCEEDED, result.get(5, TimeUnit.SECONDS).getCode());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    var cancelled = new CompletableFuture<io.grpc.Status>();
    var context = io.grpc.Context.current().withCancellation();
    context.run(
        () -> {
          var pending =
              interceptor.interceptCall(
                  method,
                  io.grpc.CallOptions.DEFAULT.withOption(RpcContextBridge.OUTBOUND, parent),
                  channel);
          pending.start(
              new io.grpc.ClientCall.Listener<>() {
                public void onClose(io.grpc.Status s, io.grpc.Metadata t) {
                  cancelled.complete(s);
                }
              },
              new io.grpc.Metadata());
          pending.sendMessage(message(c, "pending"));
        });
    context.cancel(null);
    assertEquals(Status.Code.CANCELLED, cancelled.join().getCode());
  }

  @Test
  void receiverExpiryUsesBoundedClockSkewAndNeverAcceptsTheBoundary() {
    var c = context();
    var m = message(c, "expiry");
    var assertion = sign(c, m);
    var read = new RpcContextSecurity.Policy(true, false);
    var within =
        new RpcContextSecurity(
            "b",
            Clock.fixed(NOW.plusSeconds(34).plusNanos(999999999), ZoneOffset.UTC),
            keys,
            (issuer, jti, until) -> Mono.just(true),
            (ctx, rpc, p) -> Mono.just(true));
    assertNotNull(within.verify(m, assertion, RPC, "a", read).block());
    for (long seconds : List.of(35L, 36L, 120L)) {
      var expired =
          new RpcContextSecurity(
              "b",
              Clock.fixed(NOW.plusSeconds(seconds), ZoneOffset.UTC),
              keys,
              (issuer, jti, until) -> Mono.just(true),
              (ctx, rpc, p) -> Mono.just(true));
      rejects(expired.verify(m, assertion, RPC, "a", read), Status.Code.UNAUTHENTICATED);
    }
    var overlong =
        c.toBuilder()
            .setContext(c.getContext().toBuilder().setExpiresAt(NOW.plusSeconds(31).toString()))
            .build();
    rejects(sender.authorize(overlong, RPC, POLICY), Status.Code.UNAUTHENTICATED);
    var future =
        c.toBuilder()
            .setContext(
                c.getContext().toBuilder()
                    .setIssuedAt(NOW.plusSeconds(6).toString())
                    .setExpiresAt(NOW.plusSeconds(30).toString()))
            .build();
    rejects(sender.authorize(future, RPC, POLICY), Status.Code.UNAUTHENTICATED);
  }

  @Test
  void synchronousAuthorityProviderFailureIsUnavailable() {
    var broken =
        new RpcContextSecurity(
            "a",
            Clock.fixed(NOW, ZoneOffset.UTC),
            keys,
            (i, j, e) -> Mono.just(true),
            (c, r, p) -> {
              throw new IllegalStateException("private");
            });
    rejects(broken.authorize(context(), RPC, POLICY), Status.Code.UNAVAILABLE);
  }
}
