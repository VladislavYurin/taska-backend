package ru.taska.billing;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Primary authoritative adapters, never browser claims. Missing adapters must fail closed. */
public final class SubscriptionDependencies {
  private SubscriptionDependencies() {}

  public interface FinancialReadAuthority {
    /** Current ACTIVE tenant owner, or current explicitly scoped platform administrator. */
    Mono<Boolean> allowed(VerifiedTenantContext context);
  }

  public enum PaymentSource {
    PSP,
    WIRE
  }

  /** Calendar/settlement supplies confirmed full coverage, with the original UTC monthly anchor. */
  public record CoverageEvidence(
      UUID tenantId,
      UUID invoiceId,
      UUID financialFactId,
      UUID planVersionId,
      UUID activationOperationId,
      Instant startsAt,
      Instant endsAt,
      int anchorDay,
      LocalTime anchorTimeUtc,
      Instant confirmedAt,
      PaymentSource source,
      List<UUID> supersededPeriodIds) {
    public CoverageEvidence {
      supersededPeriodIds = List.copyOf(supersededPeriodIds);
    }
  }

  /** Final financial reversal, bound only to the original invoice and source fact. */
  public record RevocationEvidence(UUID tenantId, UUID invoiceId, UUID financialFactId) {}

  public interface SettlementEvidenceAuthority {
    /** Execute against this same tenant connection/transaction (TAS-352/331/354). */
    Mono<CoverageEvidence> confirmed(DatabaseClient transaction, UUID tenantId, UUID evidenceId);

    /** Final refund/chargeback outcome, including D251; no unconfirmed client instruction. */
    Mono<RevocationEvidence> reversed(DatabaseClient transaction, UUID tenantId, UUID evidenceId);
  }
}
