package ru.taska.security.tenant;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.BillingOwnershipTransferServiceGrpc;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.OwnershipTransferReceipt;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.ReactorBillingOwnershipTransferServiceGrpc;
import ru.taska.api.b2b.v1.ReactorTenantOwnershipTransferServiceGrpc;
import ru.taska.api.b2b.v1.TenantOwnershipTransferServiceGrpc;
import ru.taska.capability.ManagedDomainReads;

/** Точные message-bound SERVICE вызовы; мутации потребляют assertion через replay guard. */
public final class OwnershipTransferClient {
  private final ManagedDomainReads reads;
  private final ManagedServiceContexts contexts;
  private final RpcContextSecurity security;

  public OwnershipTransferClient(
      ManagedDomainReads reads, ManagedServiceContexts contexts, RpcContextSecurity security) {
    this.reads = reads;
    this.contexts = contexts;
    this.security = security;
  }

  @FunctionalInterface
  private interface Send<T> {
    Mono<T> invoke(OwnershipTransferRequest q, Metadata headers);
  }

  private <T> Mono<T> call(
      UUID tenant,
      UUID operation,
      String audience,
      String rpc,
      String action,
      String scope,
      boolean mutation,
      Send<T> send) {
    var policy = new RpcContextSecurity.Policy(true, mutation);
    return contexts
        .authorize(
            "billing",
            audience,
            rpc,
            action,
            tenant,
            Set.of(mutation ? "write" : "read", scope),
            policy)
        .flatMap(
            v -> {
              var q =
                  OwnershipTransferRequest.newBuilder()
                      .setContext(RpcContextBridge.details(v))
                      .setTenantId(tenant.toString())
                      .setOperationId(operation.toString())
                      .build();
              return security
                  .sign(v, q, rpc, policy)
                  .flatMap(
                      assertion -> {
                        var headers = new Metadata();
                        headers.put(
                            Metadata.Key.of("x-taska-context", Metadata.ASCII_STRING_MARSHALLER),
                            assertion);
                        return send.invoke(q, headers);
                      });
            });
  }

  public Mono<OwnershipTransferReceipt> inspect(UUID tenant, UUID operation) {
    return call(
        tenant,
        operation,
        "tenant-service",
        TenantOwnershipTransferServiceGrpc.getInspectMethod().getFullMethodName(),
        "inspectOwnershipTransfer",
        "ownership:inspect",
        false,
        (q, h) ->
            ReactorTenantOwnershipTransferServiceGrpc.newReactorStub(
                    reads.channel("tenant-service"))
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h))
                .inspect(q));
  }

  public Mono<OwnershipTransferFence> prepare(UUID tenant, UUID operation) {
    return call(
        tenant,
        operation,
        "billing-service",
        BillingOwnershipTransferServiceGrpc.getPrepareMethod().getFullMethodName(),
        "prepareOwnershipTransfer",
        "ownership:prepare",
        true,
        (q, h) ->
            ReactorBillingOwnershipTransferServiceGrpc.newReactorStub(
                    reads.channel("billing-service"))
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h))
                .prepare(q));
  }

  public Mono<OwnershipTransferFence> finish(UUID tenant, UUID operation) {
    return call(
        tenant,
        operation,
        "billing-service",
        BillingOwnershipTransferServiceGrpc.getFinishMethod().getFullMethodName(),
        "finishOwnershipTransfer",
        "ownership:finish",
        true,
        (q, h) ->
            ReactorBillingOwnershipTransferServiceGrpc.newReactorStub(
                    reads.channel("billing-service"))
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(h))
                .finish(q));
  }
}
