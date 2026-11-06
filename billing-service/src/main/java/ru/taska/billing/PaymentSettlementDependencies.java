package ru.taska.billing;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.TenantPaymentState;
import ru.taska.billing.FinancialLedgerDependencies.Movement;

/** Primary producer adapters. Missing verification/state must fail closed. */
public final class PaymentSettlementDependencies {
  private PaymentSettlementDependencies() {}

  public record VerifiedPaymentFact(
      UUID tenantId,
      UUID invoiceId,
      UUID operationId,
      String merchantProfileVersion,
      Movement movement) {}

  public interface EvidenceAuthority {
    /**
     * Resolve durable normalized FULL success from the trusted ingress/bank owner, on this
     * connection. Verify provider signature, merchant/account/environment and operation against the
     * frozen merchant profile before returning. Browser returns are never evidence.
     * Redeliveries/reconcile return the SAME normalized Movement, not delivery timestamps.
     */
    Mono<VerifiedPaymentFact> verified(DatabaseClient transaction, UUID tenant, UUID evidenceId);
  }

  public interface InitialPaymentDelivery {
    /** After billing commit only. Durable decision/outbox permits retry after response loss. */
    Mono<ru.taska.api.b2b.v1.TenantInitialPaymentResult> deliver(UUID tenant, UUID decision);
  }

  public interface TenantAuthority {
    /** Online Root state, outside billing SQL; never a cached lifecycle allow. */
    Mono<TenantPaymentState> current(UUID tenant);
  }
}
