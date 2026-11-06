package ru.taska.tenant;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** The billing owner resolves immutable primary evidence; request bodies contain its ID only. */
public final class TenantCreationDecisions {
  private TenantCreationDecisions() {}

  public enum Precision {
    EXACT,
    DAY
  }

  /**
   * Full/timely assessment covers every contributing payment, not just the last callback.
   * coverageId denotes already committed GRANTED coverage. No PSP/browser return is evidence.
   */
  public record InitialPayment(
      UUID decisionId,
      UUID tenantId,
      UUID creationOperationId,
      UUID financialFactId,
      UUID invoiceId,
      UUID planVersionId,
      boolean full,
      UUID coverageId,
      Precision precision,
      Instant occurredAt,
      Instant occurredNotBefore,
      Instant occurredBefore,
      Instant invoiceDeadline) {}

  public interface Authority {
    /**
     * Resolve a durable, verified settlement decision from the primary owner, outside tenant SQL.
     */
    Mono<InitialPayment> resolve(UUID tenant, UUID decisionId);
  }
}
