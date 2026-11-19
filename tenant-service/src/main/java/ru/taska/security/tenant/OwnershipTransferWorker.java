package ru.taska.security.tenant;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.TenantOwnershipTransferServiceGrpc;
import ru.taska.tenant.TenantOwnershipTransfers;

/** Ограниченный routing scan не даёт прав: каждая попытка заново получает service authority. */
public final class OwnershipTransferWorker {
  private final DatabaseClient dispatch;
  private final ManagedServiceContexts contexts;
  private final TenantOwnershipTransfers transfers;
  private final Clock clock;

  public OwnershipTransferWorker(
      DatabaseClient dispatch,
      ManagedServiceContexts contexts,
      TenantOwnershipTransfers transfers,
      Clock clock) {
    this.dispatch = dispatch;
    this.contexts = contexts;
    this.transfers = transfers;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${taska.tenant.ownership-poll-ms:1000}")
  public Mono<Void> poll() {
    return dispatch
        .sql(
            "SELECT tenant_id,operation_id FROM taska.tenant_ownership_dispatch WHERE completed_at"
                + " IS NULL AND next_attempt_at<=:now ORDER BY next_attempt_at,tenant_id LIMIT 20")
        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
        .fetch()
        .all()
        .concatMap(
            row -> {
              UUID tenant = (UUID) row.get("tenant_id"), operation = (UUID) row.get("operation_id");
              return contexts
                  .authorize(
                      "billing",
                      "tenant-service",
                      TenantOwnershipTransferServiceGrpc.getRecoverMethod().getFullMethodName(),
                      "recoverOwnershipTransfer",
                      tenant,
                      Set.of("write", "ownership:recover"),
                      new RpcContextSecurity.Policy(true, true))
                  .flatMap(
                      v ->
                          transfers
                              .recover(
                                  OwnershipTransferRequest.newBuilder()
                                      .setContext(RpcContextBridge.details(v))
                                      .setTenantId(tenant.toString())
                                      .setOperationId(operation.toString())
                                      .build())
                              .contextWrite(v::install))
                  .onErrorResume(
                      error ->
                          dispatch
                              .sql(
                                  "UPDATE taska.tenant_ownership_dispatch SET next_attempt_at=:next"
                                      + " WHERE tenant_id=:t AND operation_id=:op AND completed_at"
                                      + " IS NULL")
                              .bind("next", clock.instant().plusSeconds(5).atOffset(ZoneOffset.UTC))
                              .bind("t", tenant)
                              .bind("op", operation)
                              .then()
                              .then(Mono.empty()));
            })
        .then();
  }
}
