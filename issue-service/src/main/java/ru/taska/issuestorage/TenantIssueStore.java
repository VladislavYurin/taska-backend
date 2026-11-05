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
 * Storage foundation only. TAS-343 must authorize project, workflow and nested IDs before calling.
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

  private final TenantTransactionExecutor transactions;
  private final ObjectMapper json;
  private final Clock clock;

  public TenantIssueStore(TenantTransactionExecutor transactions, ObjectMapper json, Clock clock) {
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
    return new DomainException(status, reason);
  }

  /**
   * Never retain this handle or subscribe detached work; its client is bound to the executor frame.
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
     * Claims receipt before allocating a number. All five tables commit together, including
     * retries.
     */
    public Mono<Created> create(String key, CreateIssue input) {
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
                        // A concurrent INSERT waits for the winner. The next SELECT uses a fresh
                        // READ COMMITTED snapshot.
                        return db.sql(
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
                                                    decode(row.get("response").toString()));
                                              if (inserted != 1L)
                                                return Mono.error(
                                                    failure(
                                                        DomainStatus.ABORTED,
                                                        "Incomplete idempotency claim"));
                                              return allocate(command.projectId())
                                                  .flatMap(
                                                      number -> persist(key, command, number, now));
                                            }));
                      })));
    }

    private Mono<Integer> allocate(UUID project) {
      return db.sql(
              """
              INSERT INTO taska.project_counters(tenant_id,project_id,next_issue_number) VALUES(:tenant,:project,1)
              ON CONFLICT(tenant_id,project_id) DO NOTHING
              """)
          .bind("tenant", tenant)
          .bind("project", project)
          .fetch()
          .rowsUpdated()
          .then(
              db.sql(
                      """
                      UPDATE taska.project_counters SET next_issue_number=next_issue_number+1,lifetime_created=lifetime_created+1,version=version+1
                      WHERE tenant_id=:tenant AND project_id=:project AND next_issue_number<2147483647 AND version<9223372036854775807 AND lifetime_created<9223372036854775807
                      RETURNING next_issue_number-1 AS allocated
                      """)
                  .bind("tenant", tenant)
                  .bind("project", project)
                  .map((r, m) -> r.get("allocated", Integer.class))
                  .one())
          .switchIfEmpty(
              Mono.error(failure(DomainStatus.RESOURCE_EXHAUSTED, "Issue numbering exhausted")));
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
      // Metadata-only events: summaries/descriptions never enter outbox/history payloads here.
      String payload =
          json.writeValueAsString(
              json.createObjectNode()
                  .put("issueId", id.toString())
                  .put("projectId", c.projectId().toString())
                  .put("issueKey", result.key())
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
        || !c.statusKey().matches("[A-Z][A-Z0-9_]{1,31}")
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
