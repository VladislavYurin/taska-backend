package ru.taska.tenant;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionRequest;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionServiceGrpc;
import ru.taska.api.issue.attachment.v1.ReactorAttachmentAdmissionServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.quota.StorageQuotaProtocol.Request;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Actor/credential не заменяются сервисными полномочиями при проверке upload admission. */
public final class StorageUploadPolicyClient implements StorageQuotaStore.Policy {
  private final ManagedDomainReads reads;
  private final SignedQuotaClient client;

  public StorageUploadPolicyClient(ManagedDomainReads reads, RpcContextSecurity security) {
    this.reads = reads;
    client = new SignedQuotaClient(security, Clock.systemUTC(), "tenant-service", "issue-service");
  }

  public Mono<StorageQuotaStore.Grant> current(
      VerifiedTenantContext c, StorageQuotaStore.Command command, Request r) {
    if (r == null && command != StorageQuotaStore.Command.READ)
      return Mono.error(
          new DomainException(DomainStatus.UNAVAILABLE, "STORAGE_TARGET_POLICY_REQUIRED"));
    return client
        .call(
            UUID.fromString(c.tenantId()),
            AttachmentAdmissionServiceGrpc.getCheckAdmissionMethod().getFullMethodName(),
            "checkAttachmentAdmission",
            new RpcContextSecurity.Policy(true, false),
            ctx -> {
              var q =
                  AttachmentAdmissionRequest.newBuilder().setContext(ctx).setPhase(command.name());
              if (r != null)
                q.setOperationId(r.operation().toString())
                    .setResourceId(r.resource().toString())
                    .setProjectId(r.project().toString())
                    .setIssueId(r.issue().toString())
                    .setBytes(r.bytes())
                    .setExpiresAt(r.expiresAt().toString());
              return q.build();
            },
            (q, auth) ->
                ReactorAttachmentAdmissionServiceGrpc.newReactorStub(reads.channel("issue-service"))
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .checkAdmission(q))
        .map(
            g ->
                new StorageQuotaStore.Grant(
                    g.getBypassLimit(),
                    Duration.ofSeconds(g.getMaximumTtlSeconds()),
                    g.getMaximumAttachmentBytes(),
                    g.getPolicyVersion()));
  }
}
