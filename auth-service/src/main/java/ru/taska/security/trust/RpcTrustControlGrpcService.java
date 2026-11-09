package ru.taska.security.trust;

import io.grpc.Status;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.*;

@GrpcService
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class RpcTrustControlGrpcService
    extends ReactorRpcTrustControlServiceGrpc.RpcTrustControlServiceImplBase {
  private final AuthRpcTrustBackend backend;

  public RpcTrustControlGrpcService(AuthRpcTrustBackend backend) {
    this.backend = backend;
  }

  @Override
  public Mono<RpcTrustDecision> checkRpcAuthority(Mono<CheckRpcAuthorityRequest> input) {
    String peer = ManagedRpcPeerInterceptor.PEER.get();
    return input
        .flatMap(
            r -> {
              var c = r.getContext().getContext();
              if (peer == null
                  || (!peer.equals(c.getIssuerService()) && !peer.equals(c.getAudienceService())))
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              return backend.allowed(
                  r.getContext(),
                  r.getRpc(),
                  new RpcContextSecurity.Policy(r.getTenantRequired(), r.getMutation()));
            })
        .switchIfEmpty(Mono.error(ManagedRpcTrust.unavailable()))
        .map(v -> RpcTrustDecision.newBuilder().setAllowed(v).build())
        .onErrorMap(
            e -> e instanceof io.grpc.StatusRuntimeException ? e : ManagedRpcTrust.unavailable());
  }

  @Override
  public Mono<RpcTrustDecision> consumeRpcAssertion(Mono<ConsumeRpcAssertionRequest> input) {
    String peer = ManagedRpcPeerInterceptor.PEER.get();
    return input
        .flatMap(
            r -> {
              if (peer == null || !peer.equals(r.getRecipient()))
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              return backend.consume(
                  r.getRecipient(),
                  r.getIssuer(),
                  r.getAssertionId(),
                  Instant.parse(r.getRetainUntil()));
            })
        .switchIfEmpty(Mono.error(ManagedRpcTrust.unavailable()))
        .map(v -> RpcTrustDecision.newBuilder().setAllowed(v).build())
        .onErrorMap(
            e -> e instanceof io.grpc.StatusRuntimeException ? e : ManagedRpcTrust.unavailable());
  }

  @Override
  public Mono<RpcTrustDecision> checkRpcKey(Mono<CheckRpcKeyRequest> input) {
    String peer = ManagedRpcPeerInterceptor.PEER.get();
    return input
        .flatMap(
            r -> {
              String fingerprint = r.getPeerCertificateSha256();
              boolean signing = fingerprint.isEmpty() && r.getRecipient().equals(r.getIssuer());
              if (peer == null
                  || !peer.equals(r.getRecipient())
                  || (!signing && !fingerprint.matches("[a-f0-9]{64}")))
                return Mono.error(Status.PERMISSION_DENIED.asRuntimeException());
              return backend.currentKey(
                  r.getRecipient(), r.getIssuer(), r.getKid(), signing ? null : fingerprint);
            })
        .switchIfEmpty(Mono.error(ManagedRpcTrust.unavailable()))
        .map(v -> RpcTrustDecision.newBuilder().setAllowed(v).build())
        .onErrorMap(
            e -> e instanceof io.grpc.StatusRuntimeException ? e : ManagedRpcTrust.unavailable());
  }
}
