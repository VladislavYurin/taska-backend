package ru.taska.quota;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.*;

public final class GrpcProjectQuotaAuthority implements ProjectQuotaProtocol.Authority {
  private final ReactorQuotaAuthorityServiceGrpc.ReactorQuotaAuthorityServiceStub stub;
  private final SignedQuotaClient client;

  public GrpcProjectQuotaAuthority(
      ReactorQuotaAuthorityServiceGrpc.ReactorQuotaAuthorityServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer) {
    this.stub = java.util.Objects.requireNonNull(stub);
    this.client = new SignedQuotaClient(security, clock, issuer, "tenant-service");
  }

  public Mono<Reservation> reserve(UUID operation, UUID resource, Instant expiry) {
    return VerifiedTenantContext.current()
        .flatMap(
            v ->
                client.call(
                    UUID.fromString(v.tenantId()),
                    QuotaAuthorityServiceGrpc.getReserveQuotaMethod().getFullMethodName(),
                    "reserveQuota",
                    c ->
                        ReserveQuotaRpcRequest.newBuilder()
                            .setContext(c)
                            .setPayload(
                                ReserveQuotaRequest.newBuilder()
                                    .setOperationId(operation.toString())
                                    .setResourceId(resource.toString())
                                    .setQuantity("1")
                                    .setKind(ReserveQuotaRequest.KindValue.KIND_PROJECT)
                                    .setExpiresAt(expiry.toString()))
                            .build(),
                    (q, auth) ->
                        stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .reserveQuota(Mono.just(q))))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Reservation> get(UUID operation) {
    return VerifiedTenantContext.current()
        .flatMap(
            v ->
                client.call(
                    UUID.fromString(v.tenantId()),
                    QuotaAuthorityServiceGrpc.getGetQuotaReservationMethod().getFullMethodName(),
                    "getQuotaReservation",
                    c ->
                        GetQuotaReservationRequest.newBuilder()
                            .setContext(c)
                            .setOperationId(operation.toString())
                            .build(),
                    (q, auth) ->
                        stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withInterceptors(auth)
                            .getQuotaReservation(Mono.just(q))))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Reservation> commit(Reservation r) {
    return client
        .call(
            r.tenant(),
            QuotaAuthorityServiceGrpc.getCommitQuotaMethod().getFullMethodName(),
            "commitQuota",
            c ->
                FinalizeQuotaRpcRequest.newBuilder()
                    .setContext(c)
                    .setReservationId(r.id().toString())
                    .setFence(Long.toString(r.fence()))
                    .setPayload(
                        FinalizeQuotaRequest.newBuilder()
                            .setOperationId(r.operation().toString())
                            .setResourceId(r.resource().toString())
                            .setActualQuantity("1"))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .commitQuota(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Reservation> release(Reservation r) {
    return client
        .call(
            r.tenant(),
            QuotaAuthorityServiceGrpc.getReleaseQuotaMethod().getFullMethodName(),
            "releaseQuota",
            c ->
                FinalizeQuotaRpcRequest.newBuilder()
                    .setContext(c)
                    .setReservationId(r.id().toString())
                    .setFence(Long.toString(r.fence()))
                    .setPayload(
                        FinalizeQuotaRequest.newBuilder()
                            .setOperationId(r.operation().toString())
                            .setResourceId(r.resource().toString())
                            .setActualQuantity("1"))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .releaseQuota(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }
}
