package ru.taska.billing;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Stable invoice assignment for an accepted owner choice, independent of payment method/retry. */
public final class PlanChangeObligations {
  private PlanChangeObligations() {}

  public static Mono<InvoiceDependencies.Obligation> resolve(
      DatabaseClient db,
      UUID tenant,
      CreateInvoiceRequest request,
      PlanChangeDependencies.Policy policy) {
    if (request.getPurpose() != CreateInvoiceRequest.PurposeValue.PURPOSE_PLAN_CHANGE)
      return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
    if (!Set.of(
            CreateInvoiceRequest.CurrencyValue.CURRENCY_EUR,
            CreateInvoiceRequest.CurrencyValue.CURRENCY_USD)
        .contains(request.getCurrency())) return Mono.error(CatalogErrors.invalid());
    String currency =
        request.getCurrency() == CreateInvoiceRequest.CurrencyValue.CURRENCY_EUR ? "EUR" : "USD";
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                policy
                    .require(c, PlanChangeDependencies.Command.ISSUE_PLAN_INVOICE)
                    .timeout(Duration.ofSeconds(5))
                    .switchIfEmpty(
                        Mono.error(
                            new DomainException(
                                DomainStatus.UNAVAILABLE, "PLAN_POLICY_NOT_CONFIGURED")))
                    .flatMap(
                        grant ->
                            db.sql(
                                    "SELECT p.* FROM taska.subscriptions s JOIN"
                                        + " taska.subscription_plan_changes p ON"
                                        + " p.tenant_id=s.tenant_id AND"
                                        + " p.id=s.active_plan_change_id AND"
                                        + " p.selection_generation=s.selection_generation WHERE"
                                        + " s.tenant_id=:t AND p.state IN"
                                        + " ('AWAITING_PAYMENT','SCHEDULED')")
                                .bind("t", tenant)
                                .fetch()
                                .one()
                                .switchIfEmpty(
                                    Mono.error(
                                        new DomainException(
                                            DomainStatus.FAILED_PRECONDITION,
                                            "PLAN_SELECTION_CHANGED")))
                                .map(
                                    p -> {
                                      if (!tenant.toString().equals(c.tenantId())
                                          || !p.get("target_plan_version_id")
                                              .toString()
                                              .equals(request.getPlanVersionId())
                                          || !Objects.equals(p.get("currency"), currency)
                                          || !grant.owner().equals(p.get("owner_id"))
                                          || !Long.valueOf(grant.ownerVersion())
                                              .equals(p.get("owner_version")))
                                        throw new DomainException(
                                            DomainStatus.FAILED_PRECONDITION,
                                            "PLAN_SELECTION_CHANGED");
                                      UUID op = (UUID) p.get("id");
                                      // The paid window is determined by verified
                                      // success/confirmation, not invoice creation.
                                      return new InvoiceDependencies.Obligation(
                                          tenant,
                                          op,
                                          (UUID) p.get("target_plan_version_id"),
                                          Invoice.PurposeValue.PURPOSE_PLAN_CHANGE,
                                          "plan-change:" + op,
                                          null,
                                          null,
                                          null,
                                          null);
                                    })));
  }
}
