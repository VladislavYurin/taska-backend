package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.billing.InvoiceDependencies.Obligation;
import ru.taska.billing.InvoiceDependencies.ObligationAuthority;
import ru.taska.exception.DomainStatus;

/** Stable invoice assignment, called under the subscription row lock owned by InvoiceStore. */
public final class RenewalCalendar implements ObligationAuthority {
  @Override
  public Mono<Obligation> resolve(
      DatabaseClient db, UUID tenant, CreateInvoiceRequest request, Instant now) {
    if (request.getPurpose() != CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL)
      return Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
    return db.sql(
            "SELECT id FROM taska.subscription_periods WHERE tenant_id=:tenant AND status='GRANTED'"
                + " AND starts_at<=:now ORDER BY (ends_at>:now) DESC,starts_at DESC,id DESC LIMIT"
                + " 1")
        .bind("tenant", tenant)
        .bind("now", now.atOffset(ZoneOffset.UTC))
        .map((r, m) -> r.get("id", UUID.class))
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "PAYMENT_EXPIRED")))
        .flatMap(period -> forPeriod(db, tenant, period, request, now, false));
  }

  Mono<Obligation> forPeriod(
      DatabaseClient db,
      UUID tenant,
      UUID period,
      CreateInvoiceRequest request,
      Instant now,
      boolean scheduled) {
    if (request.getPurpose() != CreateInvoiceRequest.PurposeValue.PURPOSE_RENEWAL) throw invalid();
    return db.sql(
            "SELECT p.*,s.renewal_enabled,s.renewal_cancelled FROM taska.subscription_periods p"
                + " JOIN taska.subscriptions s ON s.tenant_id=p.tenant_id WHERE p.tenant_id=:tenant"
                + " AND p.id=:period")
        .bind("tenant", tenant)
        .bind("period", period)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .flatMap(
            source -> {
              Instant start = ((OffsetDateTime) source.get("ends_at")).toInstant();
              var existing =
                  db.sql(
                          "SELECT * FROM taska.renewal_calendar_assignments WHERE tenant_id=:tenant"
                              + " AND period_starts_at=:start AND purpose='RENEWAL'")
                      .bind("tenant", tenant)
                      .bind("start", start.atOffset(ZoneOffset.UTC))
                      .fetch()
                      .one();
              return existing
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            if (!"GRANTED".equals(source.get("status"))
                                || now.isBefore(
                                    ((OffsetDateTime) source.get("starts_at")).toInstant())
                                || !now.isBefore(start)
                                || Boolean.TRUE.equals(source.get("renewal_cancelled"))
                                || (scheduled
                                    && !Boolean.TRUE.equals(source.get("renewal_enabled"))))
                              return Mono.error(
                                  fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
                            var next =
                                SubscriptionCalendar.next(
                                    start,
                                    new SubscriptionCalendar.Anchor(
                                        ((Number) source.get("anchor_day")).intValue(),
                                        (LocalTime) source.get("anchor_time_utc")));
                            return db.sql(
                                    "INSERT INTO"
                                        + " taska.renewal_calendar_assignments(tenant_id,id,source_period_id,plan_version_id,purpose,period_starts_at,period_ends_at,anchor_day,anchor_time_utc,created_at)"
                                        + " VALUES(:tenant,:id,:period,:plan,'RENEWAL',:start,:end,:day,:time,:now)"
                                        + " RETURNING *")
                                .bind("tenant", tenant)
                                .bind("id", UUID.randomUUID())
                                .bind("period", period)
                                .bind("plan", source.get("plan_version_id"))
                                .bind("start", start.atOffset(ZoneOffset.UTC))
                                .bind("end", next.endsAt().atOffset(ZoneOffset.UTC))
                                .bind("day", next.anchor().day())
                                .bind("time", next.anchor().timeUtc())
                                .bind("now", now.atOffset(ZoneOffset.UTC))
                                .fetch()
                                .one();
                          }))
                  .map(
                      row -> {
                        if (!row.get("plan_version_id")
                            .toString()
                            .equals(request.getPlanVersionId()))
                          throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
                        return obligation(tenant, row);
                      });
            });
  }

  private Obligation obligation(UUID tenant, Map<String, Object> row) {
    Instant start = ((OffsetDateTime) row.get("period_starts_at")).toInstant();
    return new Obligation(
        tenant,
        (UUID) row.get("id"),
        (UUID) row.get("plan_version_id"),
        Invoice.PurposeValue.PURPOSE_RENEWAL,
        "renewal:" + start,
        start,
        ((OffsetDateTime) row.get("period_ends_at")).toInstant(),
        null,
        null);
  }
}
