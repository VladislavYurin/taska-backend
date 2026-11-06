package ru.taska.billing.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.r2dbc.TenantTransactionExecutor;

/** Durable identity of a PSP charge. Provider retries must consult this store first. */
public final class PaymentAttemptStore {
  public enum State { ACCEPTED, SUBMITTED, PENDING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      String provider,
      String providerIdempotencyKey,
      String providerPaymentId,
      String invoiceSnapshot,
      String merchantAccount,
      PaymentProvider.Environment environment,
      State state,
      Instant acceptedAt,
      Instant updatedAt) {}

  public record Request(
      UUID tenantId,
      UUID invoiceId,
      String provider,
      String providerIdempotencyKey,
      String merchantAccount,
      PaymentProvider.Environment environment,
      String invoiceSnapshot) {
    public Request {
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(invoiceId);
      PaymentProvider.require(provider, "provider");
      PaymentProvider.require(providerIdempotencyKey, "providerIdempotencyKey");
      PaymentProvider.require(merchantAccount, "merchantAccount");
      Objects.requireNonNull(environment);
      PaymentProvider.require(invoiceSnapshot, "invoiceSnapshot");
    }
  }

  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  public PaymentAttemptStore(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Insert-or-read is the only entry point before a provider network call. */
  public Mono<Attempt> accept(Request request) {
    Instant now = clock.instant();
    UUID id = UUID.randomUUID();
    return transactions.execute(db ->
        db.sql("""
            INSERT INTO taska.payment_attempts
              (id,tenant_id,invoice_id,provider,provider_idempotency_key,merchant_account,
               provider_environment,invoice_snapshot,state,accepted_at,updated_at)
            VALUES (:id,:tenant,:invoice,:provider,:key,:merchant,:environment,CAST(:snapshot AS jsonb),'ACCEPTED',:now,:now)
            ON CONFLICT (provider,provider_idempotency_key) DO UPDATE
              SET updated_at = taska.payment_attempts.updated_at
            RETURNING *
            """)
            .bind("id", id)
            .bind("tenant", request.tenantId())
            .bind("invoice", request.invoiceId())
            .bind("provider", request.provider())
            .bind("key", request.providerIdempotencyKey())
            .bind("merchant", request.merchantAccount())
            .bind("environment", request.environment().name())
            .bind("snapshot", request.invoiceSnapshot())
            .bind("now", now)
            .map((row, metadata) -> read(row))
            .one()
            .switchIfEmpty(find(request))
            .flatMap(existing -> matches(existing, request) ? Mono.just(existing)
                : Mono.error(new ProviderException(ProviderException.Reason.IDEMPOTENCY_CONFLICT))));
  }

  public Mono<Attempt> mark(Attempt attempt, State state, String providerPaymentId) {
    Objects.requireNonNull(attempt);
    Objects.requireNonNull(state);
    Instant now = clock.instant();
    return transactions.execute(db -> {
      var statement = db.sql("""
          UPDATE taska.payment_attempts
             SET state=:state, provider_payment_id=:payment, updated_at=:now
           WHERE tenant_id=:tenant AND id=:id
           RETURNING *
          """)
          .bind("state", state.name())
          .bind("now", now)
          .bind("tenant", attempt.tenantId())
          .bind("id", attempt.id());
      statement = providerPaymentId == null
          ? statement.bindNull("payment", String.class)
          : statement.bind("payment", providerPaymentId);
      return statement.map((row, metadata) -> read(row)).one();
    });
  }

  public Mono<Attempt> unknown(Attempt attempt) {
    return mark(attempt, State.UNKNOWN, attempt.providerPaymentId());
  }

  private Mono<Attempt> find(Request request) {
    return transactions.execute(db -> db.sql("""
        SELECT * FROM taska.payment_attempts
         WHERE provider=:provider AND provider_idempotency_key=:key
        """)
        .bind("provider", request.provider())
        .bind("key", request.providerIdempotencyKey())
        .map((row, metadata) -> read(row)).one());
  }

  private static boolean matches(Attempt attempt, Request request) {
    return attempt.tenantId().equals(request.tenantId())
        && attempt.invoiceId().equals(request.invoiceId())
        && attempt.invoiceSnapshot().equals(request.invoiceSnapshot())
        && attempt.merchantAccount().equals(request.merchantAccount())
        && attempt.environment() == request.environment();
  }

  private static Attempt read(io.r2dbc.spi.Row row) {
    return new Attempt(
        row.get("id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("invoice_id", UUID.class),
        row.get("provider", String.class),
        row.get("provider_idempotency_key", String.class),
        row.get("provider_payment_id", String.class),
        row.get("invoice_snapshot", String.class),
        row.get("merchant_account", String.class),
        PaymentProvider.Environment.valueOf(row.get("provider_environment", String.class)),
        State.valueOf(row.get("state", String.class)),
        row.get("accepted_at", Instant.class),
        row.get("updated_at", Instant.class));
  }
}
