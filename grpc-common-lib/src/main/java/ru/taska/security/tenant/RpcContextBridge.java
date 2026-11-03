package ru.taska.security.tenant;

import io.grpc.CallOptions;
import reactor.core.publisher.Mono;

/** Capture inside the gRPC handler, before switching schedulers. Install per subscription. */
public final class RpcContextBridge {
  private RpcContextBridge() {}

  static final io.grpc.Context.Key<VerifiedTenantContext> INBOUND =
      io.grpc.Context.key("taska-verified-context");
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

  public static <T> Mono<T> inbound(Mono<T> work) {
    var verified = INBOUND.get();
    if (verified == null) return Mono.error(RpcContextSecurity.unauthenticated());
    return work.contextWrite(verified::install);
  }
}
