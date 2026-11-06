package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.*;
import static ru.taska.billing.PaymentSettlementDependencies.*;
import static ru.taska.billing.SubscriptionDependencies.*;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.FinancialLedgerDependencies.Kind;
import ru.taska.billing.FinancialLedgerDependencies.Precision;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;
import tools.jackson.databind.json.JsonMapper;

/** Settlement owns one billing transaction; it never calls a PSP or a remote service inside SQL. */
public final class PaymentSettlement {
  private final SubscriptionStore subscriptions;
  private final FinancialLedger ledger;
  private final EvidenceAuthority evidence;
  private final TenantAuthority tenants;
  private final Clock clock;
  private final InitialPaymentDelivery delivery;
  private final JsonMapper json = JsonMapper.builder().build();

  public PaymentSettlement(
      SubscriptionStore subscriptions,
      FinancialLedger ledger,
      EvidenceAuthority evidence,
      TenantAuthority tenants,
      InitialPaymentDelivery delivery,
      Clock clock) {
    this.subscriptions = subscriptions;
    this.ledger = ledger;
    this.evidence = evidence;
    this.tenants = tenants;
    this.clock = clock;
    this.delivery = delivery;
  }

  private Mono<VerifiedTenantContext> service(
      UUID tenant, String action, String issuer, String scope) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (!tenant.toString().equals(c.tenantId())
                  || !action.equals(c.action())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || c.actorId() != null
                  || !issuer.equals(claims.getIssuerService())
                  || !c.scopes().contains(scope))
                throw fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
              return c;
            });
  }

  public Mono<PaymentSettlementResult> apply(UUID tenant, UUID evidenceId) {
    return service(tenant, "applyVerifiedPayment", "billing-service", "ledger:write")
        .filter(c -> c.scopes().contains("write"))
        .switchIfEmpty(Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED")))
        .flatMap(
            c ->
                tenants
                    .current(tenant)
                    .timeout(Duration.ofSeconds(5))
                    .switchIfEmpty(
                        Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
                    .flatMap(
                        state -> {
                          if (!tenant.toString().equals(state.getTenantId())
                              || state.getVersion().isBlank())
                            return Mono.error(
                                fail(DomainStatus.UNAVAILABLE, "TENANT_STATE_MISMATCH"));
                          return subscriptions
                              .transactions()
                              .execute(
                                  db ->
                                      lockSubscription(db, tenant)
                                          .flatMap(
                                              s ->
                                                  evidence
                                                      .verified(db, tenant, evidenceId)
                                                      .switchIfEmpty(
                                                          Mono.error(
                                                              fail(
                                                                  DomainStatus.UNAVAILABLE,
                                                                  "PAYMENT_EVIDENCE_UNAVAILABLE")))
                                                      .flatMap(
                                                          f ->
                                                              settle(db, tenant, c, state, s, f))));
                        }))
        .flatMap(this::afterCommit);
  }

  private Mono<PaymentSettlementResult> afterCommit(PaymentSettlementResult result) {
    if (!result.getRequiresTenantActivation()) return Mono.just(result);
    return delivery
        .deliver(UUID.fromString(result.getTenantId()), UUID.fromString(result.getDecisionId()))
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
        .thenReturn(result);
  }

  private Mono<Map<String, Object>> lockSubscription(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT * FROM taska.subscriptions WHERE tenant_id=:t FOR UPDATE")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .flatMap(
            subscription -> {
              if (subscription.get("active_plan_change_id") == null) return Mono.just(subscription);
              // New READ COMMITTED statement after lock acquisition: no pre-wait JOIN snapshot.
              // Every intent writer owns this subscription lock first (TAS-354).
              return db.sql(
                      "SELECT state FROM taska.subscription_plan_changes WHERE tenant_id=:t AND"
                          + " id=:id")
                  .bind("t", tenant)
                  .bind("id", subscription.get("active_plan_change_id"))
                  .fetch()
                  .one()
                  .switchIfEmpty(Mono.error(fail(DomainStatus.ABORTED, "PLAN_SELECTION_CHANGED")))
                  .map(
                      intent -> {
                        var current = new HashMap<String, Object>(subscription);
                        current.put("change_state", intent.get("state"));
                        return current;
                      });
            });
  }

  private Mono<PaymentSettlementResult> settle(
      DatabaseClient db,
      UUID tenant,
      VerifiedTenantContext c,
      TenantPaymentState state,
      Map<String, Object> subscription,
      VerifiedPaymentFact f) {
    if (f == null
        || !tenant.equals(f.tenantId())
        || f.invoiceId() == null
        || f.operationId() == null
        || f.movement() == null
        || f.movement().kind() != Kind.PAYMENT
        || !tenant.equals(f.movement().tenantId())) return Mono.error(invalid());
    return db.sql(
            "SELECT *,pricing_snapshot::text pricing_document FROM taska.invoices WHERE"
                + " tenant_id=:t AND id=:id AND state<>'DRAFT' FOR UPDATE")
        .bind("t", tenant)
        .bind("id", f.invoiceId())
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .flatMap(
            i -> {
              var m = f.movement();
              if (!f.operationId().equals(i.get("operation_id"))
                  || f.merchantProfileVersion() == null
                  || !f.merchantProfileVersion()
                      .equals(
                          json.readTree(i.get("pricing_document").toString())
                              .path("profileVersion")
                              .asText())
                  || m.source() == null
                  || !m.source().method().name().equals(i.get("method"))
                  || m.currency() == null
                  || !m.currency().name().equals(i.get("currency")))
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "PAYMENT_BINDING_MISMATCH"));
              return ledger
                  .recordConfirmed(db, tenant, m)
                  .flatMap(
                      fact ->
                          existing(db, tenant, fact.id())
                              .flatMap(
                                  previous ->
                                      previous.getInvoiceId().equals(f.invoiceId().toString())
                                          ? Mono.just(previous)
                                          : Mono.error(
                                              fail(
                                                  DomainStatus.ALREADY_EXISTS,
                                                  "FINANCIAL_FACT_CONFLICT")))
                              .switchIfEmpty(
                                  Mono.defer(
                                      () -> {
                                        if ("PREPARED".equals(subscription.get("change_state")))
                                          return Mono.error(
                                              fail(
                                                  DomainStatus.ABORTED, "FREE_TRANSITION_PENDING"));
                                        String denial = denial(state, subscription, i, f);
                                        if (denial != null)
                                          return save(db, tenant, c, state, i, fact, null, denial);
                                        if (subscription.get("active_plan_change_id") != null
                                            && !"PLAN_CHANGE".equals(i.get("purpose")))
                                          return Mono.error(
                                              fail(DomainStatus.ABORTED, "PLAN_SELECTION_PENDING"));
                                        return coverage(db, tenant, subscription, i, fact)
                                            .flatMap(
                                                coverage ->
                                                    ledger
                                                        .matchConfirmed(
                                                            db, tenant, fact.id(), f.invoiceId())
                                                        .flatMap(
                                                            match ->
                                                                ledger.allocateConfirmed(
                                                                    db, tenant, match))
                                                        .then(
                                                            db.sql(
                                                                    "UPDATE taska.invoices SET"
                                                                        + " state='PAID' WHERE"
                                                                        + " tenant_id=:t AND id=:id"
                                                                        + " AND state IN"
                                                                        + " ('OPEN','PARTIALLY_PAID')")
                                                                .bind("t", tenant)
                                                                .bind("id", f.invoiceId())
                                                                .fetch()
                                                                .rowsUpdated())
                                                        .flatMap(
                                                            n ->
                                                                n == 1
                                                                    ? subscriptions.grantConfirmed(
                                                                        db, tenant, coverage)
                                                                    : Mono.error(
                                                                        fail(
                                                                            DomainStatus.ABORTED,
                                                                            "INVOICE_CHANGED")))
                                                        .then(
                                                            save(
                                                                db,
                                                                tenant,
                                                                c,
                                                                state,
                                                                i,
                                                                fact,
                                                                coverage,
                                                                "FULL_TIMELY_VERIFIED")));
                                      })));
            });
  }

  private String denial(
      TenantPaymentState state,
      Map<String, Object> s,
      Map<String, Object> i,
      VerifiedPaymentFact f) {
    var m = f.movement();
    Instant now = clock.instant();
    if (m.confirmedAt() == null
        || m.confirmedAt().isAfter(now)
        || m.observedAt() == null
        || m.observedAt().isAfter(now)) throw invalid();
    if (m.amountMinor() != ((Number) i.get("total_minor")).longValue())
      return "FULL_PAYMENT_REQUIRED";
    if (!Set.of("OPEN", "PARTIALLY_PAID").contains(i.get("state"))) return "INVOICE_ALREADY_CLOSED";
    if (m.occurrence().precision() != Precision.EXACT) return "PRECISE_PAYMENT_TIME_REQUIRED";
    Instant occurred = m.occurrence().at();
    if (occurred.isAfter(m.confirmedAt())) throw invalid();
    if (occurred.isAfter(time(i, "payable_until"))) return "PAYMENT_AFTER_DEADLINE";
    if (Set.of(
            Tenant.LifecycleValue.LIFECYCLE_OWNER_CANCELLED,
            Tenant.LifecycleValue.LIFECYCLE_DELETING,
            Tenant.LifecycleValue.LIFECYCLE_DELETED,
            Tenant.LifecycleValue.LIFECYCLE_UNSPECIFIED,
            Tenant.LifecycleValue.UNRECOGNIZED)
        .contains(state.getLifecycle())) return "TENANT_NOT_ELIGIBLE";
    if ("INITIAL".equals(i.get("purpose"))) {
      if (!Set.of(
                  Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT,
                  Tenant.LifecycleValue.LIFECYCLE_AUTO_CLOSED)
              .contains(state.getLifecycle())
          || !state.getCreationOperationId().equals(i.get("operation_id").toString())
          || !state.getPlanVersionId().equals(i.get("plan_version_id").toString())
          || !state.hasCreationDeadlineAt()
          || i.get("creation_deadline_snapshot") == null) return "INITIAL_PAYMENT_NOT_ELIGIBLE";
      if (occurred.isAfter(Instant.parse(state.getCreationDeadlineAt()))
          || occurred.isAfter(time(i, "creation_deadline_snapshot")))
        return "PAYMENT_AFTER_DEADLINE";
    } else if (state.getLifecycle() != Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
      return "TENANT_NOT_ELIGIBLE";
    if (!Objects.equals(s.get("selection_generation"), i.get("selection_generation")))
      return "PLAN_SELECTION_CHANGED";
    if (!"PLAN_CHANGE".equals(i.get("purpose"))
        && !s.get("selected_plan_version_id").equals(i.get("plan_version_id")))
      return "PLAN_SELECTION_CHANGED";
    return null;
  }

  private Mono<CoverageEvidence> coverage(
      DatabaseClient db,
      UUID tenant,
      Map<String, Object> s,
      Map<String, Object> invoice,
      FinancialLedger.Fact fact) {
    var m = fact.movement();
    PaymentSource source = PaymentSource.valueOf(m.source().method().name());
    UUID invoiceId = (UUID) invoice.get("id"),
        operation = (UUID) invoice.get("operation_id"),
        plan = (UUID) invoice.get("plan_version_id");
    Instant occurred = m.occurrence().at(), confirmed = m.confirmedAt();
    if ("PLAN_CHANGE".equals(invoice.get("purpose")))
      return PlanCoveragePolicy.evidence(
          db,
          new PlanPayment(
              tenant,
              operation,
              invoiceId,
              fact.id(),
              source,
              source == PaymentSource.PSP ? occurred : null,
              confirmed),
          clock.instant());
    return db.sql(
            "SELECT * FROM taska.subscription_periods WHERE tenant_id=:t AND status='GRANTED' ORDER"
                + " BY ends_at DESC,id DESC LIMIT 1")
        .bind("t", tenant)
        .fetch()
        .one()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(
            previous -> {
              var p = previous.orElse(Map.of());
              boolean same = plan.equals(p.get("plan_version_id"));
              var period =
                  SubscriptionCalendar.settled(
                      source,
                      source == PaymentSource.PSP ? occurred : null,
                      occurred,
                      confirmed,
                      same,
                      p.isEmpty() ? null : time(p, "ends_at"),
                      p.isEmpty()
                          ? null
                          : new SubscriptionCalendar.Anchor(
                              ((Number) p.get("anchor_day")).intValue(),
                              (LocalTime) p.get("anchor_time_utc")));
              return new CoverageEvidence(
                  tenant,
                  invoiceId,
                  fact.id(),
                  plan,
                  operation,
                  period.startsAt(),
                  period.endsAt(),
                  period.anchor().day(),
                  period.anchor().timeUtc(),
                  confirmed,
                  source,
                  List.of(),
                  new SelectionBinding(null, ((Number) s.get("selection_generation")).longValue()),
                  source == PaymentSource.PSP ? occurred : null);
            });
  }

  private Mono<PaymentSettlementResult> existing(DatabaseClient db, UUID tenant, UUID fact) {
    return db.sql(
            "SELECT response FROM taska.payment_settlements WHERE tenant_id=:t AND"
                + " financial_fact_id=:fact")
        .bind("t", tenant)
        .bind("fact", fact)
        .map((r, m) -> parse(r.get("response", String.class)))
        .one();
  }

  private Mono<PaymentSettlementResult> save(
      DatabaseClient db,
      UUID tenant,
      VerifiedTenantContext context,
      TenantPaymentState state,
      Map<String, Object> invoice,
      FinancialLedger.Fact fact,
      CoverageEvidence coverage,
      String reason) {
    UUID id = UUID.randomUUID();
    var b =
        PaymentSettlementResult.newBuilder()
            .setDecisionId(id.toString())
            .setTenantId(tenant.toString())
            .setInvoiceId(invoice.get("id").toString())
            .setFinancialFactId(fact.id().toString())
            .setReasonCode(reason)
            .setOutcome(
                coverage == null
                    ? PaymentSettlementResult.Outcome.OUTCOME_REVIEW_REQUIRED
                    : PaymentSettlementResult.Outcome.OUTCOME_APPLIED);
    if (coverage != null) b.setCoverageId(coverage.activationOperationId().toString());
    boolean initial = "INITIAL".equals(invoice.get("purpose"));
    b.setRequiresTenantActivation(coverage != null && initial);
    var result = b.build();
    Instant occurred =
        fact.movement().occurrence().precision() == Precision.EXACT
            ? fact.movement().occurrence().at()
            : fact.movement().occurrence().notBefore();
    var insert =
        db.sql(
                "INSERT INTO"
                    + " taska.payment_settlements(id,tenant_id,invoice_id,financial_fact_id,coverage_id,creation_operation_id,occurred_at,invoice_deadline,plan_version_id,outcome,reason_code,response)"
                    + " VALUES(:id,:t,:i,:f,:c,:op,:at,:deadline,:plan,:outcome,:reason,:response)")
            .bind("id", id)
            .bind("t", tenant)
            .bind("i", invoice.get("id"))
            .bind("f", fact.id())
            .bind("at", offset(occurred))
            .bind("deadline", invoice.get("payable_until"))
            .bind("plan", invoice.get("plan_version_id"))
            .bind("outcome", coverage == null ? "REVIEW_REQUIRED" : "APPLIED")
            .bind("reason", reason)
            .bind("response", Base64.getEncoder().encodeToString(result.toByteArray()));
    insert =
        coverage == null
            ? insert.bindNull("c", UUID.class)
            : insert.bind("c", coverage.activationOperationId());
    insert =
        initial
            ? insert.bind("op", invoice.get("operation_id"))
            : insert.bindNull("op", UUID.class);
    String type =
        coverage == null
            ? "PaymentSettlementReviewRequired"
            : initial ? "TenantInitialPaymentDecisionReady" : "InvoicePaid";
    return insert
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "INSERT INTO"
                        + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                        + " VALUES(:event,:t,'PaymentSettlement',:id,1,:type,1,CAST(:payload AS"
                        + " jsonb),:id,'NEW',:now)")
                .bind("event", UUID.randomUUID())
                .bind("t", tenant)
                .bind("id", id)
                .bind("type", type)
                .bind(
                    "payload",
                    json.writeValueAsString(
                        Map.of(
                            "decisionId",
                            id.toString(),
                            "invoiceId",
                            invoice.get("id").toString(),
                            "financialFactId",
                            fact.id().toString(),
                            "reasonCode",
                            reason,
                            "issuer",
                            "billing-service",
                            "requestId",
                            context.requestId(),
                            "rootVersion",
                            state.getVersion())))
                .bind("now", offset(clock.instant()))
                .fetch()
                .rowsUpdated())
        .thenReturn(result);
  }

  public Mono<InitialPaymentDecision> initialDecision(UUID tenant, UUID decision) {
    return service(tenant, "getTenantInitialPaymentDecision", "tenant-service", "read")
        .flatMap(
            c ->
                subscriptions
                    .transactions()
                    .execute(
                        db ->
                            db.sql(
                                    "SELECT d.* FROM taska.payment_settlements d JOIN"
                                        + " taska.subscription_periods p ON p.tenant_id=d.tenant_id"
                                        + " AND p.id=d.coverage_id WHERE d.tenant_id=:t AND"
                                        + " d.id=:id AND d.outcome='APPLIED' AND"
                                        + " d.creation_operation_id IS NOT NULL AND"
                                        + " p.status='GRANTED'")
                                .bind("t", tenant)
                                .bind("id", decision)
                                .fetch()
                                .one()
                                .switchIfEmpty(
                                    Mono.error(
                                        fail(
                                            DomainStatus.NOT_FOUND, "PAYMENT_DECISION_NOT_ACTIVE")))
                                .map(
                                    d ->
                                        InitialPaymentDecision.newBuilder()
                                            .setDecisionId(decision.toString())
                                            .setTenantId(tenant.toString())
                                            .setCreationOperationId(
                                                d.get("creation_operation_id").toString())
                                            .setInvoiceId(d.get("invoice_id").toString())
                                            .setFinancialFactId(
                                                d.get("financial_fact_id").toString())
                                            .setPlanVersionId(d.get("plan_version_id").toString())
                                            .setCoverageId(d.get("coverage_id").toString())
                                            .setOccurredAt(time(d, "occurred_at").toString())
                                            .setInvoiceDeadline(
                                                time(d, "invoice_deadline").toString())
                                            .build())));
  }

  private static Instant time(Map<String, Object> row, String field) {
    return ((OffsetDateTime) row.get(field)).toInstant();
  }

  private static OffsetDateTime offset(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private static PaymentSettlementResult parse(String encoded) {
    try {
      return PaymentSettlementResult.parseFrom(Base64.getDecoder().decode(encoded));
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException("Invalid settlement receipt", e);
    }
  }
}
