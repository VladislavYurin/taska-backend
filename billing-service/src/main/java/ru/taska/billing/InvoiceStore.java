package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import com.google.protobuf.Message;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.InvoiceDependencies.*;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.json.JsonMapper;

/** Issuance only. No browser command or timer constitutes a settlement fact. */
public final class InvoiceStore {
  private final TenantTransactionExecutor transactions;
  private final FinancialAccess access;
  private final ObligationAuthority obligations;
  private final CommercialPolicy commercial;
  private final BillingCatalog catalog;
  private final Clock clock;
  private final PeriodCursor cursors;
  private final JsonMapper json = JsonMapper.builder().build();

  public InvoiceStore(
      TenantTransactionExecutor transactions,
      FinancialReadAuthority readers,
      ObligationAuthority obligations,
      CommercialPolicy commercial,
      BillingCatalog catalog,
      Clock clock,
      PeriodCursor cursors) {
    this.transactions = transactions;
    this.access = new FinancialAccess(readers);
    this.obligations = obligations;
    this.commercial = commercial;
    this.catalog = catalog;
    this.clock = clock;
    this.cursors = cursors;
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static byte[] hash(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encoded(Message value) {
    return Base64.getEncoder().encodeToString(value.toByteArray());
  }

  private static Invoice parsed(String value) {
    try {
      return Invoice.parseFrom(Base64.getDecoder().decode(value));
    } catch (com.google.protobuf.InvalidProtocolBufferException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid invoice snapshot");
    }
  }

  /**
   * Explicit protobuf fields only, never reflective bean serialization of implementation objects.
   */
  private Object document(Object value) {
    if (value instanceof Message m) {
      var fields = new TreeMap<String, Object>();
      m.getAllFields().forEach((k, v) -> fields.put(k.getJsonName(), document(v)));
      return fields;
    }
    if (value instanceof List<?> list) return list.stream().map(this::document).toList();
    if (value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e) return e.getName();
    if (value instanceof Long n) return n.toString();
    return value;
  }

  private String json(Object value) {
    return json.writeValueAsString(document(value));
  }

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl)) throw invalid();
  }

  private static void address(BillingAddress a) {
    if (!a.getCountry().matches("[A-Z]{2}")) throw invalid();
    text(a.getPostalCode(), 30);
    text(a.getCity(), 120);
    text(a.getLine1(), 300);
    if (a.hasLine2()) text(a.getLine2(), 300);
  }

  private static void validate(CreateInvoiceRequest p) {
    uuid(p.getPlanVersionId());
    if (p.getPurposeValue() < 1
        || p.getPurposeValue() > 3
        || p.getCurrencyValue() < 1
        || p.getCurrencyValue() > 2
        || p.getPaymentMethodValue() < 1
        || p.getPaymentMethodValue() > 2
        || (p.getPaymentMethod() == CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE
            && p.getCurrency() != CreateInvoiceRequest.CurrencyValue.CURRENCY_EUR)
        || p.getBuyer().getKindValue() < 1
        || p.getBuyer().getKindValue() > 2
        || !p.getUnknownFields().asMap().isEmpty()
        || !p.getBuyer().getUnknownFields().asMap().isEmpty()
        || !p.getBuyer().getAddress().getUnknownFields().asMap().isEmpty()) throw invalid();
    text(p.getBuyer().getName(), 200);
    text(p.getBuyer().getEmail(), 254);
    if (!p.getBuyer().getEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid();
    address(p.getBuyer().getAddress());
    if (p.getBuyer().hasTaxId()) text(p.getBuyer().getTaxId(), 80);
  }

  private static void validate(UUID tenant, CreateInvoiceRequest p, Obligation o) {
    if (!tenant.equals(o.tenantId())
        || o.operationId() == null
        || !uuid(p.getPlanVersionId()).equals(o.planVersionId())
        || o.purpose().getNumber() != p.getPurposeValue()) throw invalid();
    text(o.targetKey(), 200);
    if ((o.periodStartsAt() == null) != (o.periodEndsAt() == null)
        || (o.periodStartsAt() != null
            && (!o.periodEndsAt().isAfter(o.periodStartsAt())
                || o.periodStartsAt().getNano() % 1000 != 0
                || o.periodEndsAt().getNano() % 1000 != 0))
        || (o.creationDeadline() != null && o.creationDeadline().getNano() % 1000 != 0))
      throw invalid();
  }

  private static long minor(Money m, Money.CurrencyValue currency) {
    if (m.getCurrency() != currency
        || m.getExponent() != 2
        || !m.getAmountMinor().matches("0|[1-9][0-9]{0,18}")) throw invalid();
    try {
      return Long.parseLong(m.getAmountMinor());
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static Money money(long n, Money.CurrencyValue c) {
    return Money.newBuilder()
        .setAmountMinor(Long.toString(n))
        .setCurrency(c)
        .setExponent(2)
        .build();
  }

  private record Totals(long net, long tax, long gross) {}

  private Totals validate(
      CommercialQuote q, PlanVersion plan, CreateInvoiceRequest request, Instant now) {
    text(q.profileVersion(), 120);
    text(q.seller().getLegalName(), 200);
    text(q.seller().getRegistrationId(), 100);
    address(q.seller().getAddress());
    if (q.seller().hasTaxId()) text(q.seller().getTaxId(), 80);
    if (q.validity() == null
        || q.validity().compareTo(java.time.Duration.ofDays(1)) < 0
        || q.validity().compareTo(java.time.Duration.ofDays(30)) > 0
        || q.validity().getNano() % 1000 != 0
        || q.lines().isEmpty()
        || q.lines().size() > 100) throw invalid();
    Money.CurrencyValue currency = Money.CurrencyValue.forNumber(request.getCurrencyValue());
    long gross = minor(plan.getPrice(), Money.CurrencyValue.CURRENCY_EUR);
    try {
      if (currency == Money.CurrencyValue.CURRENCY_USD) {
        var fx = q.fxQuote();
        if (fx == null) throw fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
        text(fx.getSource(), 120);
        if (fx.getBaseCurrency() != FxQuote.BaseCurrencyValue.BASE_CURRENCY_EUR
            || fx.getQuoteCurrency() != FxQuote.QuoteCurrencyValue.QUOTE_CURRENCY_USD
            || !fx.getRate().matches("[0-9]{1,18}(\\.[0-9]{1,18})?")
            || new BigDecimal(fx.getRate()).signum() <= 0
            || Instant.parse(fx.getObservedAt()).isAfter(now)
            || !Instant.parse(fx.getExpiresAt()).isAfter(now)
            || !Instant.parse(fx.getExpiresAt()).isAfter(Instant.parse(fx.getObservedAt())))
          throw fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
        gross =
            BigDecimal.valueOf(gross)
                .multiply(new BigDecimal(fx.getRate()))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
      } else if (q.fxQuote() != null) throw invalid();
      if (request.getPaymentMethod()
          == CreateInvoiceRequest.PaymentMethodValue.PAYMENT_METHOD_WIRE) {
        var w = q.wireInstructions();
        if (w == null) throw fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED");
        text(w.getBeneficiaryName(), 200);
        text(w.getBankName(), 200);
        text(w.getReference(), 140);
        if (!w.getIban().matches("[A-Z0-9]{15,34}")
            || !w.getBic().matches("[A-Z0-9]{8}([A-Z0-9]{3})?")) throw invalid();
      } else if (q.wireInstructions() != null) throw invalid();
      long net = 0, tax = 0, total = 0;
      for (var line : q.lines()) {
        text(line.getDescription(), 300);
        if (line.getQuantity() < 1 || line.getTaxesCount() > 20) throw invalid();
        long n = minor(line.getNet(), currency), t = 0, g = minor(line.getTotal(), currency);
        for (var item : line.getTaxesList()) {
          text(item.getLabel(), 120);
          if (!item.getRate().matches("[0-9]{1,3}(\\.[0-9]{1,6})?")) throw invalid();
          t = Math.addExact(t, minor(item.getAmount(), currency));
        }
        if (Math.addExact(n, t) != g) throw invalid();
        net = Math.addExact(net, n);
        tax = Math.addExact(tax, t);
        total = Math.addExact(total, g);
      }
      if (gross <= 0 || gross != total || Math.addExact(net, tax) != gross) throw invalid();
      return new Totals(net, tax, gross);
    } catch (ArithmeticException | java.time.DateTimeException e) {
      throw invalid();
    }
  }

  public Mono<Invoice> create(CreateInvoiceRpcRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return access
        .authorize(tenant, "createInvoice", true)
        .flatMap(
            v -> {
              var p = request.getPayload();
              validate(p);
              String key = request.getMutation().getIdempotencyKey();
              if (!key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
              byte[] digest = hash(p.toByteArray());
              return transactions.execute(
                  db ->
                      db.sql(
                              "SELECT tenant_id FROM taska.subscriptions WHERE tenant_id=:tenant"
                                  + " FOR UPDATE")
                          .bind("tenant", tenant)
                          .fetch()
                          .one()
                          .switchIfEmpty(
                              Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                          .then(
                              Mono.defer(
                                  () -> {
                                    var replay =
                                        db.sql(
                                                "SELECT request_hash,response FROM"
                                                    + " taska.invoice_command_receipts WHERE"
                                                    + " tenant_id=:tenant AND actor_id=:actor AND"
                                                    + " key=:key")
                                            .bind("tenant", tenant)
                                            .bind("actor", uuid(v.actorId()))
                                            .bind("key", key)
                                            .map(
                                                (r, m) ->
                                                    Map.of(
                                                        "request_hash",
                                                        r.get("request_hash", byte[].class),
                                                        "response",
                                                        r.get("response", String.class)))
                                            .one()
                                            .map(
                                                r -> {
                                                  if (!MessageDigest.isEqual(
                                                      digest, (byte[]) r.get("request_hash")))
                                                    throw fail(
                                                        DomainStatus.ALREADY_EXISTS,
                                                        "IDEMPOTENCY_CONFLICT");
                                                  return parsed((String) r.get("response"));
                                                });
                                    return replay.switchIfEmpty(
                                        Mono.defer(() -> issue(db, tenant, p))
                                            .flatMap(
                                                invoice ->
                                                    db.sql(
                                                            "INSERT INTO"
                                                                + " taska.invoice_command_receipts(tenant_id,actor_id,key,request_hash,response)"
                                                                + " VALUES(:tenant,:actor,:key,:hash,:response)")
                                                        .bind("tenant", tenant)
                                                        .bind("actor", uuid(v.actorId()))
                                                        .bind("key", key)
                                                        .bind("hash", digest)
                                                        .bind("response", encoded(invoice))
                                                        .fetch()
                                                        .rowsUpdated()
                                                        .thenReturn(invoice)));
                                  })));
            });
  }

  private Mono<Invoice> issue(DatabaseClient db, UUID tenant, CreateInvoiceRequest p) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    return obligations
        .resolve(db, tenant, p, now)
        .switchIfEmpty(Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE")))
        .flatMap(
            o -> {
              validate(tenant, p, o);
              return db.sql(
                      "SELECT snapshot,operation_id,target_key,purpose,plan_version_id,currency"
                          + " FROM taska.invoices WHERE tenant_id=:tenant AND (operation_id=:op OR"
                          + " (target_key=:target AND purpose=:purpose AND plan_version_id=:plan"
                          + " AND currency=:currency))")
                  .bind("tenant", tenant)
                  .bind("op", o.operationId())
                  .bind("target", o.targetKey())
                  .bind("purpose", o.purpose().name().substring(8))
                  .bind("plan", o.planVersionId())
                  .bind("currency", p.getCurrency().name().substring(9))
                  .fetch()
                  .one()
                  .map(
                      r -> {
                        Invoice i = parsed((String) r.get("snapshot"));
                        if (!i.getPlanVersionId().equals(p.getPlanVersionId())
                            || i.getPurposeValue() != p.getPurposeValue()
                            || i.getTotal().getCurrencyValue() != p.getCurrencyValue()
                            || !r.get("target_key").equals(o.targetKey())
                            || !i.getBuyer().equals(p.getBuyer()))
                          throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
                        return i;
                      })
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              db.sql(
                                      "SELECT"
                                          + " p.*,c.kind,(to_jsonb(p)||jsonb_build_object('kind',c.kind))::text"
                                          + " AS terms FROM taska.catalog_plan_versions p JOIN"
                                          + " taska.catalog_plans c USING(plan_code) WHERE"
                                          + " p.id=:plan AND p.published_at IS NOT NULL AND"
                                          + " c.kind='PAID'")
                                  .bind("plan", o.planVersionId())
                                  .fetch()
                                  .one()
                                  .switchIfEmpty(
                                      Mono.error(
                                          fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                                  .flatMap(
                                      row ->
                                          commercial
                                              .quote(db, catalog.map(row), p, now)
                                              .switchIfEmpty(
                                                  Mono.error(
                                                      fail(
                                                          DomainStatus.UNAVAILABLE,
                                                          "POLICY_NOT_CONFIGURED")))
                                              .flatMap(
                                                  q -> {
                                                    Totals totals =
                                                        validate(q, catalog.map(row), p, now);
                                                    Instant due = now.plus(q.validity());
                                                    if (o.creationDeadline() != null
                                                        && due.isAfter(o.creationDeadline()))
                                                      due = o.creationDeadline();
                                                    if (!due.isAfter(now))
                                                      throw fail(
                                                          DomainStatus.FAILED_PRECONDITION,
                                                          "INVALID_STATE");
                                                    Instant deadline = due;
                                                    return db.sql(
                                                            "SELECT"
                                                                + " 'INV-'||nextval('taska.invoice_number_seq')::text"
                                                                + " AS number")
                                                        .map(
                                                            (r, m) -> r.get("number", String.class))
                                                        .one()
                                                        .flatMap(
                                                            number ->
                                                                persist(
                                                                    db,
                                                                    tenant,
                                                                    p,
                                                                    o,
                                                                    q,
                                                                    totals,
                                                                    (String) row.get("terms"),
                                                                    now,
                                                                    deadline,
                                                                    number));
                                                  }))));
            });
  }

  private Mono<Invoice> persist(
      DatabaseClient db,
      UUID tenant,
      CreateInvoiceRequest p,
      Obligation o,
      CommercialQuote q,
      Totals t,
      String terms,
      Instant now,
      Instant due,
      String number) {
    UUID id = UUID.randomUUID();
    var currency = Money.CurrencyValue.forNumber(p.getCurrencyValue());
    var b =
        Invoice.newBuilder()
            .setId(id.toString())
            .setTenantId(tenant.toString())
            .setNumber(number)
            .setPurpose(o.purpose())
            .setState(Invoice.StateValue.STATE_OPEN)
            .setPlanVersionId(p.getPlanVersionId())
            .setBuyer(p.getBuyer())
            .setSeller(q.seller())
            .addAllLines(q.lines())
            .setTotal(money(t.gross(), currency))
            .setAllocated(money(0, currency))
            .setRemaining(money(t.gross(), currency))
            .setMatchedUnallocated(money(0, currency))
            .setIssuedAt(now.toString())
            .setDueAt(due.toString())
            .setPaymentMethodValue(p.getPaymentMethodValue())
            .setVersion("1");
    if (q.fxQuote() != null) b.setFxQuote(q.fxQuote());
    if (q.wireInstructions() != null) b.setWireInstructions(q.wireInstructions());
    if (o.replacesInvoiceId() != null) b.setReplacesInvoiceId(o.replacesInvoiceId().toString());
    Invoice invoice = b.build();
    var insert =
        db.sql(
                "INSERT INTO"
                    + " taska.invoices(id,tenant_id,number,operation_id,target_key,purpose,plan_version_id,method,currency,currency_exponent,subtotal_minor,tax_minor,total_minor,issued_at,payable_until,period_starts_at,period_ends_at,creation_deadline_snapshot,terms_snapshot,pricing_snapshot,seller_snapshot,buyer_snapshot,snapshot,replaces_invoice_id)"
                    + " VALUES(:id,:tenant,:number,:op,:target,:purpose,:plan,:method,:currency,2,:net,:tax,:gross,:issued,:due,:start,:end,:creation,CAST(:terms"
                    + " AS jsonb),CAST(:pricing AS jsonb),CAST(:seller AS jsonb),CAST(:buyer AS"
                    + " jsonb),:snapshot,:replaces)")
            .bind("id", id)
            .bind("tenant", tenant)
            .bind("number", number)
            .bind("op", o.operationId())
            .bind("target", o.targetKey())
            .bind("purpose", o.purpose().name().substring(8))
            .bind("plan", o.planVersionId())
            .bind("method", p.getPaymentMethod().name().substring(15))
            .bind("currency", currency.name().substring(9))
            .bind("net", t.net())
            .bind("tax", t.tax())
            .bind("gross", t.gross())
            .bind("issued", now.atOffset(ZoneOffset.UTC))
            .bind("due", due.atOffset(ZoneOffset.UTC))
            .bind("terms", terms)
            .bind(
                "pricing",
                json(
                    Map.of(
                        "profileVersion",
                        q.profileVersion(),
                        "total",
                        document(invoice.getTotal()),
                        "fx",
                        q.fxQuote() == null ? Map.of() : document(q.fxQuote()))))
            .bind("seller", json(q.seller()))
            .bind("buyer", json(p.getBuyer()))
            .bind("snapshot", encoded(invoice));
    insert = bindTime(insert, "start", o.periodStartsAt());
    insert = bindTime(insert, "end", o.periodEndsAt());
    insert = bindTime(insert, "creation", o.creationDeadline());
    insert =
        o.replacesInvoiceId() == null
            ? insert.bindNull("replaces", UUID.class)
            : insert.bind("replaces", o.replacesInvoiceId());
    return insert
        .fetch()
        .rowsUpdated()
        .thenMany(
            Flux.range(0, q.lines().size())
                .concatMap(
                    n -> {
                      var line = q.lines().get(n);
                      long tax = 0;
                      for (var item : line.getTaxesList())
                        tax = Math.addExact(tax, minor(item.getAmount(), currency));
                      return db.sql(
                              "INSERT INTO"
                                  + " taska.invoice_lines(tenant_id,invoice_id,line_no,description,quantity,net_minor,tax_minor,total_minor,taxes_snapshot,snapshot)"
                                  + " VALUES(:tenant,:invoice,:line,:description,:quantity,:net,:tax,:total,CAST(:taxes"
                                  + " AS jsonb),:snapshot)")
                          .bind("tenant", tenant)
                          .bind("invoice", id)
                          .bind("line", n + 1)
                          .bind("description", line.getDescription())
                          .bind("quantity", line.getQuantity())
                          .bind("net", minor(line.getNet(), currency))
                          .bind("tax", tax)
                          .bind("total", minor(line.getTotal(), currency))
                          .bind("taxes", json(line.getTaxesList()))
                          .bind("snapshot", encoded(line))
                          .fetch()
                          .rowsUpdated();
                    }))
        .then(
            db.sql("UPDATE taska.invoices SET state='OPEN' WHERE tenant_id=:tenant AND id=:id")
                .bind("tenant", tenant)
                .bind("id", id)
                .fetch()
                .rowsUpdated())
        .then(
            db.sql(
                    "INSERT INTO"
                        + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                        + " VALUES(:event,:tenant,'Invoice',:invoice,1,'InvoiceIssued',1,CAST(:payload"
                        + " AS jsonb),:op,'NEW',:now)")
                .bind("event", UUID.randomUUID())
                .bind("tenant", tenant)
                .bind("invoice", id)
                .bind("op", o.operationId())
                .bind(
                    "payload",
                    json(
                        Map.of(
                            "invoiceId",
                            id.toString(),
                            "planVersionId",
                            o.planVersionId().toString())))
                .bind("now", now.atOffset(ZoneOffset.UTC))
                .fetch()
                .rowsUpdated())
        .thenReturn(invoice);
  }

  private static DatabaseClient.GenericExecuteSpec bindTime(
      DatabaseClient.GenericExecuteSpec s, String name, Instant time) {
    return time == null
        ? s.bindNull(name, OffsetDateTime.class)
        : s.bind(name, time.atOffset(ZoneOffset.UTC));
  }

  public Mono<Invoice> get(GetInvoiceRpcRequest request) {
    UUID tenant = uuid(request.getTenantId()), id = uuid(request.getInvoiceId());
    return access
        .authorize(tenant, "getInvoice", false)
        .flatMap(
            v ->
                transactions.execute(
                    db ->
                        db.sql(
                                "SELECT snapshot FROM taska.invoices WHERE tenant_id=:tenant AND"
                                    + " id=:id AND state<>'DRAFT'")
                            .bind("tenant", tenant)
                            .bind("id", id)
                            .map((r, m) -> parsed(r.get("snapshot", String.class)))
                            .one()
                            .switchIfEmpty(
                                Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))));
  }

  private String binding(VerifiedTenantContext v, int limit) {
    var c = RpcContextBridge.details(v).getContext();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            hash(
                json(List.of(
                        "invoices/v1",
                        v.tenantId(),
                        v.actorId(),
                        c.getActorGeneration(),
                        c.getMembershipGeneration(),
                        c.getCredentialKind().name(),
                        c.getSessionId(),
                        c.getCredentialId(),
                        c.getGrantId(),
                        c.getGrantGeneration(),
                        document(c.getAdministrativeScope()),
                        c.getAdministrativeLevel().name(),
                        new TreeSet<>(v.scopes()),
                        limit))
                    .getBytes(StandardCharsets.UTF_8)));
  }

  public Mono<InvoicePage> list(ListInvoicesRpcRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return access
        .authorize(tenant, "listInvoices", false)
        .flatMap(
            v -> {
              int limit = request.getPage().getLimit() == 0 ? 50 : request.getPage().getLimit();
              if (limit < 1 || limit > 100) throw invalid();
              cursors.requireConfigured();
              String binding = binding(v, limit);
              var pos =
                  request.getPage().hasCursor()
                      ? cursors.decode(request.getPage().getCursor(), binding)
                      : null;
              return transactions.execute(
                  db ->
                      db.sql(
                              "SELECT snapshot,issued_at,id FROM taska.invoices WHERE"
                                  + " tenant_id=:tenant AND state<>'DRAFT' AND (:first OR"
                                  + " (issued_at,id)<(:after,:id)) ORDER BY issued_at DESC,id DESC"
                                  + " LIMIT :limit")
                          .bind("tenant", tenant)
                          .bind("first", pos == null)
                          .bind(
                              "after",
                              (pos == null ? Instant.EPOCH : pos.createdAt())
                                  .atOffset(ZoneOffset.UTC))
                          .bind("id", pos == null ? new UUID(0, 0) : pos.periodId())
                          .bind("limit", limit + 1)
                          .fetch()
                          .all()
                          .collectList()
                          .map(
                              rows -> {
                                var page = InvoicePage.newBuilder();
                                for (var row : rows.subList(0, Math.min(rows.size(), limit)))
                                  page.addItems(parsed((String) row.get("snapshot")));
                                if (rows.size() > limit) {
                                  var row = rows.get(limit - 1);
                                  page.setNextCursor(
                                      cursors.encode(
                                          binding,
                                          1,
                                          ((OffsetDateTime) row.get("issued_at")).toInstant(),
                                          (UUID) row.get("id")));
                                }
                                return page.build();
                              }));
            });
  }
}
