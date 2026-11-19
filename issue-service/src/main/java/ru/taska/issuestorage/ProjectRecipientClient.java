package ru.taska.issuestorage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ProjectRecipientServiceGrpc;
import ru.taska.api.b2b.v1.ProjectRecipientsRequest;
import ru.taska.api.b2b.v1.ReactorProjectRecipientServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Read-only service identity не заменяет пользовательское разрешение на изменение подписок. */
public final class ProjectRecipientClient implements TenantIssueMetadataApi.Eligibility {
  private final ManagedDomainReads reads;

  public ProjectRecipientClient(ManagedDomainReads reads) {
    this.reads = reads;
  }

  public Mono<Set<UUID>> check(UUID project, List<UUID> ids, boolean contentRead) {
    if (ids.isEmpty()) return Mono.just(Set.of());
    if (ids.size() > 100)
      return Mono.error(
          new DomainException(DomainStatus.INVALID_ARGUMENT, "BOUNDED_RECIPIENTS_REQUIRED"));
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                reads.service(
                    "membership",
                    "project-service",
                    ProjectRecipientServiceGrpc.getCheckMethod().getFullMethodName(),
                    "checkProjectRecipients",
                    UUID.fromString(c.tenantId()),
                    Set.of("memberships:read", "profiles:read"),
                    context ->
                        ProjectRecipientsRequest.newBuilder()
                            .setContext(context)
                            .setProjectId(project.toString())
                            .addAllUserIds(ids.stream().map(UUID::toString).toList())
                            .setContentRead(contentRead)
                            .build(),
                    (q, auth) ->
                        ReactorProjectRecipientServiceGrpc.newReactorStub(
                                reads.channel("project-service"))
                            .withInterceptors(auth)
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .check(q)))
        .map(
            response -> {
              var result = new HashSet<UUID>();
              for (String text : response.getUserIdsList()) {
                UUID user = TenantIssueMetadataApi.id(text);
                if (!ids.contains(user) || !result.add(user))
                  throw new DomainException(
                      DomainStatus.UNAVAILABLE, "INVALID_RECIPIENT_AUTHORITY");
              }
              return Set.copyOf(result);
            });
  }
}
