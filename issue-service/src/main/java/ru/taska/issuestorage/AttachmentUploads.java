package ru.taska.issuestorage;

import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequestBody;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequestBody;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.ProjectQuotaProtocol.State;
import ru.taska.quota.StorageQuotaProtocol.Authority;
import ru.taska.quota.StorageQuotaProtocol.Reservation;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.storage.versioned.UploadObjects;

/**
 * Сага загрузки: server intent → reserve → PUT window → pinned copy → materialize → commit →
 * activate.
 */
public final class AttachmentUploads {
  private final AttachmentUploadStore store;
  private final StorageQuotaOwner owner;
  private final Authority authority;
  private final UploadObjects objects;
  private final AttachmentUploadPolicy policy;
  private final Clock clock;

  public AttachmentUploads(
      AttachmentUploadStore store,
      StorageQuotaOwner owner,
      Authority authority,
      UploadObjects objects,
      AttachmentUploadPolicy policy,
      Clock clock) {
    this.store = store;
    this.owner = owner;
    this.authority = authority;
    this.objects = objects;
    this.policy = policy;
    this.clock = clock;
  }

  public Mono<CreateAttachmentUploadUrlResponse> initiate(CreateAttachmentUploadUrlRequestBody q) {
    return context(q.getActorUserId(), "createAttachmentUploadUrl")
        .flatMap(
            c -> {
              UUID issue = id(q.getIssueId()), project = id(q.getProjectId());
              return policy
                  .check(project, issue, null, "MUTATION_CONTEXT")
                  .then(
                      store.begin(
                          project,
                          issue,
                          q.getContentType(),
                          q.getSizeBytes(),
                          q.getFileName(),
                          q.getIdempotencyKey()))
                  .flatMap(
                      u ->
                          authority
                              .reserve(u.request())
                              .flatMap(
                                  r ->
                                      store
                                          .accepted(u, r)
                                          .then(prepareWindow(u, r))
                                          .then(policy.check(project, issue, u, "UPLOAD_WINDOW"))
                                          .then(
                                              objects.presign(
                                                  u.stagingKey(),
                                                  u.mime(),
                                                  u.bytes(),
                                                  u.putUntil()))
                                          .map(
                                              cap ->
                                                  CreateAttachmentUploadUrlResponse.newBuilder()
                                                      .setUploadUrl(cap.url())
                                                      .setObjectKey(u.stagingKey())
                                                      .setExpiresIn(
                                                          Math.max(
                                                              0,
                                                              java.time.Duration.between(
                                                                          clock.instant(),
                                                                          u.putUntil())
                                                                      .getSeconds()
                                                                  - 1))
                                                      .build())));
            });
  }

