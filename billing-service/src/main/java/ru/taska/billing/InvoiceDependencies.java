package ru.taska.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;

/**
 * Server-owned eligibility and provisioned commercial policy. No permissive production defaults.
 */
public final class InvoiceDependencies {
  private InvoiceDependencies() {}

  /** Stable assignment/target identity, independent of retries, workers and payment method. */
  public record Obligation(
      UUID tenantId,
      UUID operationId,
      UUID planVersionId,
      Invoice.PurposeValue purpose,
      String targetKey,
      Instant periodStartsAt,
      Instant periodEndsAt,
      Instant creationDeadline,
      UUID replacesInvoiceId) {}

  public interface ObligationAuthority {
    /**
     * Resolve current eligible assignment under the locked subscription transaction.
     * Calendar/plan-change/lifecycle policy owns this decision (TAS-331/354/352). An existing
     * operation must keep its identity after expiry; replacements get a new identity.
     */
    Mono<Obligation> resolve(
        DatabaseClient transaction, UUID tenant, CreateInvoiceRequest request, Instant now);
  }

  public record CommercialQuote(
      String profileVersion,
      SellerSnapshot seller,
      List<InvoiceLine> lines,
      FxQuote fxQuote,
      WireInstructions wireInstructions,
      Duration validity) {
    public CommercialQuote {
      lines = List.copyOf(lines);
    }
  }

  public interface CommercialPolicy {
    /**
     * Provisioned seller, gross-inclusive tax breakdown, fresh approved FX and method readiness.
     * Amounts must exactly match the catalog gross (USD converted once HALF_UP). This adapter must
     * not accept a caller price, seller or tax rate as authority.
     */
    Mono<CommercialQuote> quote(
        DatabaseClient transaction, PlanVersion plan, CreateInvoiceRequest request, Instant now);
  }
}
