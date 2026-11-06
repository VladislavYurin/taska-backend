package ru.taska.billing.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.r2dbc.TenantTransactionExecutor;

/** Durable business-operation identity used to fence retries around external effects. */
public final class BillingOperationStore {
  public enum State { NEW, PROCESSING, UNKNOWN, SUCCEEDED, FAILED }

  public record Request(UUID tenantId, String kind, String requestKey, byte[] requestHash,
                        String channel, UUID actorId, UUID grantId) {
    public Request {
      Objects.requireNonNull(tenantId);
      PaymentProvider.require(kind, "kind");
      PaymentProvider.require(requestKey, "requestKey");
      Objects.requireNonNull(requestHash);
      PaymentProvider.require(channel, "channel");
    }
  }

  public record Operation(UUID id, UUID tenantId, String kind, String requestKey,
                          byte[] requestHash, State state, long fence, String result) {}

  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  public BillingOperationStore(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<Operation> accept(Request request) {
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    return transactions.execute(db -> {
      var statement = db.sql("""
        INSERT INTO taska.billing_operations
          (id,tenant_id,kind,actor_id,channel,grant_id,request_key,request_hash,state,fence,created_at,updated_at)
        VALUES (:id,:tenant,:kind,:actor,:channel,:grant,:key,:hash,'NEW',1,:now,:now)
        ON CONFLICT (tenant_id,kind,request_key) DO UPDATE
          SET updated_at = taska.billing_operations.updated_at
        RETURNING *
        """)
        .bind("id", id).bind("tenant", request.tenantId()).bind("kind", request.kind())
        .bind("channel", request.channel()).bind("key", request.requestKey())
        .bind("hash", request.requestHash()).bind("now", now);
      statement = request.actorId() == null ? statement.bindNull("actor", UUID.class)
          : statement.bind("actor", request.actorId());
      statement = request.grantId() == null ? statement.bindNull("grant", UUID.class)
          : statement.bind("grant", request.grantId());
      return statement.map((row, metadata) -> read(row)).one()
        .switchIfEmpty(find(request))
        .flatMap(existing -> java.util.Arrays.equals(existing.requestHash(), request.requestHash())
            ? Mono.just(existing)
            : Mono.error(new ProviderException(ProviderException.Reason.IDEMPOTENCY_CONFLICT)));
    });
  }

  public Mono<Operation> mark(Operation operation, State state, String result) {
    Instant now = clock.instant();
    return transactions.execute(db -> {
      var statement = db.sql("""
          UPDATE taska.billing_operations
             SET state=:state,result=CAST(:result AS jsonb),fence=fence+1,updated_at=:now
           WHERE tenant_id=:tenant AND id=:id RETURNING *
          """).bind("state", state.name()).bind("tenant", operation.tenantId())
          .bind("id", operation.id()).bind("now", now);
      statement = result == null ? statement.bindNull("result", String.class)
          : statement.bind("result", result);
      return statement.map((row, metadata) -> read(row)).one();
    });
  }

  private Mono<Operation> find(Request request) {
    return transactions.execute(db -> db.sql("""
        SELECT * FROM taska.billing_operations
         WHERE tenant_id=:tenant AND kind=:kind AND request_key=:key
        """).bind("tenant", request.tenantId()).bind("kind", request.kind())
        .bind("key", request.requestKey()).map((row, metadata) -> read(row)).one());
  }

  private static Operation read(io.r2dbc.spi.Row row) {
    return new Operation(row.get("id", UUID.class), row.get("tenant_id", UUID.class),
        row.get("kind", String.class), row.get("request_key", String.class),
        row.get("request_hash", byte[].class), State.valueOf(row.get("state", String.class)),
        row.get("fence", Long.class), row.get("result", String.class));
  }
}
