package ru.taska.billing.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.r2dbc.TenantTransactionExecutor;

/** Durable PSP identity. Every result is emitted after its transaction commits. */
public final class PaymentAttemptStore {
  public enum State {
    ACCEPTED,
    SUBMITTED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
  }

  public record Attempt(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      UUID operationId,
      String provider,
      String providerIdempotencyKey,
      String providerPaymentId,
      String invoiceSnapshot,
      String merchantAccount,
      PaymentProvider.Environment environment,
      State state,
      Instant acceptedAt,
      Instant updatedAt,
      long version,
      long amountMinor,
      String currency,
      String profileVersion) {
    public PaymentProvider.Merchant merchant() {
      return new PaymentProvider.Merchant(merchantAccount, environment);
    }

    public boolean terminal() {
      return state == State.SUCCEEDED || state == State.FAILED || state == State.CANCELLED;
    }
  }

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
      PaymentProvider.require(providerIdempotencyKey, "key");
      if (providerIdempotencyKey.length() > 128)
        throw new IllegalArgumentException("Bounded payment key required");
      PaymentProvider.require(merchantAccount, "merchant");
      Objects.requireNonNull(environment);
      PaymentProvider.require(invoiceSnapshot, "snapshot");
    }
  }

  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  public PaymentAttemptStore(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = Objects.requireNonNull(transactions);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<String> profileVersion(Request request, PaymentProvider.Invoice invoice) {
    return transactions.execute(
        db ->
            db.sql(
                    "SELECT pricing_snapshot->>'profileVersion' AS profile FROM taska.invoices"
                        + " WHERE tenant_id=:tenant AND id=:invoice AND method='PSP' AND"
                        + " state<>'DRAFT' AND total_minor=:amount AND currency=:currency")
                .bind("tenant", request.tenantId())
                .bind("invoice", request.invoiceId())
                .bind("amount", invoice.total().amountMinor())
                .bind("currency", invoice.total().currency())
                .map((r, m) -> r.get("profile", String.class))
                .one()
                .switchIfEmpty(Mono.error(conflict())));
  }

  public Mono<String> canonicalSnapshot(Request request) {
    return transactions.execute(
        db ->
            db.sql("SELECT CAST(CAST(:snapshot AS jsonb) AS text)")
                .bind("snapshot", request.invoiceSnapshot())
                .map((r, m) -> r.get(0, String.class))
                .one());
  }

  public Mono<Attempt> accept(Request request, UUID operation, PaymentProvider.Invoice invoice) {
    if (!request.invoiceId().equals(invoice.invoiceId())) return Mono.error(conflict());
    return transactions
        .execute(
            db ->
                db.sql(
                        """
                                    INSERT INTO taska.payment_attempts
                                      (id,tenant_id,invoice_id,operation_id,provider,provider_idempotency_key,merchant_account,
                                       provider_environment,request_snapshot,invoice_snapshot,state,accepted_at,updated_at,amount_minor,currency,merchant_profile_version)
                                    SELECT :id,:tenant,:invoice,:operation,:provider,:key,:merchant,:environment,
                                           CAST(:snapshot AS jsonb),jsonb_build_object('invoiceId',i.id,'operationId',i.operation_id,
                          'planVersionId',i.plan_version_id,'purpose',i.purpose,'totalMinor',i.total_minor,'currency',i.currency,
                          'terms',i.terms_snapshot,'pricing',i.pricing_snapshot,'seller',i.seller_snapshot,'buyer',i.buyer_snapshot,
                          'document',i.snapshot),'ACCEPTED',:now,:now,i.total_minor,i.currency,i.pricing_snapshot->>'profileVersion'
                                      FROM taska.invoices i WHERE i.tenant_id=:tenant AND i.id=:invoice AND i.method='PSP'
                                       AND i.total_minor=:amount AND i.currency=:currency AND i.pricing_snapshot->>'profileVersion' IS NOT NULL
                                       AND ((i.state='OPEN' AND i.payable_until>:now) OR EXISTS
                                         (SELECT 1 FROM taska.payment_attempts p WHERE p.tenant_id=:tenant AND p.operation_id=:operation))
                                    ON CONFLICT (provider,provider_idempotency_key) DO UPDATE
                                      SET updated_at=taska.payment_attempts.updated_at
                                      WHERE taska.payment_attempts.tenant_id=EXCLUDED.tenant_id
                                        AND taska.payment_attempts.invoice_id=EXCLUDED.invoice_id
                                        AND taska.payment_attempts.operation_id=EXCLUDED.operation_id
                                        AND taska.payment_attempts.invoice_snapshot=EXCLUDED.invoice_snapshot
                        AND taska.payment_attempts.request_snapshot=EXCLUDED.request_snapshot
                                        AND taska.payment_attempts.merchant_account=EXCLUDED.merchant_account
                                        AND taska.payment_attempts.provider_environment=EXCLUDED.provider_environment
                                        AND taska.payment_attempts.amount_minor=EXCLUDED.amount_minor
                                        AND taska.payment_attempts.currency=EXCLUDED.currency
                        AND taska.payment_attempts.merchant_profile_version=EXCLUDED.merchant_profile_version
                                    RETURNING *,invoice_snapshot::text AS snapshot_document
                        """)
                    .bind("id", UUID.randomUUID())
                    .bind("tenant", request.tenantId())
                    .bind("invoice", request.invoiceId())
                    .bind("operation", operation)
                    .bind("provider", request.provider())
                    .bind("key", request.providerIdempotencyKey())
                    .bind("merchant", request.merchantAccount())
                    .bind("environment", request.environment().name())
                    .bind("snapshot", request.invoiceSnapshot())
                    .bind("now", clock.instant())
                    .bind("amount", invoice.total().amountMinor())
                    .bind("currency", invoice.total().currency())
                    .map((r, m) -> read(r))
                    .one()
                    .switchIfEmpty(Mono.error(conflict())))
        .onErrorResume(
            org.springframework.dao.DuplicateKeyException.class,
            error ->
                // A concurrent identical insert can hit the non-arbiter active-invoice index first.
                // execute() has rolled back before this separate read; only the same durable
                // operation may replay.
                transactions.execute(
                    db ->
                        db.sql(
                                """
                                SELECT *,invoice_snapshot::text AS snapshot_document FROM taska.payment_attempts
                                 WHERE tenant_id=:tenant AND operation_id=:operation AND invoice_id=:invoice
                                   AND provider=:provider AND provider_idempotency_key=:key
                                   AND merchant_account=:merchant AND provider_environment=:environment
                                   AND request_snapshot=CAST(:snapshot AS jsonb)
                                   AND amount_minor=:amount AND currency=:currency
                                """)
                            .bind("tenant", request.tenantId())
                            .bind("operation", operation)
                            .bind("invoice", request.invoiceId())
                            .bind("provider", request.provider())
                            .bind("key", request.providerIdempotencyKey())
                            .bind("merchant", request.merchantAccount())
                            .bind("environment", request.environment().name())
                            .bind("snapshot", request.invoiceSnapshot())
                            .bind("amount", invoice.total().amountMinor())
                            .bind("currency", invoice.total().currency())
                            .map((r, m) -> read(r))
                            .one()
                            .switchIfEmpty(Mono.error(conflict()))));
  }

  /** Only the transaction that changes ACCEPTED to SUBMITTED may invoke createCheckout. */
  public Mono<Attempt> claim(Attempt attempt) {
    return transactions.execute(
        db ->
            db.sql(
                    """
                    UPDATE taska.payment_attempts SET state='SUBMITTED',version=version+1,updated_at=:now
                     WHERE tenant_id=:tenant AND id=:id AND state='ACCEPTED' AND version=:version
                    RETURNING *,invoice_snapshot::text AS snapshot_document
                    """)
                .bind("tenant", attempt.tenantId())
                .bind("id", attempt.id())
                .bind("version", attempt.version())
                .bind("now", clock.instant())
                .map((r, m) -> read(r))
                .one());
  }

  public Mono<Attempt> find(UUID tenant, UUID id) {
    return transactions.execute(db -> find(db, tenant, id, false));
  }

  private Mono<Attempt> find(DatabaseClient db, UUID tenant, UUID id, boolean lock) {
    return db.sql(
            "SELECT *,invoice_snapshot::text AS snapshot_document FROM taska.payment_attempts"
                + " WHERE tenant_id=:tenant AND id=:id"
                + (lock ? " FOR UPDATE" : ""))
        .bind("tenant", tenant)
        .bind("id", id)
        .map((r, m) -> read(r))
        .one()
        .switchIfEmpty(Mono.error(new ProviderException(ProviderException.Reason.NOT_FOUND)));
  }

  public Mono<Attempt> mark(Attempt attempt, State state, String payment) {
    if (payment != null) PaymentProvider.require(payment, "providerPaymentId");
    return transactions.execute(
        db ->
            find(db, attempt.tenantId(), attempt.id(), true)
                .flatMap(
                    current -> {
                      if (current.providerPaymentId() != null
                          && payment != null
                          && !current.providerPaymentId().equals(payment))
                        return Mono.error(
                            new ProviderException(ProviderException.Reason.INVALID_STATE));
                      if (current.version() != attempt.version() || current.terminal())
                        return Mono.just(current);
                      if (current.state() == State.ACCEPTED
                          || state == State.ACCEPTED
                          || state == State.SUBMITTED)
                        return Mono.error(
                            new ProviderException(ProviderException.Reason.INVALID_STATE));
                      if (state == State.SUCCEEDED
                          && payment == null
                          && current.providerPaymentId() == null)
                        return Mono.error(
                            new ProviderException(ProviderException.Reason.INVALID_STATE));
                      var s =
                          db.sql(
                                  """
                                  UPDATE taska.payment_attempts SET state=:state,
                                    provider_payment_id=COALESCE(:payment,provider_payment_id),version=version+1,updated_at=:now
                                  WHERE tenant_id=:tenant AND id=:id AND version=:version
                                  RETURNING *,invoice_snapshot::text AS snapshot_document
                                  """)
                              .bind("state", state.name())
                              .bind("tenant", current.tenantId())
                              .bind("id", current.id())
                              .bind("version", current.version())
                              .bind("now", clock.instant());
                      s =
                          payment == null
                              ? s.bindNull("payment", String.class)
                              : s.bind("payment", payment);
                      return s.map((r, m) -> read(r)).one();
                    }));
  }

  public Mono<Attempt> unknown(Attempt attempt) {
    return mark(attempt, State.UNKNOWN, null);
  }

  private static ProviderException conflict() {
    return new ProviderException(ProviderException.Reason.IDEMPOTENCY_CONFLICT);
  }

  private static Attempt read(io.r2dbc.spi.Row r) {
    return new Attempt(
        r.get("id", UUID.class),
        r.get("tenant_id", UUID.class),
        r.get("invoice_id", UUID.class),
        r.get("operation_id", UUID.class),
        r.get("provider", String.class),
        r.get("provider_idempotency_key", String.class),
        r.get("provider_payment_id", String.class),
        r.get("snapshot_document", String.class),
        r.get("merchant_account", String.class),
        PaymentProvider.Environment.valueOf(r.get("provider_environment", String.class)),
        State.valueOf(r.get("state", String.class)),
        r.get("accepted_at", Instant.class),
        r.get("updated_at", Instant.class),
        r.get("version", Long.class),
        r.get("amount_minor", Long.class),
        r.get("currency", String.class),
        r.get("merchant_profile_version", String.class));
  }
}
