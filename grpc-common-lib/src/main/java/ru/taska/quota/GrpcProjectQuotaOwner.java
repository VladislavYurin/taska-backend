package ru.taska.quota;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.RpcContextSecurity;

public final class GrpcProjectQuotaOwner implements Owner {
  private final ReactorProjectQuotaOwnerServiceGrpc.ReactorProjectQuotaOwnerServiceStub stub;
  private final SignedQuotaClient client;

  public GrpcProjectQuotaOwner(
      ReactorProjectQuotaOwnerServiceGrpc.ReactorProjectQuotaOwnerServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer) {
    this.stub = java.util.Objects.requireNonNull(stub);
    this.client = new SignedQuotaClient(security, clock, issuer, "project-service");
  }

  public Mono<Receipt> inspect(Reservation r) {
    return client
        .call(
            r.tenant(),
            ProjectQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            "projectQuotaInspect",
            c ->
                ProjectQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(ProjectQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .inspect(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Receipt> cancel(Reservation r) {
    return client
        .call(
            r.tenant(),
            ProjectQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            "projectQuotaCancel",
            c ->
                ProjectQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(ProjectQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .cancel(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Receipt> open(Reservation r) {
    return client
        .call(
            r.tenant(),
            ProjectQuotaOwnerServiceGrpc.getOpenMethod().getFullMethodName(),
            "projectQuotaOpen",
            c ->
                ProjectQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(ProjectQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .open(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }

  public Mono<Inventory> inventory(UUID tenant) {
    return inventory(tenant, null);
  }

  public Mono<Inventory> inventory(UUID tenant, Reservation accepted) {
    return client
        .call(
            tenant,
            ProjectQuotaOwnerServiceGrpc.getInventoryMethod().getFullMethodName(),
            "projectQuotaInventory",
            c -> {
              var q =
                  ProjectQuotaOwnerInventoryRequest.newBuilder()
                      .setContext(c)
                      .setTenantId(tenant.toString());
              if (accepted != null) q.setAcceptedReservation(ProjectQuotaWire.encode(accepted));
              return q.build();
            },
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .inventory(Mono.just(q)))
        .map(ProjectQuotaWire::decode);
  }
}
