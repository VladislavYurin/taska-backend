package ru.taska.projectstorage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.DeleteProjectRpcRequest;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.ProjectDeletionProof;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.ProjectDeletionAuthority;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Принятие и продолжение необратимого удаления; сеть выполняется вне SQL-транзакции. */
public final class ProjectDeletions {
  public interface Owners {
    Mono<Boolean> issues(UUID tenant, UUID project, UUID operation);

    Mono<Boolean> workflows(UUID tenant, UUID project, UUID operation);

    Mono<Void> removeProject(UUID tenant, UUID project, UUID operation);

    Mono<Void> releaseQuota(UUID tenant, UUID project, UUID operation);
  }

  private final TenantProjectStore store;
  private final TenantProjectApi api;
  private final Owners owners;

  public ProjectDeletions(TenantProjectStore store, TenantProjectApi api, Owners owners) {
    this.store = store;
    this.api = api;
    this.owners = owners;
  }

  public Mono<Operation> start(DeleteProjectRpcRequest q) {
    UUID project = TenantProjectApi.uuid(q.getProjectId());
    if (!q.hasMutation() || !q.getMutation().hasExpectedVersion())
      return Mono.error(problem(DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT"));
    UUID key = TenantProjectApi.uuid(q.getMutation().getIdempotencyKey());
    int expected;
    try {
      expected = Integer.parseInt(q.getMutation().getExpectedVersion());
      if (expected < 0) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      return Mono.error(problem(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));
    }
    if (!q.getConfirmed())
      return Mono.error(problem(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED"));
    return api.deletionFrame("write")
        .flatMap(
            f -> {
              UUID tenant = UUID.fromString(f.context().tenantId()),
                  actor = UUID.fromString(f.context().actorId());
              String authority = authority(f.context());
              var provenance = RpcContextBridge.details(f.context()).getContext();
              return store.execute(
                  s -> {
                    var db = s.database();
                    return db.sql("SELECT pg_advisory_xact_lock(459,hashtext(:k))")
                        .bind("k", tenant + "/" + actor + "/" + authority + "/" + key)
                        .then()
                        .then(s.lock(project).then())
                        .then(lock(db, tenant, project.toString()))
                        .then(
                            db.sql(
                                    "SELECT * FROM taska.project_deletion_operations WHERE"
                                        + " tenant_id=:t AND actor_id=:a AND authority=:g AND"
                                        + " request_key=:k")
                                .bind("t", tenant)
                                .bind("a", actor)
                                .bind("g", authority)
                                .bind("k", key)
                                .fetch()
                                .one())
                        .flatMap(
                            row ->
                                api.deletionReplay(s, f, project)
                                    .then(
                                        Mono.fromSupplier(
                                            () -> {
                                              if (!project.equals(row.get("project_id"))
                                                  || expected
                                                      != (Integer) row.get("expected_version")
                                                  || !q.getConfirmedProjectKey()
                                                      .equals(row.get("confirmed_key")))
                                                throw problem(
                                                    DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT");
                                              return operation(row);
                                            })))
                        .switchIfEmpty(
                            Mono.defer(
                                () ->
                                    api.deletionAdmission(s, f, project)
                                        .flatMap(
                                            current -> {
                                              if (current.getVersion() != expected)
                                                return Mono.error(
                                                    problem(
                                                        DomainStatus.ABORTED, "VERSION_CONFLICT"));
                                              if (!current
                                                  .getProjectKey()
                                                  .equals(q.getConfirmedProjectKey()))
                                                return Mono.error(
                                                    problem(
                                                        DomainStatus.FAILED_PRECONDITION,
                                                        "CONFIRMATION_REQUIRED"));
                                              return db.sql(
                                                      "SELECT id FROM"
                                                          + " taska.project_deletion_operations"
                                                          + " WHERE tenant_id=:t AND project_id=:p")
                                                  .bind("t", tenant)
                                                  .bind("p", project)
                                                  .fetch()
                                                  .one()
                                                  .flatMap(
                                                      row ->
                                                          Mono.<Operation>error(
                                                              problem(
                                                                  DomainStatus.ABORTED,
                                                                  "PROJECT_DELETING")))
                                                  .switchIfEmpty(
                                                      Mono.defer(
                                                          () -> {
                                                            UUID id = UUID.randomUUID();
                                                            return db.sql(
                                                                    "INSERT INTO"
                                                                        + " taska.project_deletion_operations(tenant_id,id,project_id,actor_id,authority,credential_kind,actor_generation,membership_generation,policy_version,request_id,request_key,expected_version,confirmed_key)"
                                                                        + " VALUES(:t,:id,:p,:a,:g,:kind,:actorGen,:memberGen,:policy,:request,:k,:v,:key)"
                                                                        + " RETURNING *")
                                                                .bind("t", tenant)
                                                                .bind("id", id)
                                                                .bind("p", project)
                                                                .bind("a", actor)
                                                                .bind("g", authority)
                                                                .bind(
                                                                    "kind",
                                                                    provenance
                                                                        .getCredentialKind()
                                                                        .name())
                                                                .bind(
                                                                    "actorGen",
                                                                    provenance.getActorGeneration())
                                                                .bind(
                                                                    "memberGen",
                                                                    provenance
                                                                        .getMembershipGeneration())
                                                                .bind(
                                                                    "policy",
                                                                    ru.taska.capability
                                                                        .CapabilityEvaluator
                                                                        .POLICY_VERSION)
                                                                .bind(
                                                                    "request",
                                                                    UUID.fromString(
                                                                        f.context().requestId()))
                                                                .bind("k", key)
                                                                .bind("v", expected)
                                                                .bind(
                                                                    "key",
                                                                    q.getConfirmedProjectKey())
                                                                .fetch()
                                                                .one()
                                                                .flatMap(
                                                                    row ->
                                                                        s.appendEvent(
                                                                                project,
                                                                                "ProjectDeletionAccepted",
                                                                                "{\"projectId\":\""
                                                                                    + project
                                                                                    + "\",\"operationId\":\""
                                                                                    + id
                                                                                    + "\"}")
                                                                            .thenReturn(
                                                                                operation(row)));
                                                          }));
                                            })));
                  });
            });
  }

  public Mono<Operation> get(UUID id) {
    return api.deletionFrame("read")
        .flatMap(
            f ->
                store
                    .execute(
                        s ->
                            s.database()
                                .sql(
                                    "SELECT project_id FROM taska.project_deletion_operations WHERE"
                                        + " tenant_id=:t AND id=:id")
                                .bind("t", UUID.fromString(f.context().tenantId()))
                                .bind("id", id)
                                .map((r, m) -> r.get("project_id", UUID.class))
                                .one()
                                .switchIfEmpty(
                                    Mono.error(
                                        problem(DomainStatus.NOT_FOUND, "OPERATION_NOT_FOUND"))))
                    .flatMap(p -> get(p, id)));
  }

  public Mono<Operation> get(UUID project, UUID id) {
    return api.deletionFrame("read")
        .flatMap(
            f ->
                store.execute(
                    s ->
                        required(s.database(), UUID.fromString(f.context().tenantId()), project, id)
                            .flatMap(
                                row -> {
                                  if (!f.implicitAdmin()
                                      && !f.context()
                                          .actorId()
                                          .equals(row.get("actor_id").toString()))
                                    return Mono.error(
                                        problem(DomainStatus.NOT_FOUND, "OPERATION_NOT_FOUND"));
                                  return Mono.just(operation(row));
                                })));
  }

  public Mono<ProjectDeletionProof> inspect(UUID project, UUID id) {
    return ProjectDeletionAuthority.service()
        .then(
            scoped(
                db ->
                    requiredContext(db, project, id)
                        .map(
                            row ->
                                ProjectDeletionProof.newBuilder()
                                    .setOperation(operation(row))
                                    .setProjectId(project.toString())
                                    .setAccepted(true)
                                    .setInitiatorId(row.get("actor_id").toString())
                                    .setResourcesDeleted(
                                        List.of("RESOURCES_DELETED", "DELETED", "SUCCEEDED")
                                            .contains(row.get("state")))
                                    .build())));
  }

  public Mono<List<UUID>> pending() {
    return ProjectDeletionAuthority.service()
        .then(VerifiedTenantContext.current())
        .flatMap(
            c ->
                scoped(
                    db ->
                        db.sql(
                                "SELECT id FROM taska.project_deletion_operations WHERE"
                                    + " tenant_id=:t AND state<>'SUCCEEDED' ORDER BY updated_at,id"
                                    + " LIMIT 50")
                            .bind("t", UUID.fromString(c.tenantId()))
                            .map((r, m) -> r.get("id", UUID.class))
                            .all()
                            .collectList()));
  }

  public Mono<Operation> recover(UUID id) {
    return ProjectDeletionAuthority.service()
        .then(
            scoped(
                db ->
                    VerifiedTenantContext.current()
                        .flatMap(
                            c ->
                                db.sql(
                                        "SELECT * FROM taska.project_deletion_operations WHERE"
                                            + " tenant_id=:t AND id=:id")
                                    .bind("t", UUID.fromString(c.tenantId()))
                                    .bind("id", id)
                                    .fetch()
                                    .one()
                                    .switchIfEmpty(
                                        Mono.error(
                                            problem(
                                                DomainStatus.NOT_FOUND, "OPERATION_NOT_FOUND"))))))
        .flatMap(
            row -> {
              UUID t = (UUID) row.get("tenant_id"), p = (UUID) row.get("project_id");
              if (row.get("state").equals("SUCCEEDED")) return Mono.just(operation(row));
              Mono<Void> issues =
                  (Boolean) row.get("issue_done")
                      ? Mono.empty()
                      : owners
                          .issues(t, p, id)
                          .flatMap(
                              done ->
                                  done
                                      ? mark(t, id, "issue_done")
                                      : Mono.error(
                                          problem(
                                              DomainStatus.UNAVAILABLE,
                                              "PROJECT_CLEANUP_PENDING")));
              Mono<Void> workflow =
                  (Boolean) row.get("workflow_done")
                      ? Mono.empty()
                      : owners
                          .workflows(t, p, id)
                          .flatMap(
                              done ->
                                  done
                                      ? mark(t, id, "workflow_done")
                                      : Mono.error(
                                          problem(
                                              DomainStatus.UNAVAILABLE,
                                              "PROJECT_CLEANUP_PENDING")));
              return issues
                  .then(workflow)
                  .then(transition(t, id, "RESOURCES_DELETED"))
                  .then(Mono.defer(() -> owners.removeProject(t, p, id)))
                  .then(transition(t, id, "DELETED"))
                  .then(Mono.defer(() -> owners.releaseQuota(t, p, id)))
                  .then(transition(t, id, "SUCCEEDED"))
                  .then(scoped(db -> required(db, t, p, id).map(ProjectDeletions::operation)))
                  .onErrorResume(error -> failure(t, id, error).then(Mono.error(error)));
            });
  }

  /** Сохраняются только ограниченные коды, без текста RPC, ключей объектов и credentials. */
  private Mono<Void> failure(UUID tenant, UUID id, Throwable error) {
    String code =
        error instanceof DomainException d
            ? d.getStatus().name()
            : io.grpc.Status.fromThrowable(error).getCode().name();
    boolean manual =
        List.of("DATA_LOSS", "NOT_FOUND").contains(code)
            || error instanceof DomainException d
                && "PROJECT_CREATION_RECEIPT_REQUIRED".equals(d.getDetails().get("code"));
    return scoped(
        db ->
            db.sql(
                    "UPDATE taska.project_deletion_operations SET"
                        + " last_code=:code,manual_review=:manual,updated_at=now() WHERE"
                        + " tenant_id=:t AND id=:id AND state<>'SUCCEEDED'")
                .bind("t", tenant)
                .bind("id", id)
                .bind("code", code)
                .bind("manual", manual)
                .then());
  }

  private Mono<Void> mark(UUID t, UUID id, String column) {
    if (!List.of("issue_done", "workflow_done").contains(column))
      throw new IllegalArgumentException();
    return scoped(
        db ->
            db.sql(
                    "UPDATE taska.project_deletion_operations SET "
                        + column
                        + "=true,updated_at=now() WHERE tenant_id=:t AND id=:id")
                .bind("t", t)
                .bind("id", id)
                .then());
  }

  private Mono<Void> transition(UUID t, UUID id, String state) {
    return scoped(
        db ->
            db.sql(
                    "UPDATE taska.project_deletion_operations SET"
                        + " state=:s,updated_at=now(),last_code=NULL,manual_review=false WHERE"
                        + " tenant_id=:t AND id=:id AND"
                        + " array_position(ARRAY['PENDING','RESOURCES_DELETED','DELETED','SUCCEEDED'],state)<array_position(ARRAY['PENDING','RESOURCES_DELETED','DELETED','SUCCEEDED'],:s)")
                .bind("t", t)
                .bind("id", id)
                .bind("s", state)
                .then());
  }

  private <T> Mono<T> scoped(Function<DatabaseClient, Mono<T>> work) {
    return store.execute(s -> work.apply(s.database()));
  }

  private Mono<Map<String, Object>> requiredContext(DatabaseClient db, UUID p, UUID id) {
    return VerifiedTenantContext.current()
        .flatMap(c -> required(db, UUID.fromString(c.tenantId()), p, id));
  }

  private static Mono<Map<String, Object>> required(DatabaseClient db, UUID t, UUID p, UUID id) {
    return db.sql(
            "SELECT * FROM taska.project_deletion_operations WHERE tenant_id=:t AND project_id=:p"
                + " AND id=:id")
        .bind("t", t)
        .bind("p", p)
        .bind("id", id)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(problem(DomainStatus.NOT_FOUND, "OPERATION_NOT_FOUND")));
  }

  static Mono<Void> lock(DatabaseClient db, UUID t, String suffix) {
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k,0) % 256)")
        .bind("k", t + "/project-delete/" + suffix)
        .then();
  }

  private static String authority(VerifiedTenantContext c) {
    var q = RpcContextBridge.details(c).getContext();
    return q.hasGrantId()
        ? q.getCredentialKind().name() + "/" + q.getGrantId() + "/" + q.getGrantGeneration()
        : "ACTOR";
  }

  private static Operation operation(Map<String, Object> row) {
    var result =
        Operation.newBuilder()
            .setId(row.get("id").toString())
            .setType("PROJECT_DELETE")
            .setTenantId(row.get("tenant_id").toString())
            .setResourceId(row.get("project_id").toString())
            .setCreatedAt(((OffsetDateTime) row.get("created_at")).toInstant().toString())
            .setUpdatedAt(((OffsetDateTime) row.get("updated_at")).toInstant().toString())
            .setState(
                row.get("state").equals("SUCCEEDED")
                    ? Operation.StateValue.STATE_SUCCEEDED
                    : Boolean.TRUE.equals(row.get("manual_review"))
                        ? Operation.StateValue.STATE_MANUAL_REVIEW
                        : Operation.StateValue.STATE_RUNNING);
    if (row.get("last_code") != null)
      result.setFailureCode(failureCode(row.get("last_code").toString()));
    return result.build();
  }

  private static ErrorCode failureCode(String code) {
    return switch (code) {
      case "NOT_FOUND" -> ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND;
      case "DATA_LOSS", "FAILED_PRECONDITION", "ABORTED", "ALREADY_EXISTS" ->
          ErrorCode.ERROR_CODE_INVALID_STATE;
      case "PERMISSION_DENIED" -> ErrorCode.ERROR_CODE_SCOPE_DENIED;
      case "UNAUTHENTICATED" -> ErrorCode.ERROR_CODE_AUTHENTICATION_REQUIRED;
      case "INVALID_ARGUMENT" -> ErrorCode.ERROR_CODE_VALIDATION_FAILED;
      case "RESOURCE_EXHAUSTED" -> ErrorCode.ERROR_CODE_RATE_LIMITED;
      default ->ErrorCode.ERROR_CODE_DEPENDENCY_UNAVAILABLE;
    };
  }

  static DomainException problem(DomainStatus s, String code) {
    return new DomainException(s, code, Map.of("code", code));
  }
}
