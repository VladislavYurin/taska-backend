package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.billing.FinancialLedgerDependencies.*;
import ru.taska.billing.FinancialLedgerDependencies.Currency;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only accounting primitives. Settlement/refund policy owns their surrounding transaction.
 */
public final class FinancialLedger {
  public record Fact(UUID id, Movement movement) {}

  public record Match(UUID id, UUID paymentId, UUID invoiceId, long amountMinor) {}

  public record Allocation(
      UUID id, UUID matchId, UUID paymentId, UUID invoiceId, long amountMinor) {}

  public record Balance(
      UUID paymentId,
      Currency currency,
      long received,
      long netAllocated,
      long unallocatedRemaining,
      long confirmedNetReturned,
      long matchedUnallocated) {}

  private final TenantTransactionExecutor transactions;
  private final FinancialAccess access;
  private final EvidenceAuthority evidence;
  private final EligibilityAuthority eligibility;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public FinancialLedger(
      TenantTransactionExecutor transactions,
      FinancialReadAuthority readers,
      EvidenceAuthority evidence,
      EligibilityAuthority eligibility,
      Clock clock) {
    this.transactions = transactions;
    this.access = new FinancialAccess(readers);
    this.evidence = evidence;
    this.eligibility = eligibility;
    this.clock = clock;
  }

