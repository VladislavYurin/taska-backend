package ru.taska.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** Verified financial inputs only; browser confirmations are never evidence. */
public final class FinancialLedgerDependencies {
  private FinancialLedgerDependencies() {}

  public enum Kind {
    PAYMENT,
    REFUND,
    CHARGEBACK,
    CHARGEBACK_REVERSAL
  }

  public enum Currency {
    EUR,
    USD
  }

  public enum Method {
    PSP,
    WIRE
  }

  public enum Environment {
    SANDBOX,
    LIVE
  }

  public enum Precision {
    EXACT,
    DAY
  }

  public record Source(
      String provider,
      String accountId,
      Environment environment,
      String externalId,
      Method method) {}

  public record Occurrence(Precision precision, Instant at, Instant notBefore, Instant before) {}

  /**
   * allocationId selects allocated principal; absent means unallocated principal, including excess.
   */
  public record Movement(
      UUID tenantId,
      Source source,
      Kind kind,
      UUID parentId,
      UUID allocationId,
      long amountMinor,
      Currency currency,
      Occurrence occurrence,
      Instant observedAt,
      Instant confirmedAt,
      UUID confirmedBy,
      String payerRef,
      String evidenceRef,
      String evidenceDigest) {}

  public interface EvidenceAuthority {
    /**
     * Resolve an immutable normalized, verified source movement on this same transaction.
     * Ingress/PSP/bank/refund owners verify signature, merchant/environment and actual success.
     */
    Mono<Movement> verified(DatabaseClient transaction, UUID tenant, UUID evidenceId);
  }

  public record AllocationDecision(List<UUID> matchIds, String decisionCode) {
    public AllocationDecision {
      matchIds = List.copyOf(matchIds);
    }
  }

  public interface EligibilityAuthority {
    /**
     * TAS-352 supplies full timeliness/lifecycle/payment eligibility; missing authority denies
     * allocation. A decision does not itself change invoice state or grant subscription rights.
     */
    Mono<AllocationDecision> eligible(
        DatabaseClient transaction, UUID tenant, UUID invoice, List<UUID> matchIds);
  }
}
