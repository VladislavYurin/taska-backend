package ru.taska.billing.ingress;

import static ru.taska.billing.ingress.ProviderWebhookRegistry.failure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.json.JsonMapper;

/** Global authenticated transport inbox. Only local immutable routes may assign its tenant. */
public final class ProviderInboxStore {
  public record Receipt(UUID id, String state, String reason, UUID evidenceId) {}

  public record Pending(UUID receiptId, UUID tenantId) {}

  private final DatabaseClient db;
  private final TransactionalOperator globalTx;
  private final TenantTransactionExecutor tenants;
  private final InboxCipher cipher;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public ProviderInboxStore(
      DatabaseClient db,
      TransactionalOperator globalTx,
      TenantTransactionExecutor tenants,
      InboxCipher cipher,
      Clock clock) {
    this.db = db;
    this.globalTx = globalTx;
    this.tenants = tenants;
    this.cipher = cipher;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime offset(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static DatabaseClient.GenericExecuteSpec identity(
      DatabaseClient.GenericExecuteSpec sql, ProviderWebhookRegistry.Identity i) {
    return sql.bind("provider", i.provider())
        .bind("account", i.account())
        .bind("environment", i.environment().name());
  }

  public Mono<Void> admit(ProviderWebhookRegistry.Identity i, int perMinute) {
    return Mono.defer(
        () ->
            identity(
                    db.sql(
                        "INSERT INTO"
                            + " taska.provider_ingress_limits(provider,provider_account_id,environment,bucket,attempts)"
                            + " VALUES(:provider,:account,:environment,:bucket,1) ON"
                            + " CONFLICT(provider,provider_account_id,environment,bucket) DO UPDATE"
                            + " SET attempts=taska.provider_ingress_limits.attempts+1 WHERE"
                            + " taska.provider_ingress_limits.attempts<:limit RETURNING attempts"),
                    i)
                .bind("bucket", offset(now().truncatedTo(ChronoUnit.MINUTES)))
                .bind("limit", perMinute)
                .fetch()
                .one()
                .switchIfEmpty(
                    Mono.error(failure(DomainStatus.RESOURCE_EXHAUSTED, "WEBHOOK_RATE_LIMIT")))
                .then());
  }

  public Mono<Receipt> accept(
      ProviderWebhookRegistry.Identity identity,
      ProviderWebhookRegistry.Observation observation,
      byte[] raw) {
    return Mono.defer(
        () -> {
          UUID id = UUID.randomUUID();
          Instant received = now();
          var sealed = cipher.seal(id, raw);
          var body = new HashMap<String, Object>();
          body.put("type", observation.eventType());
          var event = observation.payment();
          String state = "IGNORED", reason = "UNSUPPORTED_EVENT";
          if (event != null) {
            body.put("paymentId", event.providerPaymentId());
            body.put("invoiceId", event.invoiceId().toString());
            body.put("amount", event.amount().amountMinor());
            body.put("currency", event.amount().currency());
            body.put("occurredAt", event.occurredAt().truncatedTo(ChronoUnit.MICROS).toString());
            body.put("kind", event.kind().name());
            if (event.providerRefundId() != null) body.put("refundId", event.providerRefundId());
            if (event.kind() == PaymentProvider.EventKind.PAYMENT
                && event.paymentState() == PaymentProvider.State.SUCCEEDED) {
              state = "RECEIVED";
              reason = "AWAITING_MAPPING";
            } else if (event.kind() == PaymentProvider.EventKind.REFUND) {
              state = "QUARANTINED";
              reason = "REFUND_OWNER_REQUIRED";
            } else reason = "NON_SUCCESS_OUTCOME";
          }
          String digest = sha(raw), document = json.writeValueAsString(body);
          var insert =
              identity(
                      db.sql(
                          "INSERT INTO"
                              + " taska.provider_inbox(id,provider,provider_account_id,environment,event_id,payload_digest,"
                              + "payload_key_id,payload_nonce,payload_ciphertext,observation,signature_verified,state,reason_code,received_at,next_attempt_at,processed_at)"
                              + " VALUES(:id,:provider,:account,:environment,:event,:digest,:key,:nonce,:cipher,CAST(:document"
                              + " AS jsonb),true,:state,:reason,:now,:now,:processed) ON"
                              + " CONFLICT(provider,provider_account_id,environment,event_id) DO"
                              + " NOTHING"),
                      identity)
                  .bind("id", id)
                  .bind("event", observation.eventId())
                  .bind("digest", digest)
                  .bind("key", sealed.keyId())
                  .bind("nonce", sealed.nonce())
                  .bind("cipher", sealed.ciphertext())
                  .bind("document", document)
                  .bind("state", state)
                  .bind("reason", reason)
                  .bind("now", offset(received));
          insert =
              state.equals("IGNORED")
                  ? insert.bind("processed", offset(received))
                  : insert.bindNull("processed", OffsetDateTime.class);
          return insert
              .fetch()
              .rowsUpdated()
              .then(
                  identity(
                          db.sql(
                              "SELECT * FROM taska.provider_inbox WHERE provider=:provider AND"
                                  + " provider_account_id=:account AND environment=:environment AND"
                                  + " event_id=:event"),
                          identity)
                      .bind("event", observation.eventId())
                      .fetch()
                      .one())
              .map(
                  row -> {
                    if (!digest.equals(row.get("payload_digest")))
                      throw failure(DomainStatus.ABORTED, "WEBHOOK_EVENT_CONFLICT");
                    return receipt(row);
                  })
              .as(globalTx::transactional);
        });
  }

  /** Called only by authenticated billing, after it learns the provider object identity. */
  public Mono<Void> bind(
      UUID tenant,
      UUID invoice,
      ProviderWebhookRegistry.Registration registration,
      String paymentId) {
    ProviderWebhookRegistry.text(paymentId, 200);
    return tenants.execute(
        tx ->
            tx.sql(
                    "SELECT operation_id,pricing_snapshot->>'profileVersion' AS profile FROM"
                        + " taska.invoices WHERE tenant_id=:tenant AND id=:invoice AND method='PSP'"
                        + " AND state<>'DRAFT'")
                .bind("tenant", tenant)
                .bind("invoice", invoice)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                .flatMap(
                    row -> {
                      String profile = (String) row.get("profile");
                      if (profile == null || !registration.profileVersions().contains(profile))
                        return Mono.error(
                            failure(DomainStatus.FAILED_PRECONDITION, "PAYMENT_BINDING_MISMATCH"));
                      var i = registration.identity();
                      return identity(
                              tx.sql(
                                  "INSERT INTO"
                                      + " taska.provider_pay_routes(provider,provider_account_id,environment,provider_payment_id,tenant_id,invoice_id,operation_id,merchant_profile_version,created_at)"
                                      + " VALUES(:provider,:account,:environment,:payment,:tenant,:invoice,:operation,:profile,:now)"
                                      + " ON CONFLICT DO NOTHING"),
                              i)
                          .bind("payment", paymentId)
                          .bind("tenant", tenant)
                          .bind("invoice", invoice)
                          .bind("operation", row.get("operation_id"))
                          .bind("profile", profile)
                          .bind("now", offset(now()))
                          .fetch()
                          .rowsUpdated()
                          .then(route(tx, i, paymentId))
                          .flatMap(
                              bound ->
                                  tenant.equals(bound.get("tenant_id"))
                                          && invoice.equals(bound.get("invoice_id"))
                                      ? Mono.<Void>empty()
                                      : Mono.error(
                                          failure(
                                              DomainStatus.ABORTED, "PAYMENT_BINDING_CONFLICT")));
                    }));
  }

  private Mono<Map<String, Object>> route(
      DatabaseClient tx, ProviderWebhookRegistry.Identity i, String payment) {
    return identity(
            tx.sql(
                "SELECT r.*,i.currency AS expected_currency FROM taska.provider_pay_routes r LEFT"
                    + " JOIN taska.invoices i ON i.tenant_id=r.tenant_id AND i.id=r.invoice_id"
                    + " WHERE provider=:provider AND provider_account_id=:account AND"
                    + " environment=:environment AND provider_payment_id=:payment"),
            i)
        .bind("payment", payment)
        .fetch()
        .one();
  }

  public Mono<Receipt> prepare(UUID tenant, UUID id) {
    return tenants.execute(
        tx ->
            tx.sql(
                    "SELECT *,observation::text AS observation_document FROM taska.provider_inbox"
                        + " WHERE id=:id FOR UPDATE")
                .bind("id", id)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                .flatMap(
                    row -> {
                      String state = row.get("state").toString();
                      if (!state.equals("RECEIVED")) {
                        if (!tenant.equals(row.get("assigned_tenant_id")))
                          return Mono.error(failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
                        return Mono.just(receipt(row));
                      }
                      var i =
                          new ProviderWebhookRegistry.Identity(
                              row.get("provider").toString(),
                              row.get("provider_account_id").toString(),
                              PaymentProvider.Environment.valueOf(
                                  row.get("environment").toString()));
                      var event = json.readTree(row.get("observation_document").toString());
                      String payment = event.path("paymentId").asText();
                      return route(tx, i, payment)
                          .switchIfEmpty(
                              Mono.error(
                                  failure(DomainStatus.UNAVAILABLE, "PAYMENT_MAPPING_UNAVAILABLE")))
                          .flatMap(
                              binding -> {
                                if (!tenant.equals(binding.get("tenant_id")))
                                  return Mono.error(
                                      failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
                                if (!binding
                                    .get("invoice_id")
                                    .toString()
                                    .equals(event.path("invoiceId").asText()))
                                  return mark(
                                      tx,
                                      id,
                                      tenant,
                                      "QUARANTINED",
                                      "PAYMENT_BINDING_MISMATCH",
                                      null);
                                Instant occurred = Instant.parse(event.path("occurredAt").asText());
                                if (occurred.isBefore(Instant.EPOCH)
                                    || occurred.isAfter(now().plusSeconds(300)))
                                  return mark(
                                      tx, id, tenant, "QUARANTINED", "INVALID_OCCURRENCE", null);
                                long amount = event.path("amount").asLong();
                                String currency = event.path("currency").asText();
                                if (!currency.equals(binding.get("expected_currency")))
                                  return mark(
                                      tx,
                                      id,
                                      tenant,
                                      "QUARANTINED",
                                      "PAYMENT_CURRENCY_MISMATCH",
                                      null);
                                String canonical =
                                    framed(
                                        i.provider(),
                                        i.account(),
                                        i.environment().name(),
                                        payment,
                                        tenant.toString(),
                                        binding.get("invoice_id").toString(),
                                        binding.get("operation_id").toString(),
                                        binding.get("merchant_profile_version").toString(),
                                        Long.toString(amount),
                                        currency,
                                        occurred.toString());
                                UUID evidence =
                                    UUID.nameUUIDFromBytes(
                                        framed(
                                                i.provider(),
                                                i.account(),
                                                i.environment().name(),
                                                payment)
                                            .getBytes(StandardCharsets.UTF_8));
                                String digest = sha(canonical.getBytes(StandardCharsets.UTF_8));
                                return identity(
                                        tx.sql(
                                            "INSERT INTO"
                                                + " taska.provider_payment_proofs(id,provider,provider_account_id,environment,provider_payment_id,tenant_id,invoice_id,operation_id,merchant_profile_version,amount_minor,currency,occurred_at,observed_at,evidence_digest)"
                                                + " VALUES(:id,:provider,:account,:environment,:payment,:tenant,:invoice,:operation,:profile,:amount,:currency,:occurred,:observed,:digest)"
                                                + " ON CONFLICT DO NOTHING"),
                                        i)
                                    .bind("id", evidence)
                                    .bind("payment", payment)
                                    .bind("tenant", tenant)
                                    .bind("invoice", binding.get("invoice_id"))
                                    .bind("operation", binding.get("operation_id"))
                                    .bind("profile", binding.get("merchant_profile_version"))
                                    .bind("amount", amount)
                                    .bind("currency", currency)
                                    .bind("occurred", offset(occurred))
                                    .bind("observed", row.get("received_at"))
                                    .bind("digest", digest)
                                    .fetch()
                                    .rowsUpdated()
                                    .then(
                                        tx.sql(
                                                "SELECT evidence_digest FROM"
                                                    + " taska.provider_payment_proofs WHERE id=:id"
                                                    + " AND tenant_id=:tenant")
                                            .bind("id", evidence)
                                            .bind("tenant", tenant)
                                            .fetch()
                                            .one())
                                    .switchIfEmpty(
                                        Mono.error(
                                            failure(
                                                DomainStatus.UNAVAILABLE,
                                                "PAYMENT_EVIDENCE_UNAVAILABLE")))
                                    .flatMap(
                                        proof ->
                                            digest.equals(proof.get("evidence_digest"))
                                                ? mark(
                                                    tx, id, tenant, "VERIFIED", "READY", evidence)
                                                : mark(
                                                    tx,
                                                    id,
                                                    tenant,
                                                    "QUARANTINED",
                                                    "PAYMENT_FACT_CONFLICT",
                                                    null));
                              });
                    }));
  }

  public Mono<Receipt> applied(UUID tenant, UUID id) {
    return tenants.execute(
        tx ->
            tx.sql(
                    "UPDATE taska.provider_inbox SET"
                        + " state='APPLIED',reason_code='PROCESSED',processed_at=coalesce(processed_at,:now),version=version+1"
                        + " WHERE id=:id AND assigned_tenant_id=:tenant AND state='VERIFIED'"
                        + " RETURNING *")
                .bind("now", offset(now()))
                .bind("id", id)
                .bind("tenant", tenant)
                .fetch()
                .one()
                .map(ProviderInboxStore::receipt)
                .switchIfEmpty(
                    tx.sql(
                            "SELECT * FROM taska.provider_inbox WHERE id=:id AND"
                                + " assigned_tenant_id=:tenant AND state='APPLIED'")
                        .bind("id", id)
                        .bind("tenant", tenant)
                        .fetch()
                        .one()
                        .map(ProviderInboxStore::receipt))
                .switchIfEmpty(
                    Mono.error(failure(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"))));
  }

  private Mono<Receipt> mark(
      DatabaseClient tx, UUID id, UUID tenant, String state, String reason, UUID evidence) {
    var q =
        tx.sql(
                "UPDATE taska.provider_inbox SET"
                    + " state=:state,reason_code=:reason,assigned_tenant_id=:tenant,evidence_id=:evidence,version=version+1"
                    + " WHERE id=:id RETURNING *")
            .bind("state", state)
            .bind("reason", reason)
            .bind("tenant", tenant)
            .bind("id", id);
    q = evidence == null ? q.bindNull("evidence", UUID.class) : q.bind("evidence", evidence);
    return q.fetch().one().map(ProviderInboxStore::receipt);
  }

  public Mono<List<Pending>> pending(int limit) {
    return db.sql(
            "SELECT i.id,r.tenant_id FROM taska.provider_inbox i JOIN taska.provider_pay_routes r"
                + " ON r.provider=i.provider AND r.provider_account_id=i.provider_account_id AND"
                + " r.environment=i.environment AND"
                + " r.provider_payment_id=i.observation->>'paymentId' WHERE i.state IN"
                + " ('RECEIVED','VERIFIED') AND i.next_attempt_at<=:now ORDER BY"
                + " i.next_attempt_at,i.received_at,i.id LIMIT :limit")
        .bind("limit", limit)
        .bind("now", offset(now()))
        .map((r, m) -> new Pending(r.get("id", UUID.class), r.get("tenant_id", UUID.class)))
        .all()
        .collectList();
  }

  public Mono<Void> retry(UUID receipt) {
    return db.sql(
            "UPDATE taska.provider_inbox SET attempts=attempts+1,reason_code='RETRY_PENDING',"
                + " next_attempt_at=greatest(next_attempt_at,:now+LEAST(900,5*power(2,LEAST(attempts,8)))*interval"
                + " '1 second') WHERE id=:id AND state IN ('RECEIVED','VERIFIED')")
        .bind("id", receipt)
        .bind("now", offset(now()))
        .fetch()
        .rowsUpdated()
        .then();
  }

  public Mono<Void> verifyPayloadKeys() {
    return db.sql(
            "SELECT DISTINCT payload_key_id FROM taska.provider_inbox WHERE payload_key_id IS NOT"
                + " NULL")
        .map((r, m) -> r.get("payload_key_id", String.class))
        .all()
        .all(cipher::hasKey)
        .flatMap(
            valid ->
                valid
                    ? Mono.empty()
                    : Mono.error(new IllegalStateException("Retained inbox payload key missing")));
  }

  public Mono<Void> purge() {
    return db.sql(
            "UPDATE taska.provider_inbox SET"
                + " payload_key_id=NULL,payload_nonce=NULL,payload_ciphertext=NULL WHERE"
                + " processed_at IS NOT NULL AND processed_at<:cutoff AND state IN"
                + " ('APPLIED','IGNORED') AND payload_ciphertext IS NOT NULL")
        .bind("cutoff", offset(now().minus(30, ChronoUnit.DAYS)))
        .fetch()
        .rowsUpdated()
        .then(
            db.sql("DELETE FROM taska.provider_ingress_limits WHERE bucket<:cutoff")
                .bind("cutoff", offset(now().minus(1, ChronoUnit.DAYS)))
                .fetch()
                .rowsUpdated())
        .then();
  }

  private static Receipt receipt(Map<String, Object> r) {
    return new Receipt(
        (UUID) r.get("id"),
        r.get("state").toString(),
        r.get("reason_code").toString(),
        (UUID) r.get("evidence_id"));
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("Digest unavailable");
    }
  }

  private static String framed(String... values) {
    var s = new StringBuilder("taska:provider:v1:");
    for (String v : values) s.append(v.length()).append(':').append(v);
    return s.toString();
  }
}
