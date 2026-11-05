package ru.taska.quota;

import static ru.taska.quota.StorageQuotaProtocol.*;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.tenant.RpcContextSecurity;

public final class GrpcStorageQuotaOwner implements Owner {
  private final ReactorStorageQuotaOwnerServiceGrpc.ReactorStorageQuotaOwnerServiceStub stub;
  private final SignedQuotaClient client;

  public GrpcStorageQuotaOwner(
      ReactorStorageQuotaOwnerServiceGrpc.ReactorStorageQuotaOwnerServiceStub stub,
      RpcContextSecurity security,
      Clock clock,
      String issuer) {
    this.stub = java.util.Objects.requireNonNull(stub);
    this.client = new SignedQuotaClient(security, clock, issuer, "issue-service");
  }

  public Mono<Receipt> inspect(Reservation r) {
    return client
        .call(
            r.tenant(),
            StorageQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            "storageQuotaInspect",
            c ->
                StorageQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(StorageQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .inspect(Mono.just(q)))
        .map(StorageQuotaWire::decode);
  }

  public Mono<Receipt> cancel(Reservation r) {
    return client
        .call(
            r.tenant(),
            StorageQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            "storageQuotaCancel",
            c ->
                StorageQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(StorageQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .cancel(Mono.just(q)))
        .map(StorageQuotaWire::decode);
  }

  public Mono<Receipt> activate(Reservation r) {
    return client
        .call(
            r.tenant(),
            StorageQuotaOwnerServiceGrpc.getActivateMethod().getFullMethodName(),
            "storageQuotaActivate",
            c ->
                StorageQuotaOwnerRequest.newBuilder()
                    .setContext(c)
                    .setReservation(StorageQuotaWire.encode(r))
                    .build(),
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .activate(Mono.just(q)))
        .map(StorageQuotaWire::decode);
  }

  public Mono<ManifestPage> manifests(UUID tenant, UUID after, long version) {
    return client
        .call(
            tenant,
            StorageQuotaOwnerServiceGrpc.getManifestsMethod().getFullMethodName(),
            "storageQuotaManifests",
            c -> {
              var b =
                  StorageQuotaManifestRequest.newBuilder()
                      .setContext(c)
                      .setTenantId(tenant.toString())
                      .setSnapshotVersion(Long.toString(version));
              if (after != null) b.setAfter(after.toString());
              return b.build();
            },
            (q, auth) ->
                stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .manifests(Mono.just(q)))
        .map(StorageQuotaWire::decode);
  }
}
