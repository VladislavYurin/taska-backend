package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.*;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.billing.SubscriptionDependencies.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/**
 * Plan-change calendar/selection policy. TAS-352 supplies a verified FULL financial outcome through
 * SettlementEvidenceAuthority; invoice identity/state alone is never payment proof. Issued invoices
 * remain OPEN in the pre-settlement schema, so this policy does not invent a PAID state transition.
 */
public final class PlanCoveragePolicy {
  private PlanCoveragePolicy() {}

  public static Mono<CoverageEvidence> evidence(
      DatabaseClient db, PlanPayment payment, Instant now) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claims = RpcContextBridge.details(c).getContext();
              if (!payment.tenantId().toString().equals(c.tenantId())
                  || !"applySubscriptionCoverage".equals(c.action())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"billing-service".equals(claims.getIssuerService())
                  || !c.scopes().contains("write"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return selection(db, payment.tenantId())
                  .flatMap(
                      s -> {
                        if ("PREPARED".equals(s.get("change_state")))
                          return Mono.error(fail(DomainStatus.ABORTED, "FREE_TRANSITION_PENDING"));
                        if (payment.planChangeId() == null
                            || !payment.planChangeId().equals(s.get("change_id")))
                          return Mono.error(changed());
                        return invoice(
                                db,
                                payment.tenantId(),
                                payment.invoiceId(),
                                payment.planChangeId(),
                                (UUID) s.get("target_plan_version_id"))
                            .then(
                                window(
                                    db,
                                    payment.tenantId(),
                                    s,
                                    payment.source(),
                                    payment.succeededAt(),
                                    payment.confirmedAt(),
                                    now))
                            .map(
                                w ->
                                    new CoverageEvidence(
                                        payment.tenantId(),
                                        payment.invoiceId(),
                                        Objects.requireNonNull(payment.financialFactId()),
                                        (UUID) s.get("target_plan_version_id"),
                                        payment.planChangeId(),
                                        w.period().startsAt(),
                                        w.period().endsAt(),
                                        w.period().anchor().day(),
                                        w.period().anchor().timeUtc(),
                                        payment.confirmedAt(),
                                        payment.source(),
                                        w.superseded(),
                                        new SelectionBinding(
                                            payment.planChangeId(),
                                            (Long) s.get("selection_generation")),
                                        payment.succeededAt()));
                      });
            });
  }

  static Mono<Void> validate(DatabaseClient db, UUID tenant, CoverageEvidence e, Instant now) {
    return selection(db, tenant)
        .flatMap(
            s -> {
              if ("PREPARED".equals(s.get("change_state")))
                return Mono.error(fail(DomainStatus.ABORTED, "FREE_TRANSITION_PENDING"));
              long generation = e.selection() == null ? 0 : e.selection().generation();
              if (generation != (Long) s.get("selection_generation")) return Mono.error(changed());
              UUID op = e.selection() == null ? null : e.selection().operationId();
              if (op == null) {
                if (s.get("change_id") != null || generation > 0 && "FREE".equals(s.get("mode")))
                  return Mono.error(changed());
                return Mono
                    .empty(); // Existing initial/renewal evidence authority still owns its policy.
              }
              if (!op.equals(s.get("change_id"))
                  || !e.planVersionId().equals(s.get("target_plan_version_id")))
                return Mono.error(changed());
              return invoice(db, tenant, e.invoiceId(), op, e.planVersionId())
                  .then(window(db, tenant, s, e.source(), e.succeededAt(), e.confirmedAt(), now))
                  .flatMap(
                      w -> {
                        var p = w.period();
                        if (!p.startsAt().equals(e.startsAt())
                            || !p.endsAt().equals(e.endsAt())
                            || p.anchor().day() != e.anchorDay()
                            || !p.anchor().timeUtc().equals(e.anchorTimeUtc())
                            || !new HashSet<>(w.superseded())
                                .equals(new HashSet<>(e.supersededPeriodIds())))
                          return Mono.error(
                              fail(
                                  DomainStatus.FAILED_PRECONDITION,
                                  "PLAN_CHANGE_COVERAGE_MISMATCH"));
                        return Mono.empty();
                      });
            });
  }

  static Mono<Void> applied(DatabaseClient db, UUID tenant, CoverageEvidence e, Instant now) {
    if (e.selection() == null || e.selection().operationId() == null) return Mono.empty();
    UUID op = e.selection().operationId();
    return db.sql(
            "UPDATE taska.subscription_plan_changes SET state='APPLIED',applied_at=:now WHERE"
                + " tenant_id=:t AND id=:op AND state IN ('AWAITING_PAYMENT','SCHEDULED')")
        .bind("t", tenant)
        .bind("op", op)
        .bind("now", offset(now))
        .fetch()
        .rowsUpdated()
        .flatMap(n -> n == 1 ? Mono.<Void>empty() : Mono.error(changed()))
        .then(
            db.sql(
                    "UPDATE taska.subscriptions SET"
                        + " active_plan_change_id=NULL,next_plan_version_id=CASE WHEN :starts>:now"
                        + " THEN :plan::uuid ELSE NULL END,next_effective_at=CASE WHEN :starts>:now"
                        + " THEN :starts::timestamptz ELSE NULL END WHERE tenant_id=:t AND"
                        + " active_plan_change_id=:op AND selection_generation=:generation")
                .bind("t", tenant)
                .bind("op", op)
                .bind("generation", e.selection().generation())
                .bind("starts", offset(e.startsAt()))
                .bind("now", offset(now))
                .bind("plan", e.planVersionId())
                .fetch()
                .rowsUpdated())
        .flatMap(n -> n == 1 ? Mono.empty() : Mono.error(changed()));
  }

  private record Window(SubscriptionCalendar.Period period, List<UUID> superseded) {}

  private static Mono<Window> window(
      DatabaseClient db,
      UUID tenant,
      Map<String, Object> s,
      PaymentSource source,
      Instant succeeded,
      Instant confirmed,
      Instant now) {
    if (!Set.of("AWAITING_PAYMENT", "SCHEDULED").contains(s.get("change_state"))
        || source == null
        || confirmed == null
        || confirmed.isAfter(now)) return Mono.error(changed());
    Instant activation = source == PaymentSource.PSP ? succeeded : confirmed;
    if (activation == null
        || activation.isAfter(confirmed)
        || activation.getNano() % 1000 != 0
        || confirmed.getNano() % 1000 != 0) return Mono.error(invalid());
    boolean next = "NEXT_PERIOD".equals(s.get("effect"));
    Instant boundary =
        s.get("effective_at") == null ? null : ((OffsetDateTime) s.get("effective_at")).toInstant();
    if (next && boundary == null) return Mono.error(changed());
    if (next && !activation.isAfter(boundary)) {
      if (s.get("anchor_day") == null || s.get("anchor_time_utc") == null)
        return Mono.error(changed());
      var p =
          SubscriptionCalendar.next(
              boundary,
              new SubscriptionCalendar.Anchor(
                  ((Number) s.get("anchor_day")).intValue(), (LocalTime) s.get("anchor_time_utc")));
      return Mono.just(new Window(p, List.of()));
    }
    var p = SubscriptionCalendar.initial(activation);
    return db.sql(
            "SELECT id FROM taska.subscription_periods WHERE tenant_id=:t AND status='GRANTED'"
                + " ORDER BY id")
        .bind("t", tenant)
        .map((r, m) -> r.get("id", UUID.class))
        .all()
        .collectList()
        .map(ids -> new Window(p, ids));
  }

  private static Mono<Void> invoice(
      DatabaseClient db, UUID tenant, UUID invoice, UUID op, UUID plan) {
    return db.sql(
            "SELECT id FROM taska.invoices WHERE tenant_id=:t AND id=:invoice AND operation_id=:op"
                + " AND plan_version_id=:plan AND purpose='PLAN_CHANGE' AND state IN"
                + " ('OPEN','PARTIALLY_PAID','PAID')")
        .bind("t", tenant)
        .bind("invoice", Objects.requireNonNull(invoice))
        .bind("op", op)
        .bind("plan", plan)
        .fetch()
        .one()
        .switchIfEmpty(
            Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "PLAN_CHANGE_PAYMENT_NOT_CONFIRMED")))
        .then();
  }

  private static Mono<Map<String, Object>> selection(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT s.*,p.id change_id,p.target_plan_version_id,p.effect,p.effective_at,p.state"
                + " change_state FROM taska.subscriptions s LEFT JOIN"
                + " taska.subscription_plan_changes p ON p.tenant_id=s.tenant_id AND"
                + " p.id=s.active_plan_change_id WHERE s.tenant_id=:t FOR UPDATE OF s")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")));
  }

  private static OffsetDateTime offset(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static DomainException changed() {
    return fail(DomainStatus.FAILED_PRECONDITION, "PLAN_SELECTION_CHANGED");
  }
}