  private Mono<Void> prepareWindow(AttachmentUploadStore.Upload u, Reservation r) {
    if (r.state() != State.ACTIVE
        || r.cancelRequested()
        || !clock.instant().isBefore(r.expiresAt()))
      return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, "UPLOAD_CLOSED"));
    return owner
        .hasUploadWindow(r, u.putUntil())
        .flatMap(
            ready ->
                ready
                    ? Mono.empty()
                    : owner.prepare(r, u.mime()).then(owner.recordUploadWindow(r, u.putUntil())));
  }

  public Mono<AttachmentResponse> confirm(ConfirmAttachmentUploadRequestBody q) {
    return context(q.getActorUserId(), "confirmAttachmentUpload")
        .then(store.staging(q.getObjectKey()))
        .flatMap(
            u -> {
              if (!u.issue().toString().equals(q.getIssueId())
                  || !u.project().toString().equals(q.getProjectId())
                  || !u.mime().equals(q.getContentType())
                  || !u.fileName().equals(q.getFileName()))
                return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"));
              return policy
                  .check(u.project(), u.issue(), u, "CONFIRM")
                  .then(store.requestConfirm(u))
                  .then(finish(u));
            });
  }

  private Mono<AttachmentResponse> finish(AttachmentUploadStore.Upload u) {
    return authority
        .get(u.operation())
        .flatMap(
            r -> {
              if (!u.request().equals(r.request())
                  || !u.tenant().equals(r.tenant())
                  || !u.actor().equals(r.actor()))
                return Mono.error(
                    new DomainException(DomainStatus.DATA_LOSS, "STORAGE_ADMISSION_MISMATCH"));
              if (r.state() == State.COMMITTED) return activate(u, r);
              if (r.state() != State.ACTIVE
                  || r.cancelRequested()
                  || !clock.instant().isBefore(r.expiresAt()))
                return Mono.error(
                    new DomainException(DomainStatus.FAILED_PRECONDITION, "UPLOAD_CLOSED"));
              return store
                  .operation(u.operation())
                  .flatMap(
                      current ->
                          (current.stagingVersion() != null
                                  ? Mono.just(current.stagingVersion())
                                  : policy
                                      .check(u.project(), u.issue(), current, "CONFIRM")
                                      .then(
                                          objects.stagingVersion(
                                              u.stagingKey(), u.mime(), u.bytes()))
                                      .flatMap(v -> store.pin(u, true, v)))
                              .flatMap(
                                  stage ->
                                      copyVersion(current, stage)
                                          .flatMap(
                                              finalVersion ->
                                                  owner.materialize(r, stage, finalVersion))))
                  .then(authority.commit(r))
                  .flatMap(committed -> activate(u, committed));
            })
        .onErrorResume(
            original ->
                authority
                    .get(u.operation())
                    .flatMap(
                        current -> {
                          if (!u.request().equals(current.request())
                              || !u.tenant().equals(current.tenant())
                              || !u.actor().equals(current.actor()))
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.DATA_LOSS, "STORAGE_ADMISSION_MISMATCH"));
                          return current.state() == State.COMMITTED
                              ? activate(u, current)
                              : Mono.error(original);
                        })
                    .onErrorResume(unavailable -> Mono.error(original)));
  }

  private Mono<String> copyVersion(AttachmentUploadStore.Upload u, String stage) {
    if (u.finalVersion() != null) return Mono.just(u.finalVersion());
    return policy
        .check(u.project(), u.issue(), u, "CONFIRM")
        .then(store.claimCopy(u))
        .flatMap(
            winner -> {
              Mono<String> version =
                  winner
                      ? objects.copyPinned(
                          u.stagingKey(), stage, u.finalKey(), u.operation().toString(), u.mime())
                      : objects
                          .existingFinal(u.finalKey(), stage, u.operation().toString())
                          .repeatWhenEmpty(
                              20, flux -> flux.delayElements(java.time.Duration.ofMillis(100)))
                          .timeout(java.time.Duration.ofSeconds(3));
              return version
                  .switchIfEmpty(
                      Mono.error(
                          new DomainException(DomainStatus.UNAVAILABLE, "COPY_OUTCOME_UNKNOWN")))
                  .flatMap(v -> store.pin(u, false, v));
            });
  }

  private Mono<AttachmentUploadStore.Upload> recoverCopy(AttachmentUploadStore.Upload u) {
    if (!u.copyStarted() || u.finalVersion() != null) return Mono.just(u);
    return objects
        .existingFinal(u.finalKey(), u.stagingVersion(), u.operation().toString())
        .switchIfEmpty(
            Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "COPY_OUTCOME_UNKNOWN")))
        .flatMap(v -> store.pin(u, false, v))
        .then(store.operation(u.operation()));
  }

  public record Recovery(int processed, int failed, int dataLoss, String next) {}

  public Mono<Recovery> reconcile(UUID after, int limit) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claims = ru.taska.security.tenant.RpcContextBridge.details(c).getContext();
              if (claims.getCredentialKind()
                      != ru.taska.api.b2b.v1.CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !claims.getIssuerService().equals("issue-service")
                  || !claims.getServiceIdentity().equals("issue-service")
                  || !claims.getServicePurpose().equals("STORAGE_CLEANUP")
                  || !c.action().equals("reconcileAttachmentUploads")
                  || !c.scopes().contains("storage:recover")
                  || !c.scopes().contains("write"))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "BOUND_STORAGE_RECOVERY_REQUIRED"));
              return store
                  .page(after, limit)
                  .flatMap(
                      rows -> {
                        var failed = new java.util.concurrent.atomic.AtomicInteger();
                        var dataLoss = new java.util.concurrent.atomic.AtomicInteger();
                        return reactor.core.publisher.Flux.fromIterable(rows)
                            .concatMap(
                                u ->
                                    authority
                                        .get(u.operation())
                                        .flatMap(
                                            r ->
                                                store
                                                    .accepted(u, r)
                                                    .flatMap(
                                                        current -> {
                                                          if (r.state() == State.COMMITTED)
                                                            return finish(current).then();
                                                          if (r.state() == State.ACTIVE
                                                              && (r.cancelRequested()
                                                                  || !clock
                                                                      .instant()
                                                                      .isBefore(r.expiresAt())))
                                                            return recoverCopy(current)
                                                                .then(authority.release(r))
                                                                .then();
                                                          if (r.state() == State.ACTIVE
                                                              && current.confirmRequested())
                                                            return finish(current).then();
                                                          return Mono.empty();
                                                        }))
                                        .onErrorResume(
                                            e -> {
                                              failed.incrementAndGet();
                                              if (e instanceof DomainException domain
                                                      && domain.getStatus()
                                                          == DomainStatus.DATA_LOSS
                                                  || io.grpc.Status.fromThrowable(e).getCode()
                                                      == io.grpc.Status.Code.DATA_LOSS)
                                                dataLoss.incrementAndGet();
                                              return Mono.empty();
                                            }))
                            .then(
                                Mono.fromSupplier(
                                    () ->
                                        new Recovery(
                                            rows.size(),
                                            failed.get(),
                                            dataLoss.get(),
                                            rows.size() == limit
                                                ? rows.getLast().id().toString()
                                                : "")));
                      });
            });
  }

  private Mono<AttachmentResponse> activate(AttachmentUploadStore.Upload u, Reservation r) {
    return store
        .result(u)
        .onErrorResume(
            DomainException.class,
            error ->
                error.getStatus() == DomainStatus.UNAVAILABLE
                        && "ATTACHMENT_ACTIVATION_PENDING".equals(error.getMessage())
                    ? owner.activate(r).then(store.result(u))
                    : Mono.error(error));
  }

  private Mono<VerifiedTenantContext> context(String actor, String action) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> new DomainException(DomainStatus.UNAUTHENTICATED, "VERIFIED_CONTEXT_REQUIRED"))
        .flatMap(
            c -> {
              if (!actor.equals(c.actorId())
                  || !c.action().equals(action)
                  || !c.scopes().contains("write"))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "UPLOAD_CONTEXT_MISMATCH"));
              return Mono.just(c);
            });
  }

  private static UUID id(String text) {
    try {
      var id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_UPLOAD_ID");
    }
  }
}
