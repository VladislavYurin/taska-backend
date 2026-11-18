package ru.taska.issuestorage;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityFacts;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.ProjectAccessClient;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Текущие primary facts, реальный project ACL и неизменяемые условия accepted upload. */
public final class PrimaryAttachmentPolicy implements AttachmentUploadPolicy {
  private final TenantDecisionFacts facts;
  private final ManagedDomainReads reads;
  private final ProjectAccessClient projects;
  private final TenantIssueStore issues;

  public PrimaryAttachmentPolicy(
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      RpcContextSecurity security,
      TenantIssueStore issues) {
    this.facts = facts;
    this.reads = reads;
    this.projects = new ProjectAccessClient(reads, security, "issue-service");
    this.issues = issues;
  }

  public Mono<Grant> check(
      UUID project, UUID issue, AttachmentUploadStore.Upload upload, String phase) {
    if (upload != null
        && phase.equals("CANCEL")
        && upload.copyStarted()
        && upload.finalVersion() == null)
      return Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "COPY_OUTCOME_UNKNOWN"));
    if (phase.equals("READ"))
      return facts
          .currentAdministrative("read")
          .flatMap(
              frame ->
                  facts
                      .decision(frame, Action.TENANT_READ, (UUID) null, Set.of())
                      .then(terms(frame.entitlement().getPlanVersionId(), null, false)));
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (upload != null
                  && (!upload.tenant().toString().equals(c.tenantId())
                      || !upload.project().equals(project)
                      || !upload.issue().equals(issue)))
                return Mono.error(denied("UPLOAD_SCOPE_MISMATCH"));
              var claim = RpcContextBridge.details(c).getContext();
              if (claim.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  && claim.getAdministrativeLevel()
                      != ru.taska.api.b2b.v1.AdminGrant.LevelValue.LEVEL_MODIFY)
                return Mono.error(denied("ADMIN_MODIFY_REQUIRED"));
              boolean principal =
                  Set.of("LOOKUP", "MUTATION_CONTEXT", "REPLAY", "INSPECT", "CANCEL")
                      .contains(phase);
              boolean finish =
                  !phase.equals("RESERVE")
                      && !phase.equals("NEW")
                      && !phase.equals("UPLOAD_WINDOW")
                      && !phase.equals("PREPARE");
              if (RpcContextBridge.details(c).getContext().getCredentialKind()
                  == CredentialKind.CREDENTIAL_KIND_SERVICE) return service(c, upload, phase);
              if (upload != null && !upload.actor().toString().equals(c.actorId()))
                return Mono.error(denied("ORIGINAL_ACTOR_REQUIRED"));
              return facts
                  .currentAdministrative("write")
                  .flatMap(
                      frame ->
                          issues
                              .execute(s -> s.findActive(issue))
                              .switchIfEmpty(
                                  Mono.error(
                                      new DomainException(
                                          DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND")))
                              .flatMap(
                                  row -> {
                                    if (!row.identity().projectId().equals(project))
                                      return Mono.error(
                                          new DomainException(
                                              DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND"));
                                    return projects
                                        .check(project)
                                        .flatMap(
                                            p -> {
                                              if (!p.hasProjectArchived())
                                                return Mono.error(
                                                    new DomainException(
                                                        DomainStatus.UNAVAILABLE,
                                                        "ARCHIVE_AUTHORITY_UNAVAILABLE"));
                                              var role =
                                                  switch (p.getRole()) {
                                                    case PROJECT_ROLE_ADMIN ->
                                                        CapabilityFacts.ProjectRole.ADMIN;
                                                    case PROJECT_ROLE_MEMBER ->
                                                        CapabilityFacts.ProjectRole.MEMBER;
                                                    case PROJECT_ROLE_VIEWER ->
                                                        CapabilityFacts.ProjectRole.VIEWER;
                                                    default -> CapabilityFacts.ProjectRole.NONE;
                                                  };
                                              var target =
                                                  new CapabilityFacts.Target(
                                                      UUID.fromString(c.tenantId()),
                                                      project,
                                                      issue,
                                                      upload == null ? null : upload.operation());
                                              var integrity =
                                                  p.getProjectArchived() && !frame.webAdmin()
                                                      ? Set.of(ErrorCode.ERROR_CODE_INVALID_STATE)
                                                      : Set.<ErrorCode>of();
                                              var decision =
                                                  principal
                                                      ? facts.attachmentPrincipal(
                                                          frame, target, role, integrity)
                                                      : finish
                                                              && upload != null
                                                              && upload.accepted()
                                                          ? facts.acceptedAttachment(
                                                              frame, target, role, integrity)
                                                          : facts.decision(
                                                              frame,
                                                              Action.ATTACHMENT_UPLOAD,
                                                              target,
                                                              role,
                                                              integrity);
                                              return decision.flatMap(
                                                  d -> {
                                                    var permit =
                                                        new TenantIssueAccess.Permit(
                                                            target.tenant(),
                                                            UUID.fromString(c.actorId()),
                                                            c.action(),
                                                            project,
                                                            frame.until(),
                                                            d.businessBypass(),
                                                            d.auditRequired(),
                                                            d.bypassedRestrictions().stream()
                                                                .map(Enum::name)
                                                                .collect(
                                                                    java.util.stream.Collectors
                                                                        .toSet()));
                                                    return issues
                                                        .execute(s -> s.audit(permit, issue))
                                                        .then(
                                                            terms(
                                                                frame
                                                                    .entitlement()
                                                                    .getPlanVersionId(),
                                                                upload,
                                                                d.businessBypass()));
                                                  });
                                            });
                                  }));
            });
  }

  private Mono<Grant> service(
      VerifiedTenantContext c, AttachmentUploadStore.Upload u, String phase) {
    var claim = RpcContextBridge.details(c).getContext();
    if (u == null
        || !Set.of("issue-service", "tenant-service").contains(claim.getIssuerService())
        || !claim.hasServicePurpose()
        || !claim.getServicePurpose().equals("STORAGE_CLEANUP")
        || !c.scopes().contains("storage:recover")
        || !c.scopes().contains("write")
        || Set.of("NEW", "RESERVE", "PREPARE", "UPLOAD_WINDOW").contains(phase))
      return Mono.error(denied("BOUND_STORAGE_RECOVERY_REQUIRED"));
    // Транспорт проверил текущую service generation/purpose. Полномочия ограничены durable intent.
    if (!u.accepted() || u.maximum() == null || u.policyVersion() == null) {
      if (!Set.of("LOOKUP", "CANCEL", "RELEASE").contains(phase))
        return Mono.error(denied("ACCEPTED_STORAGE_INTENT_REQUIRED"));
      return reads
          .entitlement(u.tenant(), 0)
          .flatMap(e -> terms(e.getPlanVersionId(), null, false));
    }
    return Mono.just(new Grant(false, u.maximum(), u.policyVersion(), Duration.ofMinutes(15)));
  }

  private Mono<Grant> terms(String plan, AttachmentUploadStore.Upload upload, boolean bypass) {
    if (upload != null && upload.accepted())
      return Mono.just(
          new Grant(bypass, upload.maximum(), upload.policyVersion(), Duration.ofMinutes(15)));
    return reads
        .referencedPlan(plan)
        .map(
            p -> {
              if (!p.hasMaxAttachmentBytes())
                throw new DomainException(DomainStatus.UNAVAILABLE, "ATTACHMENT_CAP_UNAVAILABLE");
              try {
                return new Grant(
                    bypass,
                    Long.parseLong(p.getMaxAttachmentBytes()),
                    Long.parseLong(p.getVersion()),
                    Duration.ofMinutes(15));
              } catch (RuntimeException e) {
                throw new DomainException(DomainStatus.UNAVAILABLE, "INVALID_ATTACHMENT_CAP");
              }
            });
  }

  private static DomainException denied(String code) {
    return new DomainException(DomainStatus.PERMISSION_DENIED, code);
  }
}
