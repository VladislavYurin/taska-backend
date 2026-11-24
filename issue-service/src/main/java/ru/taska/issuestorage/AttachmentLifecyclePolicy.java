package ru.taska.issuestorage;

import java.time.Duration;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.capability.Action;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** Accepted deletion is a separate authority from an accepted upload. */
public final class AttachmentLifecyclePolicy {
  private final AttachmentLifecycleStore store;
  private final TenantIssueStore issues;
  private final TenantIssueAccess access;

  public AttachmentLifecyclePolicy(
      AttachmentLifecycleStore store, TenantIssueStore issues, TenantIssueAccess access) {
    this.store = store;
    this.issues = issues;
    this.access = access;
  }

  public Mono<TenantIssueAccess.Permit> authorize(Action action, UUID project, UUID issue) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (RpcContextBridge.details(c).getContext().getCredentialKind()
                  == CredentialKind.CREDENTIAL_KIND_SERVICE)
                return Mono.error(denied("USER_ATTACHMENT_AUTHORITY_REQUIRED"));
              return access
                  .authorize(action, project, issue)
                  .flatMap(
                      p ->
                          issues.execute(
                              s ->
                                  s.findActive(issue)
                                      .filter(row -> row.identity().projectId().equals(project))
                                      .switchIfEmpty(
                                          Mono.error(
                                              new DomainException(
                                                  DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND")))
                                      .flatMap(
                                          row -> {
                                            p.check(
                                                UUID.fromString(c.tenantId()),
                                                c.actorId(),
                                                c.action(),
                                                project);
                                            return s.audit(p, issue).thenReturn(p);
                                          })));
            });
  }

  public Mono<Boolean> bound(AttachmentUploadStore.Upload upload, String phase) {
    if (phase.equals("PROJECT_CLEANUP")) return Mono.error(denied("PROJECT_DELETE_PROOF_REQUIRED"));
    if (upload == null) return Mono.just(false);
    if (!Set.of("LOOKUP", "INSPECT", "DELETE_USAGE", "DIRECT_DELETE", "RECONCILE").contains(phase))
      return Mono.just(false);
    return store
        .hasDeletion(upload.operation())
        .flatMap(
            found -> {
              if (!found && Set.of("DELETE_USAGE", "DIRECT_DELETE").contains(phase))
                return Mono.error(denied("ACCEPTED_DELETION_REQUIRED"));
              return Mono.just(found);
            });
  }

  public Mono<AttachmentUploadPolicy.Grant> check(AttachmentUploadStore.Upload u) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!u.tenant().toString().equals(c.tenantId())
                  || !u.accepted()
                  || u.maximum() == null
                  || u.policyVersion() == null)
                return Mono.error(denied("ACCEPTED_DELETION_REQUIRED"));
              Mono<?> permission =
                  RpcContextBridge.details(c).getContext().getCredentialKind()
                          == CredentialKind.CREDENTIAL_KIND_SERVICE
                      ? service()
                      : authorize(Action.ATTACHMENT_DELETE, u.project(), u.issue());
              return store
                  .hasDeletion(u.operation())
                  .filter(Boolean::booleanValue)
                  .switchIfEmpty(Mono.error(denied("ACCEPTED_DELETION_REQUIRED")))
                  .then(permission)
                  .thenReturn(
                      new AttachmentUploadPolicy.Grant(
                          false, u.maximum(), u.policyVersion(), Duration.ofMinutes(15)));
            });
  }

  public static Mono<Void> service() {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var q = RpcContextBridge.details(c).getContext();
              if (q.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !Set.of("issue-service", "tenant-service").contains(q.getIssuerService())
                  || !q.getServiceIdentity().equals("issue-service")
                  || !q.hasServicePurpose()
                  || !q.getServicePurpose().equals("STORAGE_CLEANUP")
                  || !c.scopes().containsAll(Set.of("write", "storage:recover")))
                return Mono.error(denied("BOUND_STORAGE_RECOVERY_REQUIRED"));
              return Mono.empty();
            });
  }

  private static DomainException denied(String code) {
    return new DomainException(DomainStatus.PERMISSION_DENIED, code);
  }
}
