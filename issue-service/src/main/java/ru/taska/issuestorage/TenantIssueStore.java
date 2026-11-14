package ru.taska.issuestorage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Слой хранения. До вызова TAS-343 проверяет права на проект, workflow и вложенные идентификаторы.
 */
public final class TenantIssueStore {
  public record CreateIssue(
      UUID projectId,
      String projectKey,
      String issueType,
      String summary,
      String description,
      String statusKey,
      String priority,
      UUID assigneeId,
      BigDecimal storyPoints,
      LocalDate startDate,
      LocalDate dueDate,
      Integer originalEstimateMinutes,
      Integer remainingEstimateMinutes) {}

  public record Created(UUID id, UUID projectId, int number, String key, int version) {}

  /** Нативное состояние задачи без данных профилей пользователей. */
  public record Snapshot(
      Created identity,
      String issueType,
      String summary,
      String description,
      String statusKey,
      String priority,
      UUID assigneeId,
      UUID reporterId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      BigDecimal storyPoints,
      LocalDate startDate,
      LocalDate dueDate,
      Integer originalEstimateMinutes,
      Integer remainingEstimateMinutes,
      int timeSpentMinutes,
      OffsetDateTime deletedAt) {
    public Snapshot {
      createdAt = normalized(createdAt);
      updatedAt = normalized(updatedAt);
      deletedAt = normalized(deletedAt);
    }

    private static OffsetDateTime normalized(OffsetDateTime value) {
      return value == null
          ? null
          : value
              .withOffsetSameInstant(ZoneOffset.UTC)
              .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
  }

  private final TenantTransactionExecutor transactions;
  private final ObjectMapper json;
  private final Clock clock;
  private final LifetimeTaskQuota quota;

  public TenantIssueStore(TenantTransactionExecutor transactions, ObjectMapper json, Clock clock) {
    this(transactions, json, clock, LifetimeTaskQuota.unavailable(transactions, clock));
  }

  public TenantIssueStore(
      TenantTransactionExecutor transactions,
      ObjectMapper json,
      Clock clock,
      LifetimeTaskQuota quota) {
    this.quota = Objects.requireNonNull(quota);
    this.transactions = transactions;
    this.json = json;
    this.clock = clock;
  }

  public <T> Mono<T> execute(Function<Session, Mono<T>> work) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> failure(DomainStatus.UNAUTHENTICATED, "Verified tenant context required"))
        .flatMap(
            context ->
                transactions.execute(
                    db -> {
                      var session = new Session(db, context);
                      return Mono.defer(() -> work.apply(session))
                          .doFinally(signal -> session.active.set(false));
                    }));
  }

  private static DomainException failure(DomainStatus status, String reason) {
    var details =
        java.util.Set.of("VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT").contains(reason)
            ? java.util.Map.of("code", reason)
            : java.util.Map.<String, String>of();
    return new DomainException(status, reason, details);
  }

  /**
   * Не сохранять этот объект и не запускать отдельные подписки: клиент привязан к текущей
   * транзакции исполнителя.
   */
  public final class Session {
    private final java.util.concurrent.atomic.AtomicBoolean active =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    private final DatabaseClient db;
    private final UUID tenant;
    private final String actor, operation, requestId;

    private Session(DatabaseClient db, VerifiedTenantContext context) {
      this.db = db;
      tenant = UUID.fromString(context.tenantId());
      actor = context.actorId();
      operation = context.action();
      requestId = context.requestId();
    }

    private <T> Mono<T> guarded(Mono<T> work) {
      return Mono.defer(
          () -> {
            if (!active.get())
              return Mono.error(
                  failure(DomainStatus.FAILED_PRECONDITION, "Issue session is no longer active"));
            return VerifiedTenantContext.current()
                .flatMap(
                    current -> {
                      if (!tenant.toString().equals(current.tenantId())
                          || !Objects.equals(actor, current.actorId())
                          || !Objects.equals(operation, current.action()))
                        return Mono.error(
                            failure(
                                DomainStatus.PERMISSION_DENIED,
                                "Cannot change issue session authority"));
                      return work;
                    });
          });
    }

    /** Внутреннее чтение хранилища. Бизнес-слой отдельно проверяет права на проект. */
    public Mono<Snapshot> findActive(UUID id) {
      return guarded(
          Mono.defer(
              () -> {
                if (id == null || id.equals(new UUID(0, 0))) throw invalid();
                return db.sql(
                        "SELECT * FROM taska.issues WHERE tenant_id=:tenant AND id=:id"
                            + " AND deleted_at IS NULL")
                    .bind("tenant", tenant)
                    .bind("id", id)
                    .map((row, metadata) -> snapshot(row))
                    .one();
              }));
    }

    /**
     * Поиск по ключу в пределах арендатора; при отсутствии ключа глобальный поиск не выполняется.
     */
    public Mono<Snapshot> findActiveByKey(String key) {
      return guarded(
          Mono.defer(
              () -> {
                if (key == null || !key.matches("[A-Z][A-Z0-9]{1,15}-[1-9][0-9]{0,9}"))
                  throw invalid();
                return db.sql(
                        "SELECT * FROM taska.issues WHERE tenant_id=:tenant AND issue_key=:key"
                            + " AND deleted_at IS NULL")
                    .bind("tenant", tenant)
                    .bind("key", key)
                    .map((row, metadata) -> snapshot(row))
                    .one();
              }));
    }

    private Snapshot snapshot(io.r2dbc.spi.Row row) {
      return new Snapshot(
          new Created(
              row.get("id", UUID.class),
              row.get("project_id", UUID.class),
              row.get("issue_number", Integer.class),
              row.get("issue_key", String.class),
              row.get("version", Integer.class)),
          row.get("issue_type", String.class),
          row.get("summary", String.class),
          row.get("description", String.class),
          row.get("status_key", String.class),
          row.get("priority", String.class),
          row.get("assignee_id", UUID.class),
          row.get("reporter_id", UUID.class),
          row.get("created_at", OffsetDateTime.class),
          row.get("updated_at", OffsetDateTime.class),
          row.get("story_points", BigDecimal.class),
          row.get("start_date", LocalDate.class),
          row.get("due_date", LocalDate.class),
          row.get("original_estimate_minutes", Integer.class),
          row.get("remaining_estimate_minutes", Integer.class),
          row.get("time_spent_minutes", Integer.class),
          row.get("deleted_at", OffsetDateTime.class));
    }

    Mono<List<ru.taska.api.issue.v1.ProjectTaskQuota>> quotas(TenantIssueAccess.ReadBatch batch) {
      return guarded(
          Mono.defer(
              () -> {
                for (var p : batch.permits()) p.check(tenant, actor, operation, p.project());
                var ids = batch.permits().stream().map(TenantIssueAccess.Permit::project).toList();
                if (ids.isEmpty()) return Mono.just(List.of());
                return db.sql(
                        "SELECT project_id,lifetime_created,retired_at FROM taska.project_counters"
                            + " WHERE tenant_id=:t AND project_id IN (:ids)")
                    .bind("t", tenant)
                    .bind("ids", ids)
                    .fetch()
                    .all()
                    .collectMap(r -> (UUID) r.get("project_id"))
                    .flatMap(
                        rows ->
                            reactor.core.publisher.Flux.fromIterable(batch.permits())
                                .concatMap(
                                    p -> {
                                      p.check(tenant, actor, operation, p.project());
                                      var row = rows.get(p.project());
                                      if (row != null && row.get("retired_at") != null)
                                        return Mono.error(
                                            failure(DomainStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
                                      var value =
                                          ru.taska.api.issue.v1.ProjectTaskQuota.newBuilder()
                                              .setProjectId(p.project().toString())
                                              .setUsed(
                                                  row == null
                                                      ? 0L
                                                      : (Long) row.get("lifetime_created"))
                                              .setUnlimited(batch.unlimited());
                                      if (batch.limit() != null) value.setLimit(batch.limit());
                                      return audit(p, null).thenReturn(value.build());
                                    })
                                .collectList());
              }));
    }

    Mono<List<ru.taska.api.issue.v1.ProjectIssueCount>> counts(
        TenantIssueAccess.ReadBatch batch, String type, boolean deleted) {
      return guarded(
          Mono.defer(
              () -> {
                for (var p : batch.permits()) p.check(tenant, actor, operation, p.project());
                var ids = batch.permits().stream().map(TenantIssueAccess.Permit::project).toList();
                if (ids.isEmpty()) return Mono.just(List.of());
                var query =
                    db.sql(
                            "SELECT project_id,count(*) n FROM taska.issues WHERE tenant_id=:t AND"
                                + " project_id IN (:ids)"
                                + (deleted ? "" : " AND deleted_at IS NULL")
                                + (type == null ? "" : " AND issue_type=:type")
                                + " GROUP BY project_id")
                        .bind("t", tenant)
                        .bind("ids", ids);
                if (type != null) query = query.bind("type", type);
                return query
                    .fetch()
                    .all()
                    .collectMap(r -> (UUID) r.get("project_id"), r -> (Long) r.get("n"))
                    .flatMap(
                        rows ->
                            reactor.core.publisher.Flux.fromIterable(batch.permits())
                                .concatMap(
                                    p ->
                                        audit(p, null)
                                            .thenReturn(
                                                ru.taska.api.issue.v1.ProjectIssueCount.newBuilder()
                                                    .setProjectId(p.project().toString())
                                                    .setIssueCount(
                                                        rows.getOrDefault(p.project(), 0L))
                                                    .build()))
                                .collectList());
              }));
    }

    /** Удалённые записи доступны только внутренним проверкам прав и повторным вызовам. */
    Mono<Snapshot> stored(UUID id, boolean lock) {
      return guarded(
          db.sql(
                  "SELECT * FROM taska.issues WHERE tenant_id=:tenant AND id=:id"
                      + (lock ? " FOR UPDATE" : ""))
              .bind("tenant", tenant)
              .bind("id", id)
              .map((r, m) -> snapshot(r))
              .one());
    }

    Mono<Void> audit(TenantIssueAccess.Permit permit, UUID issue) {
      return Mono.defer(
          () -> {
            permit.check(tenant, actor, operation, permit.project());
            if (!permit.auditRequired()) return Mono.empty();
            var query =
                db.sql(
                        """
INSERT INTO taska.issue_authorization_audit(tenant_id,actor_id,project_id,issue_id,action,request_id,bypassed)
VALUES(:t,:a,:p,:i,:op,:r,CAST(:b AS jsonb))
""")
                    .bind("t", tenant)
                    .bind("a", UUID.fromString(actor))
                    .bind("p", permit.project())
                    .bind("op", operation)
                    .bind("r", requestId)
                    .bind("b", json.writeValueAsString(permit.bypassed()));
            return nullable(query, "i", issue, UUID.class).fetch().rowsUpdated().then();
          });
    }

    Mono<List<ru.taska.api.issue.v1.IssueHistoryResponse>> history(
        UUID id, TenantIssueAccess.Permit permit) {
      return guarded(
          audit(permit, id)
              .thenMany(
                  db.sql(
                          """
SELECT id,actor_user_id,event_type,occurred_at,payload::text payload FROM taska.issue_history
WHERE tenant_id=:t AND issue_id=:id ORDER BY occurred_at,id
""")
                      .bind("t", tenant)
                      .bind("id", id)
                      .map(
                          (r, m) ->
                              ru.taska.api.issue.v1.IssueHistoryResponse.newBuilder()
                                  .setId(r.get("id", UUID.class).toString())
                                  .setIssueId(id.toString())
                                  .setActorUserId(r.get("actor_user_id", UUID.class).toString())
                                  .setEventType(
                                      ru.taska.api.issue.v1.IssueEventType.valueOf(
                                          "ISSUE_EVENT_TYPE_" + r.get("event_type", String.class)))
                                  .setOccurredAt(
                                      TenantIssueApi.timestamp(
                                          r.get("occurred_at", OffsetDateTime.class)))
                                  .setPayload(r.get("payload", String.class))
                                  .build())
                      .all())
              .collectList());
    }

    private Mono<java.util.Map<String, Object>> receipt(String key) {
      if (key == null || key.isBlank() || key.length() > 200) return Mono.error(invalid());
      return db.sql(
              """
              SELECT request_hash,state,response::text response FROM taska.idempotency_keys
              WHERE tenant_id=:t AND user_id=:a AND operation_code=:op AND key=:k
              """)
          .bind("t", tenant)
          .bind("a", UUID.fromString(actor))
          .bind("op", operation)
          .bind("k", key)
          .fetch()
          .one();
    }

    Mono<Snapshot> replay(
        String key, String fingerprint, UUID id, String event, TenantIssueAccess.Permit permit) {
      return guarded(
          receipt(key)
              .flatMap(
                  row -> {
                    if (!mutationHash(id, event, fingerprint).equals(row.get("request_hash")))
                      return Mono.error(
                          failure(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                    if (!"SUCCEEDED".equals(row.get("state"))) return Mono.empty();
                    return audit(permit, id)
                        .thenReturn(json.readValue(row.get("response").toString(), Snapshot.class));
                  }));
    }

    Mono<Snapshot> replayCreate(String key, String fingerprint, TenantIssueAccess.Permit permit) {
      return replayCreate(key, fingerprint, permit, false);
    }

    private Mono<Snapshot> replayCreate(
        String key, String fingerprint, TenantIssueAccess.Permit permit, boolean initializing) {
      return guarded(
          receipt(key)
              .flatMap(
                  row -> {
                    var response = json.readTree(row.get("response").toString());
                    if (!response.has("wireHash"))
                      return initializing
                          ? Mono.empty()
                          : Mono.error(
                              failure(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                    if (!fingerprint.equals(response.path("wireHash").asText()))
                      return Mono.error(
                          failure(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                    var result = json.treeToValue(response.path("snapshot"), Snapshot.class);
                    return audit(permit, result.identity().id()).thenReturn(result);
                  }));
    }

    Mono<Snapshot> completeCreate(
        String key, String fingerprint, Created result, TenantIssueAccess.Permit permit) {
      return replayCreate(key, fingerprint, permit, true)
          .switchIfEmpty(
              Mono.defer(
                  () ->
                      stored(result.id(), false)
                          .flatMap(
                              snapshot ->
                                  audit(permit, result.id())
                                      .then(
                                          db.sql(
                                                  """
UPDATE taska.idempotency_keys SET response=response || CAST(:extra AS jsonb)
WHERE tenant_id=:t AND user_id=:a AND operation_code=:op AND key=:k AND state='SUCCEEDED'
""")
                                              .bind("t", tenant)
                                              .bind("a", UUID.fromString(actor))
                                              .bind("op", operation)
                                              .bind("k", key)
                                              .bind(
                                                  "extra",
                                                  json.writeValueAsString(
                                                      java.util.Map.of(
                                                          "wireHash",
                                                          fingerprint,
                                                          "snapshot",
                                                          snapshot)))
                                              .fetch()
                                              .rowsUpdated())
                                      .thenReturn(snapshot))));
    }

    private record Claim(long inserted, String hash, String state, String response) {}

    /**
     * Версия, изменение, история, outbox и точный ответ для повтора фиксируются одной транзакцией.
     */
    Mono<Snapshot> mutate(
        String key,
        String fingerprint,
        UUID id,
        int expectedVersion,
        String event,
        boolean delete,
        Function<Snapshot, Mono<CreateIssue>> change,
        TenantIssueAccess.Permit permit) {
      return guarded(
          transactions.execute(
              ignored ->
                  Mono.defer(
                      () -> {
                        permit.check(tenant, actor, operation, permit.project());
                        if (key == null
                            || key.isBlank()
                            || key.length() > 200
                            || expectedVersion < 1
                            || !List.of("UPDATED", "ASSIGNED", "TRANSITIONED", "DELETED")
                                .contains(event)) throw invalid();
                        String digest = mutationHash(id, event, fingerprint);
                        return claimMutation(key, digest)
                            .flatMap(
                                receipt -> {
                                  if (!digest.equals(receipt.hash()))
                                    return Mono.error(
                                        failure(
                                            DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                                  if ("SUCCEEDED".equals(receipt.state()))
                                    return audit(permit, id)
                                        .thenReturn(
                                            json.readValue(receipt.response(), Snapshot.class));
                                  if (receipt.inserted() != 1)
                                    return Mono.error(
                                        failure(
                                            DomainStatus.ABORTED, "Incomplete idempotency claim"));
                                  return stored(id, true)
                                      .switchIfEmpty(
                                          Mono.error(
                                              failure(DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND")))
                                      .flatMap(
                                          before -> {
                                            permit.check(
                                                tenant,
                                                actor,
                                                operation,
                                                before.identity().projectId());
                                            if (before.deletedAt() != null)
                                              return Mono.error(
                                                  failure(
                                                      DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND"));
                                            if (before.identity().version() != expectedVersion)
                                              return Mono.error(
                                                  failure(
                                                      DomainStatus.ABORTED, "VERSION_CONFLICT"));
                                            if (expectedVersion == Integer.MAX_VALUE)
                                              return Mono.error(
                                                  failure(
                                                      DomainStatus.RESOURCE_EXHAUSTED,
                                                      "Issue version exhausted"));
                                            return change
                                                .apply(before)
                                                .flatMap(
                                                    raw -> {
                                                      permit.check(
                                                          tenant,
                                                          actor,
                                                          operation,
                                                          before.identity().projectId());
                                                      var c = canonical(raw);
                                                      if (!c.projectId()
                                                              .equals(before.identity().projectId())
                                                          || !before
                                                              .identity()
                                                              .key()
                                                              .equals(
                                                                  c.projectKey()
                                                                      + "-"
                                                                      + before.identity().number()))
                                                        throw invalid();
                                                      var now =
                                                          clock.instant().atOffset(ZoneOffset.UTC);
                                                      var result =
                                                          new Snapshot(
                                                              new Created(
                                                                  id,
                                                                  c.projectId(),
                                                                  before.identity().number(),
                                                                  before.identity().key(),
                                                                  expectedVersion + 1),
                                                              c.issueType(),
                                                              c.summary(),
                                                              c.description(),
                                                              c.statusKey(),
                                                              c.priority(),
                                                              c.assigneeId(),
                                                              before.reporterId(),
                                                              before.createdAt(),
                                                              now,
                                                              c.storyPoints(),
                                                              c.startDate(),
                                                              c.dueDate(),
                                                              c.originalEstimateMinutes(),
                                                              c.remainingEstimateMinutes(),
                                                              before.timeSpentMinutes(),
                                                              delete ? now : null);
                                                      return writeMutation(result, event)
                                                          .then(audit(permit, id))
                                                          .then(finishMutation(key, result))
                                                          .thenReturn(result);
                                                    });
                                          });
                                });
                      })));
    }

    private Mono<Claim> claimMutation(String key, String digest) {
      var now = clock.instant().atOffset(ZoneOffset.UTC);
      return db.sql(
              """
INSERT INTO taska.idempotency_keys(tenant_id,key,user_id,operation_code,request_hash,state,response,created_at,expires_at)
VALUES(:t,:k,:a,:op,:h,'PENDING','{}',:now,:expires)
ON CONFLICT(tenant_id,user_id,operation_code,key) DO NOTHING
""")
          .bind("t", tenant)
          .bind("k", key)
          .bind("a", UUID.fromString(actor))
          .bind("op", operation)
          .bind("h", digest)
          .bind("now", now)
          .bind("expires", now.plusDays(1))
          .fetch()
          .rowsUpdated()
          .flatMap(
              inserted ->
                  db.sql(
                          """
SELECT request_hash,state,response::text response FROM taska.idempotency_keys
WHERE tenant_id=:t AND user_id=:a AND operation_code=:op AND key=:k FOR UPDATE
""")
                      .bind("t", tenant)
                      .bind("a", UUID.fromString(actor))
                      .bind("op", operation)
                      .bind("k", key)
                      .fetch()
                      .one()
                      .map(
                          r ->
                              new Claim(
                                  inserted,
                                  r.get("request_hash").toString(),
                                  r.get("state").toString(),
                                  r.get("response").toString())));
    }

    private Mono<Void> finishMutation(String key, Snapshot result) {
      return db.sql(
              """
UPDATE taska.idempotency_keys SET state='SUCCEEDED',resource_id=:i,response=CAST(:r AS jsonb)
WHERE tenant_id=:t AND user_id=:a AND operation_code=:op AND key=:k AND state='PENDING'
""")
          .bind("t", tenant)
          .bind("a", UUID.fromString(actor))
          .bind("op", operation)
          .bind("k", key)
          .bind("i", result.identity().id())
          .bind("r", json.writeValueAsString(result))
          .fetch()
          .rowsUpdated()
          .doOnNext(
              n -> {
                if (n != 1) throw failure(DomainStatus.ABORTED, "Receipt lost");
              })
          .then();
    }

    private String mutationHash(UUID id, String event, String payload) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        json.writeValueAsString(
                                List.of(
                                    tenant.toString(),
                                    actor,
                                    operation,
                                    id.toString(),
                                    event,
                                    payload))
                            .getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    private Mono<Void> writeMutation(Snapshot r, String event) {
      var q =
          db.sql(
                  """
UPDATE taska.issues SET issue_type=:type,summary=:summary,description=:description,status_key=:status,
  priority=:priority,assignee_id=:assignee,story_points=:points,start_date=:start,due_date=:due,
  original_estimate_minutes=:original,remaining_estimate_minutes=:remaining,updated_at=:now,version=:v,deleted_at=:deleted
WHERE tenant_id=:t AND id=:id AND version=:previous AND deleted_at IS NULL
""")
              .bind("t", tenant)
              .bind("id", r.identity().id())
              .bind("previous", r.identity().version() - 1)
              .bind("v", r.identity().version())
              .bind("type", r.issueType())
              .bind("summary", r.summary())
              .bind("status", r.statusKey())
              .bind("priority", r.priority())
              .bind("now", r.updatedAt());
      q = nullable(q, "description", r.description(), String.class);
      q = nullable(q, "assignee", r.assigneeId(), UUID.class);
      q = nullable(q, "points", r.storyPoints(), BigDecimal.class);
      q = nullable(q, "start", r.startDate(), LocalDate.class);
      q = nullable(q, "due", r.dueDate(), LocalDate.class);
      q = nullable(q, "original", r.originalEstimateMinutes(), Integer.class);
      q = nullable(q, "remaining", r.remainingEstimateMinutes(), Integer.class);
      q = nullable(q, "deleted", r.deletedAt(), OffsetDateTime.class);
      String payload =
          json.writeValueAsString(
              json.createObjectNode()
                  .put("issueId", r.identity().id().toString())
                  .put("projectId", r.identity().projectId().toString())
                  .put("statusKey", r.statusKey())
                  .put("issueKey", r.identity().key())
                  .put("version", r.identity().version()));
      return q.fetch()
          .rowsUpdated()
          .doOnNext(
              n -> {
                if (n != 1) throw failure(DomainStatus.ABORTED, "VERSION_CONFLICT");
              })
          .then(
              db.sql(
                      """
INSERT INTO taska.issue_history(tenant_id,issue_id,event_type,actor_user_id,occurred_at,payload)
VALUES(:t,:id,:e,:a,:now,CAST(:payload AS jsonb))
""")
                  .bind("t", tenant)
                  .bind("id", r.identity().id())
                  .bind("e", event)
                  .bind("a", UUID.fromString(actor))
                  .bind("now", r.updatedAt())
                  .bind("payload", payload)
                  .fetch()
                  .rowsUpdated())
          .then(
              db.sql(
                      """
INSERT INTO taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload,scope_kind,aggregate_version,schema_version,request_id)
VALUES(:t,'ISSUE',:id,:e,CAST(:payload AS jsonb),'TENANT',:v,1,:r)
""")
                  .bind("t", tenant)
                  .bind("id", r.identity().id())
                  .bind("e", event)
                  .bind("payload", payload)
                  .bind("v", (long) r.identity().version())
                  .bind("r", requestId)
                  .fetch()
                  .rowsUpdated())
          .then();
    }

    public Mono<Created> find(UUID id) {
      return guarded(
          db.sql(
                  "SELECT id,project_id,issue_number,issue_key,version FROM taska.issues WHERE"
                      + " tenant_id=:tenant AND id=:id")
              .bind("tenant", tenant)
              .bind("id", id)
              .map(
                  (r, m) ->
                      new Created(
                          r.get("id", UUID.class),
                          r.get("project_id", UUID.class),
                          r.get("issue_number", Integer.class),
                          r.get("issue_key", String.class),
                          r.get("version", Integer.class)))
              .one());
    }

    public Mono<List<Created>> list(UUID projectId) {
      return guarded(
          db.sql(
                  "SELECT id,project_id,issue_number,issue_key,version FROM taska.issues WHERE"
                      + " tenant_id=:tenant AND project_id=:project ORDER BY issue_number LIMIT"
                      + " 100")
              .bind("tenant", tenant)
              .bind("project", projectId)
              .map(
                  (r, m) ->
                      new Created(
                          r.get("id", UUID.class),
                          r.get("project_id", UUID.class),
                          r.get("issue_number", Integer.class),
                          r.get("issue_key", String.class),
                          r.get("version", Integer.class)))
              .all()
              .collectList());
    }

    /**
     * Резервирует запись идемпотентности до выделения номера. Изменения всех пяти таблиц
     * фиксируются вместе, в том числе при повторных вызовах.
     */
    public Mono<Created> create(String key, CreateIssue input) {
      return create(key, input, c -> Mono.empty());
    }

    Mono<Created> create(
        String key, CreateIssue input, Function<CreateIssue, Mono<Void>> validation) {
      return create(key, input, validation, true);
    }

    Mono<Created> create(
        String key,
        CreateIssue input,
        Function<CreateIssue, Mono<Void>> validation,
        boolean allowBusinessBypass) {
      return guarded(
          transactions.execute(
              ignored ->
                  Mono.defer(
                      () -> {
                        if (actor == null)
                          throw failure(DomainStatus.PERMISSION_DENIED, "Human actor required");
                        if (operation == null
                            || !operation.matches("[a-zA-Z][a-zA-Z0-9._:-]{0,63}")
                            || key == null
                            || key.isBlank()
                            || key.length() > 200) throw invalid();
                        var command = canonical(input);
                        String hash = hash(key, command);
                        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
                        // Конкурирующий INSERT ждёт завершения победителя. Следующий SELECT читает
                        // новый снимок READ COMMITTED.
                        return quota
                            .authorize(LifetimeTaskQuota.Use.CREATE, command.projectId())
                            .flatMap(
                                grant ->
                                    db.sql(
                                            """
INSERT INTO taska.idempotency_keys(tenant_id,key,user_id,operation_code,request_hash,state,response,created_at,expires_at)
VALUES(:tenant,:key,:actor,:op,:hash,'PENDING','{}',:now,:expires)
ON CONFLICT(tenant_id,user_id,operation_code,key) DO NOTHING
""")
                                        .bind("tenant", tenant)
                                        .bind("key", key)
                                        .bind("actor", UUID.fromString(actor))
                                        .bind("op", operation)
                                        .bind("hash", hash)
                                        .bind("now", now)
                                        .bind("expires", now.plusDays(1))
                                        .fetch()
                                        .rowsUpdated()
                                        .flatMap(
                                            inserted ->
                                                db.sql(
                                                        """
SELECT request_hash,state,response::text response FROM taska.idempotency_keys
WHERE tenant_id=:tenant AND user_id=:actor AND operation_code=:op AND key=:key FOR UPDATE
""")
                                                    .bind("tenant", tenant)
                                                    .bind("actor", UUID.fromString(actor))
                                                    .bind("op", operation)
                                                    .bind("key", key)
                                                    .fetch()
                                                    .one()
                                                    .switchIfEmpty(
                                                        Mono.error(
                                                            failure(
                                                                DomainStatus.ABORTED,
                                                                "Idempotency claim unavailable")))
                                                    .flatMap(
                                                        row -> {
                                                          if (!hash.equals(row.get("request_hash")))
                                                            return Mono.error(
                                                                failure(
                                                                    DomainStatus.ALREADY_EXISTS,
                                                                    "IDEMPOTENCY_CONFLICT"));
                                                          if ("SUCCEEDED".equals(row.get("state")))
                                                            return Mono.just(
                                                                decode(
                                                                    row.get("response")
                                                                        .toString()));
                                                          if (inserted != 1L)
                                                            return Mono.error(
                                                                failure(
                                                                    DomainStatus.ABORTED,
                                                                    "Incomplete idempotency"
                                                                        + " claim"));
                                                          return quota
                                                              .allocate(
                                                                  db,
                                                                  command.projectId(),
                                                                  allowBusinessBypass)
                                                              .flatMap(
                                                                  number ->
                                                                      validation
                                                                          .apply(command)
                                                                          .then(
                                                                              persist(
                                                                                  key, command,
                                                                                  number, now)));
                                                        })));
                      })));
    }

    private Mono<Created> persist(String key, CreateIssue c, int number, OffsetDateTime now) {
      UUID id = UUID.randomUUID();
      var result = new Created(id, c.projectId(), number, c.projectKey() + "-" + number, 1);
      var q =
          db.sql(
                  """
INSERT INTO taska.issues(tenant_id,id,project_id,issue_number,issue_key,issue_type,summary,description,status_key,priority,
  assignee_id,reporter_id,created_at,updated_at,version,story_points,start_date,due_date,original_estimate_minutes,remaining_estimate_minutes)
VALUES(:tenant,:id,:project,:number,:key,:type,:summary,:description,:status,:priority,:assignee,:reporter,:now,:now,1,:points,:start,:due,:original,:remaining)
""")
              .bind("tenant", tenant)
              .bind("id", id)
              .bind("project", c.projectId())
              .bind("number", number)
              .bind("key", result.key())
              .bind("type", c.issueType())
              .bind("summary", c.summary())
              .bind("status", c.statusKey())
              .bind("priority", c.priority())
              .bind("reporter", UUID.fromString(actor))
              .bind("now", now);
      q = nullable(q, "description", c.description(), String.class);
      q = nullable(q, "assignee", c.assigneeId(), UUID.class);
      q = nullable(q, "points", c.storyPoints(), BigDecimal.class);
      q = nullable(q, "start", c.startDate(), LocalDate.class);
      q = nullable(q, "due", c.dueDate(), LocalDate.class);
      q = nullable(q, "original", c.originalEstimateMinutes(), Integer.class);
      q = nullable(q, "remaining", c.remainingEstimateMinutes(), Integer.class);
      // События содержат только метаданные: названия и описания не попадают в outbox и историю.
      String payload =
          json.writeValueAsString(
              json.createObjectNode()
                  .put("issueId", id.toString())
                  .put("projectId", c.projectId().toString())
                  .put("issueKey", result.key())
                  .put("statusKey", c.statusKey())
                  .put("version", 1));
      return q.fetch()
          .rowsUpdated()
          .then(
              db.sql(
                      "INSERT INTO"
                          + " taska.issue_history(tenant_id,issue_id,event_type,actor_user_id,occurred_at,payload)"
                          + " VALUES(:tenant,:id,'CREATED',:actor,:now,CAST(:payload AS jsonb))")
                  .bind("tenant", tenant)
                  .bind("id", id)
                  .bind("actor", UUID.fromString(actor))
                  .bind("now", now)
                  .bind("payload", payload)
                  .fetch()
                  .rowsUpdated())
          .then(
              db.sql(
                      """
INSERT INTO taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload,scope_kind,aggregate_version,schema_version,request_id)
VALUES(:tenant,'ISSUE',:id,'CREATED',CAST(:payload AS jsonb),'TENANT',1,1,:request)
""")
                  .bind("tenant", tenant)
                  .bind("id", id)
                  .bind("payload", payload)
                  .bind("request", requestId)
                  .fetch()
                  .rowsUpdated())
          .then(
              db.sql(
                      """
UPDATE taska.idempotency_keys SET state='SUCCEEDED',resource_id=:id,response=CAST(:response AS jsonb)
WHERE tenant_id=:tenant AND user_id=:actor AND operation_code=:op AND key=:key AND state='PENDING'
""")
                  .bind("tenant", tenant)
                  .bind("actor", UUID.fromString(actor))
                  .bind("op", operation)
                  .bind("key", key)
                  .bind("id", id)
                  .bind("response", encode(result))
                  .fetch()
                  .rowsUpdated()
                  .doOnNext(
                      n -> {
                        if (n != 1) throw failure(DomainStatus.ABORTED, "Idempotency claim lost");
                      }))
          .thenReturn(result);
    }

    private String hash(String key, CreateIssue c) {
      var n =
          json.createObjectNode()
              .put("tenant", tenant.toString())
              .put("actor", actor)
              .put("operation", operation)
              .put("key", key)
              .put("projectId", c.projectId().toString())
              .put("projectKey", c.projectKey())
              .put("issueType", c.issueType())
              .put("summary", c.summary())
              .put("description", c.description())
              .put("statusKey", c.statusKey())
              .put("priority", c.priority())
              .put("assigneeId", Objects.toString(c.assigneeId(), null))
              .put("storyPoints", c.storyPoints() == null ? null : c.storyPoints().toPlainString())
              .put("startDate", Objects.toString(c.startDate(), null))
              .put("dueDate", Objects.toString(c.dueDate(), null));
      if (c.originalEstimateMinutes() == null) n.putNull("originalEstimateMinutes");
      else n.put("originalEstimateMinutes", c.originalEstimateMinutes());
      if (c.remainingEstimateMinutes() == null) n.putNull("remainingEstimateMinutes");
      else n.put("remainingEstimateMinutes", c.remainingEstimateMinutes());
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(n).getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static DatabaseClient.GenericExecuteSpec nullable(
      DatabaseClient.GenericExecuteSpec q, String name, Object value, Class<?> type) {
    return value == null ? q.bindNull(name, type) : q.bind(name, value);
  }

  private static DomainException invalid() {
    return failure(DomainStatus.INVALID_ARGUMENT, "Invalid issue storage command");
  }

  private static CreateIssue canonical(CreateIssue c) {
    if (c == null
        || c.projectId() == null
        || c.projectId().equals(new UUID(0, 0))
        || c.projectKey() == null
        || !c.projectKey().matches("[A-Z][A-Z0-9]{1,15}")
        || c.issueType() == null
        || !c.issueType().matches("[A-Z][A-Z0-9_]{1,31}")
        || c.summary() == null
        || c.summary().isBlank()
        || c.summary().length() > 1000
        || c.statusKey() == null
        || !c.statusKey().matches("[A-Z][A-Z0-9_]{1,63}")
        || !List.of("LOW", "MEDIUM", "HIGH").contains(c.priority() == null ? "" : c.priority())
        || (c.originalEstimateMinutes() != null && c.originalEstimateMinutes() < 0)
        || (c.remainingEstimateMinutes() != null && c.remainingEstimateMinutes() < 0)
        || (c.startDate() != null && c.dueDate() != null && c.startDate().isAfter(c.dueDate())))
      throw invalid();
    BigDecimal points = c.storyPoints();
    if (points != null)
      try {
        points = points.setScale(2, java.math.RoundingMode.UNNECESSARY);
        if (points.signum() < 0 || points.precision() > 5) throw invalid();
      } catch (ArithmeticException e) {
        throw invalid();
      }
    return new CreateIssue(
        c.projectId(),
        c.projectKey(),
        c.issueType(),
        c.summary(),
        c.description(),
        c.statusKey(),
        c.priority(),
        c.assigneeId(),
        points,
        c.startDate(),
        c.dueDate(),
        c.originalEstimateMinutes(),
        c.remainingEstimateMinutes());
  }

  private String encode(Created r) {
    return json.writeValueAsString(
        json.createObjectNode()
            .put("id", r.id().toString())
            .put("projectId", r.projectId().toString())
            .put("number", r.number())
            .put("key", r.key())
            .put("version", r.version()));
  }

  private Created decode(String response) {
    var n = json.readTree(response);
    return new Created(
        UUID.fromString(n.path("id").asText()),
        UUID.fromString(n.path("projectId").asText()),
        n.path("number").asInt(),
        n.path("key").asText(),
        n.path("version").asInt());
  }
}
