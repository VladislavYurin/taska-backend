package ru.taska.quota;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.QuotaUsage;

/**
 * Internal primary-authority ports. Values from HTTP/request bodies are never proofs. Owner calls
 * must not call quota authority while holding an owner SQL transaction.
 */
public final class ProjectQuotaProtocol {
  private ProjectQuotaProtocol() {}

  public enum State {
    ACTIVE,
    COMMITTED,
    RELEASED,
    EXPIRED
  }

  public enum OwnerState {
    PROVISIONING,
    OPEN,
    CANCELLED,
    DELETED
  }

  public record Reservation(
      UUID tenant,
      UUID operation,
      UUID resource,
      UUID id,
      long fence,
      Instant expiresAt,
      State state,
      long version,
      boolean cancelRequested) {
    public Reservation(
        UUID tenant,
        UUID operation,
        UUID resource,
        UUID id,
        long fence,
        Instant expiresAt,
        State state,
        long version) {
      this(tenant, operation, resource, id, fence, expiresAt, state, version, false);
    }

    public Reservation {
      Objects.requireNonNull(tenant);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(resource);
      Objects.requireNonNull(id);
      Objects.requireNonNull(expiresAt);
      Objects.requireNonNull(state);
      if (fence <= 0 || version <= 0) throw new IllegalArgumentException();
    }

    public boolean sameIdentity(Reservation r) {
      return tenant.equals(r.tenant)
          && operation.equals(r.operation)
          && resource.equals(r.resource)
          && id.equals(r.id)
          && fence == r.fence;
    }
  }

  public record Receipt(Reservation reservation, OwnerState state) {
    public Receipt {
      Objects.requireNonNull(reservation);
      Objects.requireNonNull(state);
    }
  }

  /**
   * Complete primary inventory of OPEN projects, including archived projects. Never a partial page.
   */
  public record Inventory(UUID tenant, Set<UUID> resources) {
    public Inventory {
      Objects.requireNonNull(tenant);
      resources = Set.copyOf(resources);
    }
  }

  public interface Authority {
    Mono<Reservation> reserve(UUID operation, UUID resource, Instant expiresAt);

    Mono<Reservation> get(UUID operation);

    Mono<Reservation> commit(Reservation reservation);

    Mono<Reservation> release(Reservation reservation);
  }

  public interface Owner {
    /** Empty means no materialized receipt yet; it never proves cancellation. */
    Mono<Receipt> inspect(Reservation reservation);

    /** Seal CANCELLED even when no receipt/resource exists. Reject cancellation of OPEN. */
    Mono<Receipt> cancel(Reservation reservation);

    Mono<Inventory> inventory(UUID tenant);

    /** Called only after authority transaction commits; verify primary COMMITTED before opening. */
    Mono<Receipt> open(Reservation reservation);
  }

  @FunctionalInterface
  public interface UsageSource {
    Mono<QuotaUsage> current(UUID tenant);
  }
}
