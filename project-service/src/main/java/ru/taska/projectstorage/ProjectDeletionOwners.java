package ru.taska.projectstorage;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.FinalizeQuotaRequest;
import ru.taska.api.b2b.v1.FinalizeQuotaRpcRequest;
import ru.taska.api.b2b.v1.ProjectDeletionRequest;
import ru.taska.api.b2b.v1.ProjectResourceCleanupServiceGrpc;
import ru.taska.api.b2b.v1.QuotaAuthorityServiceGrpc;
import ru.taska.api.b2b.v1.QuotaReservation;
import ru.taska.api.b2b.v1.ReactorProjectResourceCleanupServiceGrpc;
import ru.taska.api.b2b.v1.ReactorQuotaAuthorityServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.ProjectQuotaProtocol;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;

/** Сетевые шаги с primary подтверждением владельца; удаление строки и receipt атомарны. */
public final class ProjectDeletionOwners implements ProjectDeletions.Owners {
  private final TenantProjectStore store;
  private final ProjectQuotaProtocol.Authority root;
  private final ProjectQuotaOwner owner;
  private final ManagedDomainReads reads;
  private final RpcContextSecurity security;

  public ProjectDeletionOwners(
      TenantProjectStore store,
      ProjectQuotaProtocol.Authority root,
      ProjectQuotaOwner owner,
      ManagedDomainReads reads,
      RpcContextSecurity security) {
    this.store = store;
    this.root = root;
    this.owner = owner;
    this.reads = reads;
    this.security = security;
  }

  @Override
  public Mono<Boolean> issues(UUID t, UUID p, UUID op) {
    return cleanup("issue-service", t, p, op);
  }

  @Override
  public Mono<Boolean> workflows(UUID t, UUID p, UUID op) {
    return cleanup("workflow-service", t, p, op);
  }

  private Mono<Boolean> cleanup(String service, UUID t, UUID p, UUID op) {
    return new SignedQuotaClient(security, Clock.systemUTC(), "project-service", service)
        .call(
            t,
            ProjectResourceCleanupServiceGrpc.getCleanupProjectMethod().getFullMethodName(),
            "cleanupProjectResources",
            new RpcContextSecurity.Policy(true, true),
            c ->
                ProjectDeletionRequest.newBuilder()
                    .setContext(c)
                    .setProjectId(p.toString())
                    .setOperationId(op.toString())
                    .build(),
            (q, auth) ->
                ReactorProjectResourceCleanupServiceGrpc.newReactorStub(reads.channel(service))
                    .withDeadlineAfter(20, TimeUnit.SECONDS)
                    .withInterceptors(auth)
                    .cleanupProject(q))
        .map(
            r -> {
              if (!r.getTenantId().equals(t.toString())
                  || !r.getProjectId().equals(p.toString())
                  || !r.getOperationId().equals(op.toString()))
                throw ProjectDeletions.problem(
                    DomainStatus.DATA_LOSS, "PROJECT_CLEANUP_PROOF_MISMATCH");
              return r.getComplete();
            });
  }

  private Mono<ProjectQuotaProtocol.Reservation> reservation(UUID t, UUID p) {
    return store
        .execute(
            s ->
                s.database()
                    .sql(
                        "SELECT operation_id FROM taska.project_creation_operations WHERE"
                            + " tenant_id=:t AND project_id=:p")
                    .bind("t", t)
                    .bind("p", p)
                    .map((r, m) -> r.get("operation_id", UUID.class))
                    .one()
                    .switchIfEmpty(
                        Mono.error(
                            ProjectDeletions.problem(
                                DomainStatus.FAILED_PRECONDITION,
                                "PROJECT_CREATION_RECEIPT_REQUIRED"))))
        .flatMap(root::get)
        .map(
            r -> {
              if (!t.equals(r.tenant()) || !p.equals(r.resource()))
                throw ProjectDeletions.problem(DomainStatus.DATA_LOSS, "PROJECT_RECEIPT_MISMATCH");
              return r;
            });
  }

  @Override
  public Mono<Void> removeProject(UUID t, UUID p, UUID op) {
    return reservation(t, p)
        .flatMap(
            r ->
                store.execute(
                    s -> {
                      var db = s.database();
                      return s.lock(p)
                          .then(ProjectDeletions.lock(db, t, p.toString()))
                          .then(
                              db.sql(
                                      "SELECT id FROM taska.project_deletion_operations WHERE"
                                          + " tenant_id=:t AND project_id=:p AND id=:op AND state"
                                          + " IN ('RESOURCES_DELETED','DELETED','SUCCEEDED') AND"
                                          + " issue_done AND workflow_done FOR UPDATE")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .bind("op", op)
                                  .fetch()
                                  .one()
                                  .switchIfEmpty(
                                      Mono.error(
                                          ProjectDeletions.problem(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "PROJECT_CLEANUP_PROOF_REQUIRED"))))
                          .then(
                              db.sql(
                                      "DELETE FROM taska.project_settings WHERE tenant_id=:t AND"
                                          + " project_id=:p")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .then())
                          .then(
                              db.sql(
                                      "DELETE FROM taska.project_members WHERE tenant_id=:t AND"
                                          + " project_id=:p")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .then())
                          .then(
                              db.sql(
                                      "DELETE FROM taska.project_issue_types WHERE tenant_id=:t AND"
                                          + " project_id=:p")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .then())
                          .then(
                              db.sql(
                                      "INSERT INTO"
                                          + " taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload)"
                                          + " SELECT"
                                          + " :t,'project',p.id,'ProjectDeleted',jsonb_build_object('projectId',p.id,'operationId',:op)"
                                          + " FROM taska.projects p WHERE p.tenant_id=:t AND"
                                          + " p.id=:p")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .bind("op", op)
                                  .then())
                          .then(
                              db.sql("DELETE FROM taska.projects WHERE tenant_id=:t AND id=:p")
                                  .bind("t", t)
                                  .bind("p", p)
                                  .then())
                          .then(owner.recordDeleted(db, r));
                    }));
  }

  @Override
  public Mono<Void> releaseQuota(UUID t, UUID p, UUID op) {
    return reservation(t, p)
        .flatMap(
            r ->
                new SignedQuotaClient(
                        security, Clock.systemUTC(), "project-service", "tenant-service")
                    .call(
                        t,
                        QuotaAuthorityServiceGrpc.getReleaseProjectUsageMethod()
                            .getFullMethodName(),
                        "releaseProjectUsage",
                        new RpcContextSecurity.Policy(true, true),
                        c ->
                            FinalizeQuotaRpcRequest.newBuilder()
                                .setContext(c)
                                .setKind(QuotaReservation.KindValue.KIND_PROJECT)
                                .setReservationId(r.id().toString())
                                .setFence(Long.toString(r.fence()))
                                .setPayload(
                                    FinalizeQuotaRequest.newBuilder()
                                        .setOperationId(r.operation().toString())
                                        .setResourceId(p.toString()))
                                .build(),
                        (q, auth) ->
                            ReactorQuotaAuthorityServiceGrpc.newReactorStub(
                                    reads.channel("tenant-service"))
                                .withDeadlineAfter(5, TimeUnit.SECONDS)
                                .withInterceptors(auth)
                                .releaseProjectUsage(q)))
        .then();
  }
}