  private Mono<VerifiedTenantContext> worker(UUID tenant, String action) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!tenant.toString().equals(v.tenantId())
                  || !action.equals(v.action())
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !c.getIssuerService().equals("billing-service")
                  || !v.scopes().contains("write")
                  || !v.scopes().contains("ledger:write"))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return v;
            });
  }

  private byte[] digest(Object value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String optional(Object x) {
    return x == null ? "" : x.toString();
  }

  private byte[] movementHash(Movement m) {
    var s = m.source();
    var o = m.occurrence();
    return digest(
        List.of(
            m.tenantId().toString(),
            s.provider(),
            s.accountId(),
            s.environment().name(),
            s.externalId(),
            s.method().name(),
            m.kind().name(),
            optional(m.parentId()),
            optional(m.allocationId()),
            Long.toString(m.amountMinor()),
            m.currency().name(),
            o.precision().name(),
            optional(o.at()),
            optional(o.notBefore()),
            optional(o.before()),
            m.observedAt().toString(),
            m.confirmedAt().toString(),
            optional(m.confirmedBy()),
            optional(m.payerRef()),
            m.evidenceRef(),
            m.evidenceDigest()));
  }

  private static void opaque(String v, int max) {
    if (v == null || !v.matches("[A-Za-z0-9._:/-]{1," + max + "}")) throw invalid();
  }

  private static void time(Instant i) {
    if (i == null || i.getNano() % 1000 != 0) throw invalid();
  }

  private static void validate(UUID tenant, Movement m) {
    if (m == null
        || !tenant.equals(m.tenantId())
        || m.source() == null
        || m.kind() == null
        || m.currency() == null
        || m.amountMinor() <= 0
        || m.occurrence() == null) throw invalid();
    var s = m.source();
    opaque(s.provider(), 80);
    opaque(s.accountId(), 120);
    opaque(s.externalId(), 200);
    if (s.environment() == null || s.method() == null) throw invalid();
    if (s.method() == Method.WIRE
        && (m.currency() != Currency.EUR || m.payerRef() == null || m.confirmedBy() == null))
      throw invalid();
    if (m.payerRef() != null) opaque(m.payerRef(), 200);
    opaque(m.evidenceRef(), 200);
    if (m.evidenceDigest() == null || !m.evidenceDigest().matches("[0-9a-f]{64}")) throw invalid();
    time(m.observedAt());
    time(m.confirmedAt());
    var o = m.occurrence();
    if (o.precision() == Precision.EXACT) {
      time(o.at());
      if (o.notBefore() != null || o.before() != null) throw invalid();
    } else if (o.precision() == Precision.DAY) {
      time(o.notBefore());
      time(o.before());
      if (o.at() != null || !o.before().isAfter(o.notBefore())) throw invalid();
    } else throw invalid();
    if ((m.kind() == Kind.PAYMENT && (m.parentId() != null || m.allocationId() != null))
        || (m.kind() != Kind.PAYMENT && m.parentId() == null)) throw invalid();
  }

  private Mono<List<UUID>> command(
      DatabaseClient db,
      UUID tenant,
      String action,
      String key,
      byte[] hash,
      Function<DatabaseClient, Mono<List<UUID>>> work) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
    var previous =
        db.sql(
                "SELECT request_hash,result_ids::text ids FROM taska.financial_command_receipts"
                    + " WHERE tenant_id=:tenant AND action=:action AND key=:key")
            .bind("tenant", tenant)
            .bind("action", action)
            .bind("key", key)
            .map(
                (r, meta) ->
                    Map.of(
                        "hash",
                        r.get("request_hash", byte[].class),
                        "ids",
                        r.get("ids", String.class)))
            .one()
            .map(
                r -> {
                  if (!MessageDigest.isEqual(hash, (byte[]) r.get("hash")))
                    throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
                  return Arrays.stream(json.readValue((String) r.get("ids"), String[].class))
                      .map(UUID::fromString)
                      .toList();
                });
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .bind("lock", tenant + ":" + action + ":" + key)
        .fetch()
        .all()
        .then(
            previous.switchIfEmpty(
                Mono.defer(() -> work.apply(db))
                    .flatMap(
                        ids ->
                            db.sql(
                                    "INSERT INTO"
                                        + " taska.financial_command_receipts(tenant_id,action,key,request_hash,result_ids)"
                                        + " VALUES(:tenant,:action,:key,:hash,CAST(:ids AS jsonb))")
                                .bind("tenant", tenant)
                                .bind("action", action)
                                .bind("key", key)
                                .bind("hash", hash)
                                .bind(
                                    "ids",
                                    json.writeValueAsString(
                                        ids.stream().map(UUID::toString).toList()))
                                .fetch()
                                .rowsUpdated()
                                .thenReturn(ids))));
  }

  /** Source is resolved by authority, not accepted from a browser/caller DTO. */
  public Mono<Fact> record(UUID tenant, UUID evidenceId, String key) {
    return worker(tenant, "recordFinancialFact")
        .flatMap(
            v ->
                transactions.execute(
                    db ->
                        command(
                                db,
                                tenant,
                                "recordFinancialFact",
                                key,
                                digest(evidenceId.toString()),
                                c ->
                                    evidence
                                        .verified(c, tenant, evidenceId)
                                        .switchIfEmpty(
                                            Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "PAYMENT_PENDING")))
                                        .flatMap(
                                            m -> {
                                              validate(tenant, m);
                                              return insert(c, m).map(f -> List.of(f.id()));
                                            }))
                            .flatMap(ids -> fact(db, tenant, ids.getFirst()))));
  }

  private Mono<Fact> insert(DatabaseClient db, Movement m) {
    byte[] hash = movementHash(m);
    var s = m.source();
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .bind(
            "lock",
            json.writeValueAsString(
                List.of(
                    "financialSource",
                    s.provider(),
                    s.accountId(),
                    s.environment().name(),
                    s.externalId())))
        .fetch()
        .all()
        .then(
            Mono.defer(
                () -> {
                  var previous =
                      db.sql(
                              "SELECT id,request_hash FROM taska.payment_facts WHERE"
                                  + " tenant_id=:tenant AND provider=:provider AND"
                                  + " provider_account_id=:account AND environment=:environment AND"
                                  + " external_fact_id=:external")
                          .bind("tenant", m.tenantId())
                          .bind("provider", s.provider())
                          .bind("account", s.accountId())
                          .bind("environment", s.environment().name())
                          .bind("external", s.externalId())
                          .map(
                              (r, meta) ->
                                  Map.of(
                                      "id",
                                      r.get("id", UUID.class),
                                      "hash",
                                      r.get("request_hash", byte[].class)))
                          .one()
                          .flatMap(
                              r -> {
                                if (!MessageDigest.isEqual(hash, (byte[]) r.get("hash")))
                                  return Mono.error(
                                      fail(DomainStatus.ALREADY_EXISTS, "FINANCIAL_FACT_CONFLICT"));
                                return fact(db, m.tenantId(), (UUID) r.get("id"));
                              });
                  return previous.switchIfEmpty(
                      Mono.defer(
                          () -> {
                            UUID id = UUID.randomUUID();
                            Mono<UUID> root =
                                m.parentId() == null
                                    ? Mono.just(id)
                                    : db.sql(
                                            "SELECT root_payment_id FROM taska.payment_facts WHERE"
                                                + " tenant_id=:tenant AND id=:parent")
                                        .bind("tenant", m.tenantId())
                                        .bind("parent", m.parentId())
                                        .map((r, meta) -> r.get("root_payment_id", UUID.class))
                                        .one()
                                        .switchIfEmpty(
                                            Mono.error(
                                                fail(
                                                    DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
                            return root.flatMap(
                                rootId -> {
                                  var q =
                                      db.sql(
                                              "INSERT INTO"
                                                  + " taska.payment_facts(id,tenant_id,provider,provider_account_id,environment,external_fact_id,fact_kind,parent_fact_id,root_payment_id,allocation_id,amount_minor,currency,currency_exponent,source_method,occurred_precision,occurred_at,occurred_not_before,occurred_before,observed_at,verified_at,verified_by,payer_ref,evidence_ref,evidence_digest,request_hash)"
                                                  + " VALUES(:id,:tenant,:provider,:account,:environment,:external,:kind,:parent,:root,:allocation,:amount,:currency,2,:method,:precision,:occurred,:from,:before,:observed,:confirmed,:actor,:payer,:evidence,:digest,:hash)"
                                                  + " ON CONFLICT(provider,provider_account_id,environment,external_fact_id)"
                                                  + " DO NOTHING RETURNING id")
                                          .bind("id", id)
                                          .bind("tenant", m.tenantId())
                                          .bind("provider", s.provider())
                                          .bind("account", s.accountId())
                                          .bind("environment", s.environment().name())
                                          .bind("external", s.externalId())
                                          .bind("kind", m.kind().name())
                                          .bind("root", rootId)
                                          .bind("amount", m.amountMinor())
                                          .bind("currency", m.currency().name())
                                          .bind("method", s.method().name())
                                          .bind("precision", m.occurrence().precision().name())
                                          .bind("observed", m.observedAt().atOffset(ZoneOffset.UTC))
                                          .bind(
                                              "confirmed", m.confirmedAt().atOffset(ZoneOffset.UTC))
                                          .bind("evidence", m.evidenceRef())
                                          .bind(
                                              "digest", HexFormat.of().parseHex(m.evidenceDigest()))
                                          .bind("hash", hash);
                                  q = nullable(q, "parent", m.parentId(), UUID.class);
                                  q = nullable(q, "allocation", m.allocationId(), UUID.class);
                                  q = nullable(q, "actor", m.confirmedBy(), UUID.class);
                                  q = nullable(q, "payer", m.payerRef(), String.class);
                                  q = instant(q, "occurred", m.occurrence().at());
                                  q = instant(q, "from", m.occurrence().notBefore());
                                  q = instant(q, "before", m.occurrence().before());
                                  return q.map((r, meta) -> r.get("id", UUID.class))
                                      .one()
                                      .switchIfEmpty(
                                          Mono.error(
                                              fail(
                                                  DomainStatus.ALREADY_EXISTS,
                                                  "FINANCIAL_FACT_CONFLICT")))
                                      .flatMap(
                                          saved ->
                                              event(
                                                      db,
                                                      m.tenantId(),
                                                      saved,
                                                      "FinancialFactRecorded")
                                                  .thenReturn(new Fact(saved, m)));
                                });
                          }));
                }));
  }

  private static DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec q, String n, Object v, Class<?> type) {
    return v == null ? q.bindNull(n, type) : q.bind(n, v);
  }

  private static DatabaseClient.GenericExecuteSpec instant(
      DatabaseClient.GenericExecuteSpec q, String n, Instant v) {
    return nullable(q, n, v == null ? null : v.atOffset(ZoneOffset.UTC), OffsetDateTime.class);
  }

  private Mono<Fact> fact(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.payment_facts WHERE tenant_id=:tenant AND id=:id")
        .bind("tenant", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .map(
            r -> {
              var m =
                  new Movement(
                      tenant,
                      new Source(
                          (String) r.get("provider"),
                          (String) r.get("provider_account_id"),
                          Environment.valueOf((String) r.get("environment")),
                          (String) r.get("external_fact_id"),
                          Method.valueOf((String) r.get("source_method"))),
                      Kind.valueOf((String) r.get("fact_kind")),
                      (UUID) r.get("parent_fact_id"),
                      (UUID) r.get("allocation_id"),
                      ((Number) r.get("amount_minor")).longValue(),
                      Currency.valueOf((String) r.get("currency")),
                      new Occurrence(
                          Precision.valueOf((String) r.get("occurred_precision")),
                          at(r, "occurred_at"),
                          at(r, "occurred_not_before"),
                          at(r, "occurred_before")),
                      at(r, "observed_at"),
                      at(r, "verified_at"),
                      (UUID) r.get("verified_by"),
                      (String) r.get("payer_ref"),
                      (String) r.get("evidence_ref"),
                      HexFormat.of().formatHex(bytes(r.get("evidence_digest"))));
              return new Fact(id, m);
            })
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private static byte[] bytes(Object value) {
    if (value instanceof byte[] b) return b;
    var buffer = ((java.nio.ByteBuffer) value).duplicate();
    byte[] b = new byte[buffer.remaining()];
    buffer.get(b);
    return b;
  }

  private static Instant at(Map<String, Object> r, String name) {
    return r.get(name) == null ? null : ((OffsetDateTime) r.get(name)).toInstant();
  }

  private Mono<Balance> balance(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.financial_balances WHERE tenant_id=:tenant AND id=:id")
        .bind("tenant", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .map(
            r ->
                new Balance(
                    id,
                    Currency.valueOf((String) r.get("currency")),
                    number(r, "received"),
                    number(r, "net_allocated"),
                    number(r, "unallocated_remaining"),
                    number(r, "confirmed_net_returned"),
                    number(r, "matched_unallocated")))
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private static long number(Map<String, Object> r, String n) {
    Object v = r.get(n);
    return v instanceof BigDecimal b ? b.longValueExact() : ((Number) v).longValue();
  }

  public Mono<Fact> get(UUID tenant, UUID fact) {
    return access
        .authorize(tenant, "getFinancialFact", false)
        .flatMap(v -> transactions.execute(db -> fact(db, tenant, fact)));
  }

  public Mono<Balance> balance(UUID tenant, UUID payment) {
    return access
        .authorize(tenant, "getFinancialBalance", false)
        .flatMap(v -> transactions.execute(db -> balance(db, tenant, payment)));
  }

  private Mono<Map<String, Object>> lockInvoice(DatabaseClient db, UUID tenant, UUID invoice) {
    return db.sql("SELECT * FROM taska.invoices WHERE tenant_id=:tenant AND id=:invoice FOR UPDATE")
        .bind("tenant", tenant)
        .bind("invoice", invoice)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private Mono<Void> lockPayment(DatabaseClient db, UUID tenant, UUID payment) {
    return db.sql(
            "SELECT id FROM taska.payment_facts WHERE tenant_id=:tenant AND id=:payment AND"
                + " fact_kind='PAYMENT' FOR UPDATE")
        .bind("tenant", tenant)
        .bind("payment", payment)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .then();
  }

  public Mono<Match> match(UUID tenant, UUID payment, UUID invoice, String key) {
    return worker(tenant, "matchInvoicePayment")
        .flatMap(
            v ->
                transactions.execute(
                    db ->
                        command(
                                db,
                                tenant,
                                "matchInvoicePayment",
                                key,
                                digest(List.of(payment.toString(), invoice.toString())),
                                c -> createMatch(c, tenant, payment, invoice))
                            .flatMap(ids -> matchRow(db, tenant, ids.getFirst()))));
  }

  private Mono<List<UUID>> createMatch(DatabaseClient db, UUID tenant, UUID payment, UUID invoice) {
    return lockInvoice(db, tenant, invoice)
        .flatMap(
            header ->
                lockPayment(db, tenant, payment)
                    .then(
                        Mono.defer(
                            () -> {
                              var previous =
                                  db.sql(
                                          "SELECT id,invoice_id FROM taska.invoice_payment_matches"
                                              + " WHERE tenant_id=:tenant AND"
                                              + " payment_fact_id=:payment")
                                      .bind("tenant", tenant)
                                      .bind("payment", payment)
                                      .fetch()
                                      .one()
                                      .map(
                                          r -> {
                                            if (!r.get("invoice_id").equals(invoice))
                                              throw fail(
                                                  DomainStatus.ALREADY_EXISTS,
                                                  "FINANCIAL_FACT_CONFLICT");
                                            return List.of((UUID) r.get("id"));
                                          });
                              return previous.switchIfEmpty(
                                  Mono.defer(
                                      () ->
                                          balance(db, tenant, payment)
                                              .flatMap(
                                                  balance ->
                                                      db.sql(
                                                              "SELECT coalesce(sum(amount_minor),0)"
                                                                  + " used FROM"
                                                                  + " taska.invoice_payment_matches"
                                                                  + " WHERE tenant_id=:tenant AND"
                                                                  + " invoice_id=:invoice")
                                                          .bind("tenant", tenant)
                                                          .bind("invoice", invoice)
                                                          .map(
                                                              (r, m) ->
                                                                  r.get("used", BigDecimal.class)
                                                                      .longValueExact())
                                                          .one()
                                                          .flatMap(
                                                              used -> {
                                                                long amount =
                                                                    Math.min(
                                                                        balance
                                                                            .unallocatedRemaining(),
                                                                        ((Number)
                                                                                    header.get(
                                                                                        "total_minor"))
                                                                                .longValue()
                                                                            - used);
                                                                if (amount <= 0)
                                                                  throw fail(
                                                                      DomainStatus
                                                                          .FAILED_PRECONDITION,
                                                                      "INVALID_STATE");
                                                                UUID id = UUID.randomUUID();
                                                                return db.sql(
                                                                        "INSERT INTO"
                                                                            + " taska.invoice_payment_matches(id,tenant_id,payment_fact_id,invoice_id,amount_minor,operation_id)"
                                                                            + " VALUES(:id,:tenant,:payment,:invoice,:amount,:operation)")
                                                                    .bind("id", id)
                                                                    .bind("tenant", tenant)
                                                                    .bind("payment", payment)
                                                                    .bind("invoice", invoice)
                                                                    .bind("amount", amount)
                                                                    .bind(
                                                                        "operation",
                                                                        UUID.randomUUID())
                                                                    .fetch()
                                                                    .rowsUpdated()
                                                                    .then(
                                                                        event(
                                                                            db,
                                                                            tenant,
                                                                            id,
                                                                            "PaymentMatched"))
                                                                    .thenReturn(List.of(id));
                                                              }))));
                            })));
  }

  private Mono<Match> matchRow(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.invoice_payment_matches WHERE tenant_id=:tenant AND id=:id")
        .bind("tenant", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .map(
            r ->
                new Match(
                    id,
                    (UUID) r.get("payment_fact_id"),
                    (UUID) r.get("invoice_id"),
                    ((Number) r.get("amount_minor")).longValue()))
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  /** This joins an enclosing settlement transaction on the same executor/physical connection. */
  public Mono<List<Allocation>> allocate(
      UUID tenant, UUID invoice, List<UUID> matches, String key) {
    if (matches.isEmpty()
        || matches.size() > 100
        || matches.stream().anyMatch(Objects::isNull)
        || new HashSet<>(matches).size() != matches.size()) throw invalid();
    List<UUID> ordered = matches.stream().sorted().toList();
    return worker(tenant, "allocateInvoicePayments")
        .flatMap(
            v ->
                transactions.execute(
                    db ->
                        command(
                                db,
                                tenant,
                                "allocateInvoicePayments",
                                key,
                                digest(
                                    List.of(
                                        invoice.toString(),
                                        ordered.stream().map(UUID::toString).toList())),
                                c -> allocateMatches(c, tenant, invoice, ordered))
                            .flatMap(
                                ids ->
                                    Flux.fromIterable(ids)
                                        .concatMap(id -> allocationRow(db, tenant, id))
                                        .collectList())));
  }

  private Mono<List<UUID>> allocateMatches(
      DatabaseClient db, UUID tenant, UUID invoice, List<UUID> ordered) {
    return lockInvoice(db, tenant, invoice)
        .flatMap(
            header ->
                Flux.fromIterable(ordered)
                    .concatMap(id -> matchRow(db, tenant, id))
                    .collectList()
                    .flatMap(
                        rows -> {
                          if (rows.stream().anyMatch(m -> !m.invoiceId().equals(invoice)))
                            throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
                          var locked =
                              Flux.fromIterable(
                                      rows.stream().map(Match::paymentId).sorted().toList())
                                  .concatMap(id -> lockPayment(db, tenant, id))
                                  .then();
                          return locked.then(
                              eligibility
                                  .eligible(db, tenant, invoice, ordered)
                                  .switchIfEmpty(
                                      Mono.error(
                                          fail(
                                              DomainStatus.FAILED_PRECONDITION, "PAYMENT_PENDING")))
                                  .flatMap(
                                      decision -> {
                                        if (!new HashSet<>(decision.matchIds())
                                                .equals(new HashSet<>(ordered))
                                            || decision.matchIds().size() != ordered.size())
                                          throw invalid();
                                        opaque(decision.decisionCode(), 80);
                                        long total = 0;
                                        for (var row : rows)
                                          total = Math.addExact(total, row.amountMinor());
                                        if (total
                                            != ((Number) header.get("total_minor")).longValue())
                                          throw fail(
                                              DomainStatus.FAILED_PRECONDITION, "PAYMENT_PENDING");
                                        return Flux.fromIterable(
                                                rows.stream()
                                                    .sorted(Comparator.comparing(Match::paymentId))
                                                    .toList())
                                            .concatMap(
                                                row ->
                                                    allocation(
                                                        db, tenant, row, decision.decisionCode()))
                                            .collectList();
                                      }));
                        }));
  }

  private Mono<UUID> allocation(DatabaseClient db, UUID tenant, Match match, String decision) {
    return db.sql(
            "SELECT id FROM taska.payment_allocations WHERE tenant_id=:tenant AND match_id=:match")
        .bind("tenant", tenant)
        .bind("match", match.id())
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .switchIfEmpty(
            Mono.defer(
                () ->
                    fact(db, tenant, match.paymentId())
                        .flatMap(
                            f -> {
                              UUID id = UUID.randomUUID();
                              Movement m = f.movement();
                              var o = m.occurrence();
                              String proof =
                                  json.writeValueAsString(
                                      Map.of(
                                          "factId",
                                          f.id().toString(),
                                          "invoiceId",
                                          match.invoiceId().toString(),
                                          "decisionCode",
                                          decision,
                                          "confirmedAt",
                                          m.confirmedAt().toString(),
                                          "verifiedBy",
                                          optional(m.confirmedBy()),
                                          "occurredPrecision",
                                          o.precision().name(),
                                          "occurredAt",
                                          optional(o.at()),
                                          "occurredNotBefore",
                                          optional(o.notBefore()),
                                          "occurredBefore",
                                          optional(o.before())));
                              return db.sql(
                                      "INSERT INTO"
                                          + " taska.payment_allocations(id,tenant_id,payment_fact_id,invoice_id,match_id,amount_minor,operation_id,eligibility_evidence)"
                                          + " VALUES(:id,:tenant,:payment,:invoice,:match,:amount,:operation,CAST(:proof"
                                          + " AS jsonb))")
                                  .bind("id", id)
                                  .bind("tenant", tenant)
                                  .bind("payment", match.paymentId())
                                  .bind("invoice", match.invoiceId())
                                  .bind("match", match.id())
                                  .bind("amount", match.amountMinor())
                                  .bind("operation", UUID.randomUUID())
                                  .bind("proof", proof)
                                  .fetch()
                                  .rowsUpdated()
                                  .then(event(db, tenant, id, "PaymentAllocated"))
                                  .thenReturn(id);
                            })));
  }

  private Mono<Allocation> allocationRow(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.payment_allocations WHERE tenant_id=:tenant AND id=:id")
        .bind("tenant", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .map(
            r ->
                new Allocation(
                    id,
                    (UUID) r.get("match_id"),
                    (UUID) r.get("payment_fact_id"),
                    (UUID) r.get("invoice_id"),
                    ((Number) r.get("amount_minor")).longValue()));
  }

  private Mono<Void> event(DatabaseClient db, UUID tenant, UUID id, String event) {
    return db.sql(
            "INSERT INTO"
                + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                + " VALUES(:event,:tenant,'FinancialLedger',:id,1,:type,1,CAST(:payload AS"
                + " jsonb),:operation,'NEW',:now)")
        .bind("event", UUID.randomUUID())
        .bind("tenant", tenant)
        .bind("id", id)
        .bind("type", event)
        .bind("payload", json.writeValueAsString(Map.of("recordId", id.toString())))
        .bind("operation", UUID.randomUUID())
        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
        .fetch()
        .rowsUpdated()
        .then();
  }
}
