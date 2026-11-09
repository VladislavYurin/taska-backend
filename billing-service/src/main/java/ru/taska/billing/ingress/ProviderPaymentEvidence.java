package ru.taska.billing.ingress;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.billing.FinancialLedgerDependencies;
import ru.taska.billing.PaymentSettlementDependencies;

/** Only the durable, immutable inbox proof can become PSP settlement evidence. */
public final class ProviderPaymentEvidence
    implements PaymentSettlementDependencies.EvidenceAuthority {
  @Override
  public Mono<PaymentSettlementDependencies.VerifiedPaymentFact> verified(
      DatabaseClient db, UUID tenant, UUID evidence) {
    return db.sql(
            "SELECT p.* FROM taska.provider_payment_proofs p JOIN taska.provider_pay_routes r"
                + " USING(provider,provider_account_id,environment,provider_payment_id)"
                + " WHERE p.id=:id AND p.tenant_id=:tenant AND r.tenant_id=p.tenant_id"
                + " AND r.invoice_id=p.invoice_id AND r.operation_id=p.operation_id"
                + " AND r.merchant_profile_version=p.merchant_profile_version")
        .bind("id", evidence)
        .bind("tenant", tenant)
        .fetch()
        .one()
        .map(
            r -> {
              var observed = ((OffsetDateTime) r.get("observed_at")).toInstant();
              var occurred = ((OffsetDateTime) r.get("occurred_at")).toInstant();
              var source =
                  new FinancialLedgerDependencies.Source(
                      r.get("provider").toString(),
                      r.get("provider_account_id").toString(),
                      "TEST".equals(r.get("environment"))
                          ? FinancialLedgerDependencies.Environment.SANDBOX
                          : FinancialLedgerDependencies.Environment.LIVE,
                      r.get("provider_payment_id").toString(),
                      FinancialLedgerDependencies.Method.PSP);
              var movement =
                  new FinancialLedgerDependencies.Movement(
                      tenant,
                      source,
                      FinancialLedgerDependencies.Kind.PAYMENT,
                      null,
                      null,
                      ((Number) r.get("amount_minor")).longValue(),
                      FinancialLedgerDependencies.Currency.valueOf(r.get("currency").toString()),
                      new FinancialLedgerDependencies.Occurrence(
                          FinancialLedgerDependencies.Precision.EXACT, occurred, null, null),
                      observed,
                      observed,
                      null,
                      null,
                      "provider-inbox/" + evidence,
                      r.get("evidence_digest").toString());
              return new PaymentSettlementDependencies.VerifiedPaymentFact(
                  tenant,
                  (UUID) r.get("invoice_id"),
                  (UUID) r.get("operation_id"),
                  r.get("merchant_profile_version").toString(),
                  movement);
            });
  }
}
