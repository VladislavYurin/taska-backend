package ru.taska.issuestorage;

import io.r2dbc.spi.ConnectionFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ProjectCleanupResult;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ReactorProjectResourceCleanupServiceGrpc;
import ru.taska.api.issue.attachment.v1.AttachmentAdmissionServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.ProjectDeletionAuthority;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Очистка ограничена primary-подтверждённым проектом и его собственным manifest. */
@GrpcService
public final class IssueProjectCleanup
    extends ReactorProjectResourceCleanupServiceGrpc.ProjectResourceCleanupServiceImplBase {
  private final TenantTransactionExecutor tx;
  private final ProjectDeletionAuthority primary;
  private final AttachmentUploads uploads;
  private final AttachmentLifecycleStore attachments;
  private final AttachmentDeletions deletions;
  private final LifetimeTaskQuota quota;
  private final ManagedServiceContexts contexts;

  @org.springframework.beans.factory.annotation.Autowired
  public IssueProjectCleanup(
      ConnectionFactory factory,
      ManagedDomainReads reads,
      RpcContextSecurity security,
      AttachmentUploads uploads,
      AttachmentLifecycleStore attachments,
      AttachmentDeletions deletions,
      LifetimeTaskQuota quota,
      ManagedServiceContexts contexts) {
    this(
        new TenantTransactionExecutor(factory),
        new ProjectDeletionAuthority(reads, security, "issue-service"),
        uploads,
        attachments,
        deletions,
        quota,
        contexts);
  }

  IssueProjectCleanup(
      TenantTransactionExecutor tx,
      ProjectDeletionAuthority primary,
      AttachmentUploads uploads,
      AttachmentLifecycleStore attachments,
      AttachmentDeletions deletions,
      LifetimeTaskQuota quota,
      ManagedServiceContexts contexts) {
    this.tx = tx;
    this.primary = primary;
    this.uploads = uploads;
    this.attachments = attachments;
    this.deletions = deletions;
    this.quota = quota;
    this.contexts = contexts;
  }

  @Override
  public Mono<ProjectCleanupResult> cleanupProject(Mono<ProjectDeletionRequest> q) {
    return RpcContextBridge.inbound(
            q.flatMap(
                r ->
                    cleanup(
                        UUID.fromString(r.getProjectId()), UUID.fromString(r.getOperationId()))))
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }

  public Mono<ProjectCleanupResult> cleanup(UUID project, UUID operation) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return primary
                  .accepted(tenant, project, operation)
                  .flatMap(
                      proof ->
                          fence(
                              tenant, project, operation, UUID.fromString(proof.getInitiatorId())))
                  .flatMap(
                      done -> {
                        if (done) return Mono.just(result(tenant, project, operation, true));
                        return page(tenant, project)
                            .flatMap(
                                ids ->
                                    Flux.fromIterable(ids)
                                        .concatMap(
                                            id ->
                                                storage(
                                                        tenant,
                                                        true,
                                                        () -> uploads.cleanupProjectUpload(id))
                                                    .then(
                                                        uploadDone(tenant, project, operation, id)))
                                        .then(acceptAttachments(tenant, project))
                                        .then(attachmentIds(tenant, project))
                                        .flatMap(
                                            found ->
                                                Flux.fromIterable(found)
                                                    .concatMap(
                                                        id ->
                                                            storage(
                                                                tenant,
                                                                false,
                                                                () -> deletions.process(id)))
                                                    .then(ready(tenant, project)))
                                        .flatMap(
                                            ready ->
                                                ready
                                                    ? finish(tenant, project, operation)
                                                        .map(
                                                            batchDone ->
                                                                result(
                                                                    tenant, project, operation,
                                                                    batchDone))
                                                    : Mono.just(
                                                        result(
                                                            tenant, project, operation, false))));
                      });
            });
  }

  private Mono<Boolean> fence(UUID t, UUID p, UUID op, UUID initiator) {
    return tx.execute(
        db ->
            lock(db, t, p)
                .then(
                    db.sql(
                            "INSERT INTO"
                                + " taska.project_cleanup_receipts(tenant_id,project_id,operation_id,initiator_id)"
                                + " VALUES(:t,:p,:op,:initiator) ON CONFLICT DO NOTHING")
                        .bind("t", t)
                        .bind("p", p)
                        .bind("op", op)
                        .bind("initiator", initiator)
                        .then())
                .then(
                    db.sql(
                            "SELECT operation_id,completed,initiator_id FROM"
                                + " taska.project_cleanup_receipts WHERE tenant_id=:t AND"
                                + " project_id=:p")
                        .bind("t", t)
                        .bind("p", p)
                        .fetch()
                        .one()
                        .map(
                            r -> {
                              if (!op.equals(r.get("operation_id"))
                                  || !initiator.equals(r.get("initiator_id"))) throw denied();
                              return (Boolean) r.get("completed");
                            })));
  }

  public Mono<LifetimeTaskQuota.Grant> retirement(VerifiedTenantContext c, UUID project) {
    UUID t = UUID.fromString(c.tenantId());
    return ProjectDeletionAuthority.service()
        .then(
            tx.execute(
                db ->
                    db.sql(
                            "SELECT EXISTS(SELECT 1 FROM taska.project_cleanup_receipts WHERE"
                                + " tenant_id=:t AND project_id=:p) AS found")
                        .bind("t", t)
                        .bind("p", project)
                        .map((r, m) -> r.get("found", Boolean.class))
                        .one()))
        .filter(Boolean::booleanValue)
        .switchIfEmpty(Mono.error(denied()))
        .map(v -> new LifetimeTaskQuota.Grant(false));
  }

  private Mono<List<UUID>> page(UUID t, UUID p) {
    return tx.execute(
        db ->
            db.sql(
                    "SELECT u.operation_id FROM taska.attachment_uploads u WHERE u.tenant_id=:t AND"
                        + " u.project_id=:p AND NOT u.project_cleanup_complete ORDER BY u.id LIMIT"
                        + " 10")
                .bind("t", t)
                .bind("p", p)
                .map((r, m) -> r.get("operation_id", UUID.class))
                .all()
                .collectList());
  }

  private Mono<Void> uploadDone(UUID t, UUID p, UUID operation, UUID upload) {
    return tx.execute(
        db ->
            db.sql(
                    "INSERT INTO"
                        + " taska.project_cleanup_uploads(tenant_id,project_id,operation_id,upload_operation_id)"
                        + " VALUES(:t,:p,:op,:upload) ON CONFLICT DO NOTHING")
                .bind("t", t)
                .bind("p", p)
                .bind("op", operation)
                .bind("upload", upload)
                .then()
                .then(
                    db.sql(
                            "UPDATE taska.attachment_uploads SET project_cleanup_complete=true"
                                + " WHERE tenant_id=:t AND project_id=:p AND operation_id=:upload")
                        .bind("t", t)
                        .bind("p", p)
                        .bind("upload", upload)
                        .then()));
  }

  Mono<Void> acceptAttachments(UUID t, UUID p) {
    return tx.execute(
        db ->
            lock(db, t, p)
                .then(
                    db.sql(
                            "INSERT INTO"
                                + " taska.attachment_deletion_jobs(tenant_id,attachment_id,operation_id,initiator_id)"
                                + " SELECT m.tenant_id,m.id,m.operation_id,c.initiator_id FROM"
                                + " taska.attachment_object_manifest m JOIN"
                                + " taska.project_cleanup_receipts c ON c.tenant_id=m.tenant_id AND"
                                + " c.project_id=m.project_id WHERE m.tenant_id=:t AND"
                                + " m.project_id=:p AND m.state IN"
                                + " ('CONFIRMED','DELETE_PENDING','DELETED') AND NOT EXISTS (SELECT"
                                + " 1 FROM taska.attachment_deletion_jobs j WHERE"
                                + " j.tenant_id=m.tenant_id AND j.attachment_id=m.id) ORDER BY m.id"
                                + " LIMIT 10 ON CONFLICT DO NOTHING")
                        .bind("t", t)
                        .bind("p", p)
                        .then())
                .then(
                    db.sql(
                            "WITH changed AS (UPDATE taska.attachment_object_manifest m SET"
                                + " state='DELETE_PENDING' WHERE m.tenant_id=:t AND m.project_id=:p"
                                + " AND m.state='CONFIRMED' AND EXISTS (SELECT 1 FROM"
                                + " taska.attachment_deletion_jobs j WHERE j.tenant_id=m.tenant_id"
                                + " AND j.attachment_id=m.id AND j.operation_id=m.operation_id)"
                                + " RETURNING m.tenant_id,m.operation_id), events AS (INSERT INTO"
                                + " taska.storage_quota_outbox"
                                + " (tenant_id,operation_id,event_type,actor_id,issuer) SELECT"
                                + " d.tenant_id,d.operation_id,'AttachmentDeletionRequested',"
                                + " c.initiator_id,'issue-service' FROM changed d JOIN"
                                + " taska.project_cleanup_receipts c ON c.tenant_id=d.tenant_id AND"
                                + " c.project_id=:p ON CONFLICT DO NOTHING RETURNING tenant_id)"
                                + " INSERT INTO taska.storage_manifest_versions(tenant_id) SELECT"
                                + " DISTINCT tenant_id FROM changed ON CONFLICT(tenant_id) DO"
                                + " UPDATE SET version=taska.storage_manifest_versions.version+1")
                        .bind("t", t)
                        .bind("p", p)
                        .then()));
  }

  private Mono<List<UUID>> attachmentIds(UUID t, UUID p) {
    return tx.execute(
        db ->
            db.sql(
                    "SELECT m.id FROM taska.attachment_object_manifest m WHERE m.tenant_id=:t AND"
                        + " m.project_id=:p AND (m.state IN ('CONFIRMED','DELETE_PENDING') OR"
                        + " (m.state='DELETED' AND EXISTS(SELECT 1 FROM"
                        + " taska.attachment_deletion_jobs j WHERE j.tenant_id=m.tenant_id AND"
                        + " j.attachment_id=m.id AND j.state<>'COMPLETED'))) ORDER BY m.id LIMIT"
                        + " 10")
                .bind("t", t)
                .bind("p", p)
                .map((r, m) -> r.get("id", UUID.class))
                .all()
                .collectList());
  }

  private Mono<Boolean> ready(UUID t, UUID p) {
    return tx.execute(
        db ->
            db.sql(
                    "SELECT NOT EXISTS(SELECT 1 FROM taska.attachment_uploads u WHERE"
                        + " u.tenant_id=:t AND u.project_id=:p AND NOT EXISTS(SELECT 1 FROM"
                        + " taska.project_cleanup_uploads d WHERE d.tenant_id=u.tenant_id AND"
                        + " d.project_id=u.project_id AND d.upload_operation_id=u.operation_id))"
                        + " AND NOT EXISTS(SELECT 1 FROM taska.attachment_object_manifest m WHERE"
                        + " tenant_id=:t AND project_id=:p AND (state NOT IN ('DELETED','EXPIRED')"
                        + " OR (state='DELETED' AND NOT EXISTS(SELECT 1 FROM"
                        + " taska.attachment_deletion_jobs j WHERE j.tenant_id=m.tenant_id AND"
                        + " j.attachment_id=m.id AND j.state='COMPLETED')))) AS ready")
                .bind("t", t)
                .bind("p", p)
                .map((r, m) -> r.get("ready", Boolean.class))
                .one());
  }

  private Mono<Boolean> finish(UUID t, UUID p, UUID op) {
    return tx.execute(
        db ->
            lock(db, t, p)
                .then(
                    db.sql(
                            "SELECT id FROM taska.issues WHERE tenant_id=:t AND project_id=:p ORDER"
                                + " BY id LIMIT 100")
                        .bind("t", t)
                        .bind("p", p)
                        .map((r, m) -> r.get("id", UUID.class))
                        .all()
                        .collectList())
                .flatMap(
                    ids -> {
                      Mono<Void> batch = Mono.empty();
                      if (!ids.isEmpty()) {
                        for (String table :
                            List.of("issue_labels", "issue_watchers", "issue_attachments"))
                          batch =
                              batch.then(
                                  db.sql(
                                          "DELETE FROM taska."
                                              + table
                                              + " WHERE tenant_id=:t AND project_id=:p AND issue_id"
                                              + " IN (:ids)")
                                      .bind("t", t)
                                      .bind("p", p)
                                      .bind("ids", ids)
                                      .then());
                        batch =
                            batch.then(
                                db.sql(
                                        "DELETE FROM taska.issues WHERE tenant_id=:t AND"
                                            + " project_id=:p AND id IN (:ids)")
                                    .bind("t", t)
                                    .bind("p", p)
                                    .bind("ids", ids)
                                    .then());
                      }
                      return batch.then(
                          db.sql(
                                  "SELECT EXISTS(SELECT 1 FROM taska.issues WHERE tenant_id=:t AND"
                                      + " project_id=:p) AS remaining")
                              .bind("t", t)
                              .bind("p", p)
                              .map((r, m) -> r.get("remaining", Boolean.class))
                              .one());
                    })
                .flatMap(
                    remaining -> {
                      if (remaining) return Mono.just(true);
                      return db.sql(
                              "DELETE FROM taska.project_labels WHERE tenant_id=:t AND"
                                  + " project_id=:p AND id IN (SELECT id FROM taska.project_labels"
                                  + " WHERE tenant_id=:t AND project_id=:p ORDER BY id LIMIT 100)")
                          .bind("t", t)
                          .bind("p", p)
                          .then()
                          .then(
                              db.sql(
                                      "SELECT EXISTS(SELECT 1 FROM taska.project_labels WHERE"
                                          + " tenant_id=:t AND project_id=:p) AS remaining")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .map((r, m) -> r.get("remaining", Boolean.class))
                                  .one());
                    })
                .flatMap(
                    remaining -> {
                      if (remaining) return Mono.just(false);
                      return quota
                          .retire(db, p)
                          .then(
                              db.sql(
                                      "UPDATE taska.project_cleanup_receipts SET completed=true"
                                          + " WHERE tenant_id=:t AND project_id=:p AND"
                                          + " operation_id=:op")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .bind("op", op)
                                  .then())
                          .thenReturn(true);
                    }));
  }

  private <T> Mono<T> storage(UUID t, boolean upload, java.util.function.Supplier<Mono<T>> work) {
    return contexts
        .authorize(
            "STORAGE_CLEANUP",
            "issue-service",
            upload
                ? AttachmentAdmissionServiceGrpc.getReconcileUploadsMethod().getFullMethodName()
                : AttachmentAdmissionServiceGrpc.getReconcileDeletionsMethod().getFullMethodName(),
            upload ? "reconcileAttachmentUploads" : "reconcileAttachmentDeletions",
            t,
            Set.of("write", "storage:recover"),
            new RpcContextSecurity.Policy(true, true))
        .flatMap(c -> RpcContextBridge.install(Mono.defer(work), c));
  }

  private static Mono<Void> lock(DatabaseClient db, UUID t, UUID p) {
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k,0) % 256)")
        .bind("k", t + "/project-cleanup/" + p)
        .then();
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "PROJECT_DELETE_PROOF_REQUIRED");
  }

  private static ProjectCleanupResult result(UUID t, UUID p, UUID op, boolean complete) {
    return ProjectCleanupResult.newBuilder()
        .setTenantId(t.toString())
        .setProjectId(p.toString())
        .setOperationId(op.toString())
        .setComplete(complete)
        .build();
  }
}
