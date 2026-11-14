package ru.taska.issuestorage;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.capability.Action;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/**
 * Актуальные полномочия от сервисов-владельцев. Разрешение ограничено по времени и привязано к
 * проверенному вызову и проекту.
 */
public interface TenantIssueAccess {
  record Permit(
      UUID tenant,
      UUID actor,
      String operation,
      UUID project,
      Instant until,
      boolean bypassBusinessRules,
      boolean auditRequired,
      Set<String> bypassed) {
    public Permit {
      bypassed = Set.copyOf(bypassed);
    }

    public void check(UUID tenant, String actor, String operation, UUID project) {
      if (!this.tenant.equals(tenant)
          || !this.actor.toString().equals(actor)
          || !this.operation.equals(operation)
          || !this.project.equals(project))
        throw new DomainException(DomainStatus.PERMISSION_DENIED, "ISSUE_AUTHORITY_MISMATCH");
      if (!Instant.now().isBefore(until))
        throw new DomainException(DomainStatus.UNAVAILABLE, "ISSUE_AUTHORITY_EXPIRED");
    }
  }

  record ReadBatch(java.util.List<Permit> permits, Long limit, boolean unlimited) {
    public ReadBatch {
      permits = java.util.List.copyOf(permits);
    }
  }

  default Mono<ReadBatch> readMany(java.util.List<UUID> projects) {
    return Mono.error(
        new DomainException(DomainStatus.UNAVAILABLE, "BULK_QUOTA_AUTHORITY_UNAVAILABLE"));
  }

  Mono<Permit> authorize(Action action, UUID project, UUID issue);

  Mono<String> projectKey(UUID project);

  Mono<String> initialStatus(UUID project, String type, String requestedStatus);

  Mono<String> transition(TenantIssueStore.Snapshot issue, UUID transition, String payload);

  Mono<Void> assignee(UUID project, UUID user);
}
