package ru.taska.workflowstorage;

import java.util.UUID;
import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ProjectCleanupResult;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ReactorProjectResourceCleanupServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.ProjectDeletionAuthority;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Проектный граф удаляется отдельно от общих tenant templates и сохранённых receipts. */
@GrpcService
public final class WorkflowProjectCleanup
    extends ReactorProjectResourceCleanupServiceGrpc.ProjectResourceCleanupServiceImplBase {
  private final TenantWorkflowStore store;
  private final ProjectDeletionAuthority primary;

  @org.springframework.beans.factory.annotation.Autowired
  public WorkflowProjectCleanup(
      TenantWorkflowStore store, ManagedDomainReads reads, RpcContextSecurity security) {
    this(store, new ProjectDeletionAuthority(reads, security, "workflow-service"));
  }

  WorkflowProjectCleanup(TenantWorkflowStore store, ProjectDeletionAuthority primary) {
    this.store = store;
    this.primary = primary;
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

  public Mono<ProjectCleanupResult> cleanup(UUID p, UUID op) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              UUID t = UUID.fromString(c.tenantId());
              return primary
                  .accepted(t, p, op)
                  .then(
                      store.execute(
                          s -> {
                            var db = s.database();
                            return db.sql(
                                    "SELECT pg_advisory_xact_lock(hashtextextended(:k,0) % 256)")
                                .bind("k", t + "/project-cleanup/" + p)
                                .then()
                                .then(
                                    db.sql(
                                            "INSERT INTO"
                                                + " taska.project_cleanup_receipts(tenant_id,project_id,operation_id)"
                                                + " VALUES(:t,:p,:op) ON CONFLICT DO NOTHING")
                                        .bind("t", t)
                                        .bind("p", p)
                                        .bind("op", op)
                                        .then())
                                .then(
                                    db.sql(
                                            "SELECT operation_id FROM"
                                                + " taska.project_cleanup_receipts WHERE"
                                                + " tenant_id=:t AND project_id=:p")
                                        .bind("t", t)
                                        .bind("p", p)
                                        .map((r, m) -> r.get("operation_id", UUID.class))
                                        .one())
                                .flatMap(
                                    id ->
                                        op.equals(id)
                                            ? Mono.just(true)
                                            : Mono.error(
                                                new DomainException(
                                                    DomainStatus.PERMISSION_DENIED,
                                                    "PROJECT_DELETE_PROOF_MISMATCH")));
                          }))
                  .then(
                      store.execute(
                          s -> {
                            var db = s.database();
                            return db.sql(
                                    "SELECT project_id FROM taska.project_cleanup_receipts WHERE"
                                        + " tenant_id=:t AND project_id=:p AND operation_id=:op FOR"
                                        + " UPDATE")
                                .bind("t", t)
                                .bind("p", p)
                                .bind("op", op)
                                .fetch()
                                .one()
                                .switchIfEmpty(
                                    Mono.error(
                                        new DomainException(
                                            DomainStatus.PERMISSION_DENIED,
                                            "PROJECT_DELETE_PROOF_MISMATCH")))
                                .then(
                                    db.sql(
                                            "DELETE FROM taska.workflow_bindings WHERE tenant_id=:t"
                                                + " AND project_id=:p")
                                        .bind("t", t)
                                        .bind("p", p)
                                        .then())
                                .then(
                                    db.sql(
                                            "DELETE FROM taska.workflows WHERE tenant_id=:t AND"
                                                + " owner_project_id=:p")
                                        .bind("t", t)
                                        .bind("p", p)
                                        .then())
                                .then(
                                    db.sql(
                                            "UPDATE taska.project_cleanup_receipts SET"
                                                + " completed=true WHERE tenant_id=:t AND"
                                                + " project_id=:p AND operation_id=:op")
                                        .bind("t", t)
                                        .bind("p", p)
                                        .bind("op", op)
                                        .then())
                                .thenReturn(
                                    ProjectCleanupResult.newBuilder()
                                        .setTenantId(t.toString())
                                        .setProjectId(p.toString())
                                        .setOperationId(op.toString())
                                        .setComplete(true)
                                        .build());
                          }));
            });
  }
}
