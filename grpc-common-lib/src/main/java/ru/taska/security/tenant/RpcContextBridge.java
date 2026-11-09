package ru.taska.security.tenant;

import io.grpc.CallOptions;
import reactor.core.publisher.Mono;

/** Capture inside the gRPC handler, before switching schedulers. Install per subscription. */
public final class RpcContextBridge {
  private RpcContextBridge() {}

  static final io.grpc.Context.Key<VerifiedTenantContext> INBOUND =
      io.grpc.Context.key("taska-verified-context");
  private static final Object TRANSPORT_CONTEXT = new Object();
  static final io.grpc.Context.Key<VerifiedTenantContext> OUTBOUND_INHERITED =
      io.grpc.Context.key("taska-outbound-verified-context");
  public static final CallOptions.Key<VerifiedTenantContext> OUTBOUND =
      CallOptions.Key.create("taska-verified-context");

  /** Immutable full verified claims for adapters that need credential/grant/generation details. */
  public static ru.taska.api.b2b.v1.RequestContext details(VerifiedTenantContext verified) {
    try {
      return ru.taska.api.b2b.v1.RequestContext.parseFrom(verified.wire());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException("Invalid verified context");
    }
  }

  /** Trace-only header for internal commands created inside outbound(Supplier). */
  public static ru.taska.api.common.v1.Header outboundHeader(String node) {
    var parent = OUTBOUND_INHERITED.get();
    return ru.taska.api.common.v1.Header.newBuilder()
        .setNodeId(node)
        .setRequestId(parent == null ? java.util.UUID.randomUUID().toString() : parent.requestId())
        .build();
  }

  public static <T> Mono<T> install(Mono<T> work, VerifiedTenantContext verified) {
    return work.contextWrite(
        c -> verified.install(c).put(TRANSPORT_CONTEXT, io.grpc.Context.current()));
  }

  /** Capture verified inbound provenance while the transport invokes the handler. */
  public static <T> Mono<T> inboundIfPresent(Mono<T> work) {
    var verified = INBOUND.get();
    var transport = io.grpc.Context.current();
    return verified == null
        ? work
        : work.contextWrite(c -> verified.install(c).put(TRANSPORT_CONTEXT, transport));
  }

  /** Recreate the generated single-subscription gRPC publisher for each retry/subscription. */
  public static <T> Mono<T> outbound(java.util.function.Supplier<Mono<T>> call) {
    return outbound(Mono.defer(call));
  }

  /** Bind only call creation to gRPC Context; Reactor remains the request-local source. */
  public static <T> Mono<T> outbound(Mono<T> work) {
    return Mono.deferContextual(
        context ->
            new Mono<T>() {
              @Override
              public void subscribe(reactor.core.CoreSubscriber<? super T> subscriber) {
                if (!context.hasKey(VerifiedTenantContext.class)) {
                  work.subscribe(subscriber);
                  return;
                }
                var parent = VerifiedTenantContext.require(context);
                io.grpc.Context transport =
                    context.getOrDefault(TRANSPORT_CONTEXT, io.grpc.Context.current());
                transport
                    .withValue(OUTBOUND_INHERITED, parent)
                    .run(() -> work.subscribe(subscriber));
              }
            });
  }

  public static <T> Mono<T> inbound(Mono<T> work) {
    var verified = INBOUND.get();
    if (verified == null) return Mono.error(RpcContextSecurity.unauthenticated());
    var transport = io.grpc.Context.current();
    return work.contextWrite(c -> verified.install(c).put(TRANSPORT_CONTEXT, transport));
  }
}
