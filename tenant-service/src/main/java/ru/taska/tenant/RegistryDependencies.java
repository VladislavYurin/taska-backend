package ru.taska.tenant;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/** Implementations supplied by catalog TAS-313 and atomic FREE admission TAS-472. */
public final class RegistryDependencies {
  private RegistryDependencies() {}

  public record PublishedPlan(UUID id, boolean free) {}

  public interface PublishedPlans {
    Mono<PublishedPlan> resolve(UUID planVersionId);
  }

  public interface FreeAdmission {
    /** Must reserve in this transaction/connection, never by remote check-then-insert. */
    Mono<Void> reserve(DatabaseClient transaction, UUID actor, UUID tenant);

    /** Resolve identity/KMS/policy before opening the write transaction. */
    default Mono<FreeAdmission> prepare(UUID actor) {
      return Mono.just(this);
    }
  }
}
