package ru.taska.security.tenant;

import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;

/**
 * Raw mTLS control channel, independent of business assertion interceptors and bean dependencies.
 */
public final class RpcControlClient implements RpcTrustBackend, AutoCloseable {
  private final ManagedChannel channel;
  private final ReactorRpcTrustControlServiceGrpc.ReactorRpcTrustControlServiceStub stub;

  public RpcControlClient(ManagedChannel channel) {
    this.channel = channel;
    this.stub = ReactorRpcTrustControlServiceGrpc.newReactorStub(channel);
  }

  public Mono<Boolean> allowed(
      RequestContext context, String rpc, RpcContextSecurity.Policy policy) {
    return stub.withDeadlineAfter(3, TimeUnit.SECONDS)
        .checkRpcAuthority(
            CheckRpcAuthorityRequest.newBuilder()
                .setContext(context)
                .setRpc(rpc)
                .setTenantRequired(policy.tenantRequired())
                .setMutation(policy.mutation())
                .build())
        .map(RpcTrustDecision::getAllowed);
  }

  public Mono<Boolean> consume(String recipient, String issuer, String jti, Instant retainUntil) {
    return stub.withDeadlineAfter(3, TimeUnit.SECONDS)
        .consumeRpcAssertion(
            ConsumeRpcAssertionRequest.newBuilder()
                .setRecipient(recipient)
                .setIssuer(issuer)
                .setAssertionId(jti)
                .setRetainUntil(retainUntil.toString())
                .build())
        .map(RpcTrustDecision::getAllowed);
  }

  public Mono<Boolean> currentKey(
      String recipient, String issuer, String kid, String peerFingerprint) {
    var request =
        CheckRpcKeyRequest.newBuilder().setRecipient(recipient).setIssuer(issuer).setKid(kid);
    if (peerFingerprint != null) request.setPeerCertificateSha256(peerFingerprint);
    return stub.withDeadlineAfter(3, TimeUnit.SECONDS)
        .checkRpcKey(request.build())
        .map(RpcTrustDecision::getAllowed);
  }

  @Override
  public void close() {
    channel.shutdownNow();
  }

  public Mono<RpcActorFacts> actorFacts(
      RequestContext context, String rpc, RpcContextSecurity.Policy policy) {
    return stub.withDeadlineAfter(3, TimeUnit.SECONDS)
        .getRpcActorFacts(
            CheckRpcAuthorityRequest.newBuilder()
                .setContext(context)
                .setRpc(rpc)
                .setTenantRequired(policy.tenantRequired())
                .setMutation(policy.mutation())
                .build());
  }
}
