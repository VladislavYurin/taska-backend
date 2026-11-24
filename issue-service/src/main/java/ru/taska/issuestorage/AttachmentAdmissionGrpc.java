package ru.taska.issuestorage;

import java.util.Set;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionGrant;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionRequest;
import ru.taska.api.issue.attachment.v1.ReactorAttachmentAdmissionServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Quota root получает решение только от primary resource owner с сохранением реального actor. */
@GrpcService
public final class AttachmentAdmissionGrpc
    extends ReactorAttachmentAdmissionServiceGrpc.AttachmentAdmissionServiceImplBase {
  private final AttachmentUploadStore store;
  private final AttachmentUploadPolicy policy;
  private final AttachmentUploads uploads;

  public AttachmentAdmissionGrpc(
      AttachmentUploadStore store, AttachmentUploadPolicy policy, AttachmentUploads uploads) {
    this.store = store;
    this.policy = policy;
    this.uploads = uploads;
  }

  @Override
  public Mono<ru.taska.api.issue.attachment.v1.ReconcileAttachmentUploadsResponse> reconcileUploads(
      Mono<ru.taska.api.issue.attachment.v1.ReconcileAttachmentUploadsRequest> q) {
    return RpcContextBridge.inbound(
            q.flatMap(
                    r ->
                        uploads.reconcile(
                            r.getAfterId().isEmpty() ? null : id(r.getAfterId()), r.getLimit()))
                .map(
                    r ->
                        ru.taska.api.issue.attachment.v1.ReconcileAttachmentUploadsResponse
                            .newBuilder()
                            .setProcessed(r.processed())
                            .setFailed(r.failed())
                            .setDataLoss(r.dataLoss())
                            .setNextCursor(r.next())
                            .build()))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  @Override
  public Mono<AttachmentAdmissionGrant> checkAdmission(Mono<AttachmentAdmissionRequest> input) {
    return RpcContextBridge.inbound(
            input.flatMap(
                q ->
                    VerifiedTenantContext.current()
                        .flatMap(
                            c -> {
                              var claims = RpcContextBridge.details(c).getContext();
                              if (!claims.getIssuerService().equals("tenant-service")
                                  || !Set.of(
                                          "MUTATION_CONTEXT",
                                          "RESERVE",
                                          "REPLAY",
                                          "LOOKUP",
                                          "COMMIT",
                                          "RELEASE",
                                          "RECONCILE",
                                          "DELETE_USAGE",
                                          "READ")
                                      .contains(q.getPhase()))
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.PERMISSION_DENIED, "STORAGE_ROOT_REQUIRED"));
                              if (q.getPhase().equals("READ") && q.getOperationId().isEmpty())
                                return policy
                                    .check(null, null, null, "READ")
                                    .map(
                                        g ->
                                            AttachmentAdmissionGrant.newBuilder()
                                                .setBypassLimit(g.bypassLimit())
                                                .setMaximumAttachmentBytes(
                                                    g.maximumAttachmentBytes())
                                                .setPolicyVersion(g.policyVersion())
                                                .setMaximumTtlSeconds(g.ttl().toSeconds())
                                                .build());
                              return store
                                  .operation(id(q.getOperationId()))
                                  .flatMap(
                                      u -> {
                                        if (!u.id().toString().equals(q.getResourceId())
                                            || !u.project().toString().equals(q.getProjectId())
                                            || !u.issue().toString().equals(q.getIssueId())
                                            || u.bytes() != q.getBytes()
                                            || !u.expiresAt()
                                                .equals(java.time.Instant.parse(q.getExpiresAt())))
                                          return Mono.error(
                                              new DomainException(
                                                  DomainStatus.PERMISSION_DENIED,
                                                  "UPLOAD_INTENT_MISMATCH"));
                                        return policy
                                            .check(u.project(), u.issue(), u, q.getPhase())
                                            .map(
                                                g ->
                                                    AttachmentAdmissionGrant.newBuilder()
                                                        .setBypassLimit(g.bypassLimit())
                                                        .setMaximumAttachmentBytes(
                                                            g.maximumAttachmentBytes())
                                                        .setPolicyVersion(g.policyVersion())
                                                        .setMaximumTtlSeconds(g.ttl().toSeconds())
                                                        .build());
                                      });
                            })))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  private static UUID id(String value) {
    try {
      var result = UUID.fromString(value);
      if (!result.toString().equals(value)) throw new IllegalArgumentException();
      return result;
    } catch (IllegalArgumentException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_UPLOAD_CURSOR_OR_ID");
    }
  }
}
