package ru.taska.billing;

import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Current primary authority and durable FREE admission; caller request fields are never proof. */
public final class PlanChangeDependencies {
  private PlanChangeDependencies() {}

  public enum Command {
    CHANGE,
    REPLAY,
    ISSUE_PLAN_INVOICE,
    CANCEL_RENEWAL,
    RESUME_RENEWAL,
    RECOVER_FREE
  }

  /** The current tenant owner version fences the separate root eligibility transaction. */
  public record Grant(UUID owner, long ownerVersion) {
    public Grant {
      Objects.requireNonNull(owner);
      if (ownerVersion <= 0) throw new IllegalArgumentException();
    }
  }

  public interface Policy {
    /** Current actor/grant, tenant lifecycle and command-specific financial authority. */
    Mono<Grant> require(VerifiedTenantContext context, Command command);
  }

  public record FreeProof(
      UUID tenant, UUID operation, UUID owner, long ownerVersion, boolean admitted) {
    public FreeProof(UUID tenant, UUID operation, UUID owner, long ownerVersion) {
      this(tenant, operation, owner, ownerVersion, true);
    }

    public FreeProof {
      Objects.requireNonNull(tenant);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(owner);
      if (ownerVersion <= 0) throw new IllegalArgumentException();
    }
  }

  public interface FreeAuthority {
    /** Idempotent primary admission for this exact durable operation and current owner version. */
    Mono<FreeProof> admit(UUID tenant, UUID operation, UUID owner, long ownerVersion);

    /** Read accepted admission after a crash; absence/timeout never invents a successful proof. */
    Mono<FreeProof> accepted(UUID tenant, UUID operation, UUID owner, long ownerVersion);

    /** Seal an unaccepted operation before abandoning it; returns admission if it already won. */
    Mono<FreeProof> cancel(UUID tenant, UUID operation, UUID owner, long ownerVersion);
  }
}
