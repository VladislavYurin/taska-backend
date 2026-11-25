package ru.taska.quota;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ProjectDeletionProof;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ProjectDeletionServiceGrpc;
import ru.taska.api.b2b.v1.ReactorProjectDeletionServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Свежая SERVICE authority и primary intent обязательны перед очисткой ресурса. */
public final class ProjectDeletionAuthority {
  private final SignedQuotaClient client;
  private final ManagedDomainReads reads;

  public ProjectDeletionAuthority(
      ManagedDomainReads reads, RpcContextSecurity security, String service) {
    this.reads = reads;
    this.client = new SignedQuotaClient(security, Clock.systemUTC(), service, "project-service");
  }

  public static Mono<Void> service() {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var q = RpcContextBridge.details(c).getContext();
              if (q.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !q.getServicePurpose().equals("PROJECT_CLEANUP")
                  || !q.getServiceIdentity().equals("project-service")
                  || !Set.of(
                          "project-service", "issue-service", "workflow-service", "tenant-service")
                      .contains(q.getIssuerService())
                  || !c.scopes().containsAll(Set.of("write", "project:delete")))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "PROJECT_DELETE_PROOF_REQUIRED"));
              return Mono.empty();
            });
  }

  public Mono<ProjectDeletionProof> accepted(UUID tenant, UUID project, UUID operation) {
    return service()
        .then(
            client.call(
                tenant,
                ProjectDeletionServiceGrpc.getInspectDeletionMethod().getFullMethodName(),
                "inspectProjectDeletion",
                c ->
                    ProjectDeletionRequest.newBuilder()
                        .setContext(c)
                        .setProjectId(project.toString())
                        .setOperationId(operation.toString())
                        .build(),
                (q, auth) ->
                    ReactorProjectDeletionServiceGrpc.newReactorStub(
                            reads.channel("project-service"))
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .withInterceptors(auth)
                        .inspectDeletion(q)))
        .map(
            p -> {
              if (!p.getAccepted()
                  || !p.getOperation().getTenantId().equals(tenant.toString())
                  || !p.getProjectId().equals(project.toString())
                  || !p.getOperation().getId().equals(operation.toString())
                  || !p.getOperation().getResourceId().equals(project.toString())
                  || !p.getOperation().getType().equals("PROJECT_DELETE"))
                throw new DomainException(
                    DomainStatus.FAILED_PRECONDITION, "PROJECT_DELETE_PROOF_MISMATCH");
              try {
                if (!UUID.fromString(p.getInitiatorId()).toString().equals(p.getInitiatorId()))
                  throw new IllegalArgumentException();
              } catch (IllegalArgumentException error) {
                throw new DomainException(
                    DomainStatus.DATA_LOSS, "PROJECT_DELETE_INITIATOR_REQUIRED");
              }
              return p;
            });
  }
}
