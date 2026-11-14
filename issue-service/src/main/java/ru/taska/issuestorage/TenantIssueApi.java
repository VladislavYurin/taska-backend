package ru.taska.issuestorage;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.AssignIssueRequestBody;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest;
import ru.taska.api.issue.v1.CountProjectIssuesBatchInternalResponse;
import ru.taska.api.issue.v1.CreateIssueRequestBody;
import ru.taska.api.issue.v1.DeleteIssueRequestBody;
import ru.taska.api.issue.v1.GetIssueDetailsResponse;
import ru.taska.api.issue.v1.GetIssueRequestBody;
import ru.taska.api.issue.v1.GetProjectTaskQuotasRequest;
import ru.taska.api.issue.v1.GetProjectTaskQuotasResponse;
import ru.taska.api.issue.v1.IssueDetailsResponse;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.issue.v1.IssueWithHistoryResponse;
import ru.taska.api.issue.v1.PatchIssueRequestBody;
import ru.taska.api.issue.v1.TransitionIssueRequestBody;
import ru.taska.api.issue.v1.UpdateIssueRequestBody;
import ru.taska.capability.Action;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.issuestorage.TenantIssueStore.CreateIssue;
import ru.taska.issuestorage.TenantIssueStore.Snapshot;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Нативные бизнес-операции. Поля запроса не определяют арендатора, пользователя, права ACL или
 * полномочия workflow.
 */
public final class TenantIssueApi {
  private final TenantIssueStore store;
  private final TenantIssueAccess access;

  public TenantIssueApi(TenantIssueStore store, TenantIssueAccess access) {
    this.store = store;
    this.access = access;
  }

  public Mono<CountProjectIssuesBatchInternalResponse> counts(
      CountProjectIssuesBatchInternalRequest q) {
    if (q.getProjectIdsCount() > 100) return Mono.error(invalid());
    var ids = q.getProjectIdsList().stream().map(TenantIssueApi::uuid).distinct().toList();
    String type = q.hasIssueTypeKey() ? type(q.getIssueTypeKey()) : null;
    if (ids.isEmpty())
      return Mono.just(
          CountProjectIssuesBatchInternalResponse.newBuilder().setFiltersApplied(true).build());
    return access
        .readMany(ids)
        .flatMap(batch -> store.execute(s -> s.counts(batch, type, q.getIncludeDeleted())))
        .map(
            items ->
                CountProjectIssuesBatchInternalResponse.newBuilder()
                    .addAllItems(items)
                    .setFiltersApplied(true)
                    .build());
  }

  public Mono<GetProjectTaskQuotasResponse> quotas(GetProjectTaskQuotasRequest q) {
    if (q.getProjectIdsCount() > 100) return Mono.error(invalid());
    var ids = q.getProjectIdsList().stream().map(TenantIssueApi::uuid).distinct().toList();
    if (ids.isEmpty()) return Mono.just(GetProjectTaskQuotasResponse.getDefaultInstance());
    return access
        .readMany(ids)
        .flatMap(
            batch -> {
              if (!batch.permits().stream()
                  .map(TenantIssueAccess.Permit::project)
                  .toList()
                  .equals(ids))
                return Mono.error(
                    new DomainException(DomainStatus.UNAVAILABLE, "INVALID_BULK_AUTHORITY"));
              return store
                  .execute(s -> s.quotas(batch))
                  .map(
                      items ->
                          GetProjectTaskQuotasResponse.newBuilder().addAllItems(items).build());
            });
  }

  public Mono<Snapshot> create(CreateIssueRequestBody q) {
    UUID project = uuid(q.getProjectId());
    return actor(q.getReporterId())
        .then(access.authorize(Action.ISSUE_CREATE, project, null))
        .flatMap(
            permit -> store.execute(s -> s.replayCreate(q.getIdempotencyKey(), hash(q), permit)))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  String type =
                      type(q.hasIssueTypeKey() ? q.getIssueTypeKey() : legacy(q.getIssueType()));
                  return Mono.zip(
                          access.projectKey(project),
                          access.initialStatus(
                              project, type, q.hasStatusKey() ? q.getStatusKey() : null))
                      .flatMap(
                          values ->
                              access
                                  .authorize(Action.ISSUE_CREATE, project, null)
                                  .flatMap(
                                      permit -> {
                                        var command =
                                            new CreateIssue(
                                                project,
                                                values.getT1(),
                                                type,
                                                q.getSummary(),
                                                q.getDescription(),
                                                values.getT2(),
                                                priority(q.getPriority()),
                                                null,
                                                q.hasStoryPoints()
                                                    ? points(q.getStoryPoints())
                                                    : null,
                                                q.hasStartDate() ? date(q.getStartDate()) : null,
                                                q.hasDueDate() ? date(q.getDueDate()) : null,
                                                q.hasOriginalEstimateMinutes()
                                                    ? q.getOriginalEstimateMinutes()
                                                    : null,
                                                q.hasRemainingEstimateMinutes()
                                                    ? q.getRemainingEstimateMinutes()
                                                    : null);
                                        return store.execute(
                                            s ->
                                                s.create(
                                                        q.getIdempotencyKey(),
                                                        command,
                                                        c ->
                                                            access
                                                                .initialStatus(
                                                                    project,
                                                                    c.issueType(),
                                                                    c.statusKey())
                                                                .then(
                                                                    Mono.fromRunnable(
                                                                        () ->
                                                                            permit.check(
                                                                                permit.tenant(),
                                                                                permit
                                                                                    .actor()
                                                                                    .toString(),
                                                                                permit.operation(),
                                                                                project))),
                                                        permit.bypassBusinessRules())
                                                    .flatMap(
                                                        created ->
                                                            s.completeCreate(
                                                                q.getIdempotencyKey(),
                                                                hash(q),
                                                                created,
                                                                permit)));
                                      }));
                }));
  }

  public Mono<Snapshot> get(String id, String actor) {
    return actor(actor).then(read(uuid(id)));
  }

  public Mono<Snapshot> getByKey(String key, String actor) {
    return actor(actor)
        .then(store.execute(s -> s.findActiveByKey(key)))
        .switchIfEmpty(Mono.error(missing()))
        .flatMap(this::authorizedRead);
  }

  private Mono<Snapshot> read(UUID id) {
    return store
        .execute(s -> s.findActive(id))
        .switchIfEmpty(Mono.error(missing()))
        .flatMap(this::authorizedRead);
  }

  private Mono<Snapshot> authorizedRead(Snapshot row) {
    return access
        .authorize(Action.ISSUE_READ, row.identity().projectId(), row.identity().id())
        .flatMap(
            permit -> store.execute(s -> s.audit(permit, row.identity().id()).thenReturn(row)));
  }

  private record View(
      Snapshot issue, java.util.List<ru.taska.api.issue.v1.IssueHistoryResponse> history) {}

  public Mono<IssueWithHistoryResponse> getWithHistory(GetIssueRequestBody q) {
    return view(q)
        .map(
            v ->
                IssueWithHistoryResponse.newBuilder()
                    .setIssue(response(v.issue()))
                    .addAllHistory(v.history())
                    .build());
  }

  private Mono<View> view(GetIssueRequestBody q) {
    return get(q.getIssueId(), q.getActorUserId())
        .flatMap(
            row ->
                access
                    .authorize(
                        Action.ISSUE_HISTORY, row.identity().projectId(), row.identity().id())
                    .flatMap(
                        permit ->
                            store.execute(
                                session ->
                                    session
                                        .stored(row.identity().id(), true)
                                        .filter(current -> current.deletedAt() == null)
                                        .switchIfEmpty(Mono.error(missing()))
                                        .flatMap(
                                            current ->
                                                session
                                                    .history(current.identity().id(), permit)
                                                    .map(history -> new View(current, history))))));
  }

  public Mono<GetIssueDetailsResponse> details(GetIssueRequestBody q) {
    return view(q)
        .map(
            value -> {
              var issue = response(value.issue());
              var b =
                  IssueDetailsResponse.newBuilder()
                      .setId(issue.getId())
                      .setProjectId(issue.getProjectId())
                      .setIssueNumber(issue.getIssueNumber())
                      .setIssueKey(issue.getIssueKey())
                      .setIssueType(issue.getIssueType())
                      .setIssueTypeKey(issue.getIssueTypeKey())
                      .setSummary(issue.getSummary())
                      .setStatus(issue.getStatusKey())
                      .setPriority(issue.getPriority())
                      .setReporterId(issue.getReporterId())
                      .setCreatedAt(issue.getCreatedAt())
                      .setUpdatedAt(issue.getUpdatedAt())
                      .setVersion(issue.getVersion())
                      .setTimeSpentMinutes(issue.getTimeSpentMinutes());
              if (value.issue().description() != null)
                b.setDescription(value.issue().description());
              if (!issue.getAssigneeId().isEmpty()) b.setAssigneeId(issue.getAssigneeId());
              if (issue.hasStoryPoints()) b.setStoryPoints(issue.getStoryPoints());
              if (issue.hasStartDate()) b.setStartDate(issue.getStartDate());
              if (issue.hasDueDate()) b.setDueDate(issue.getDueDate());
              if (issue.hasOriginalEstimateMinutes())
                b.setOriginalEstimateMinutes(issue.getOriginalEstimateMinutes());
              if (issue.hasRemainingEstimateMinutes())
                b.setRemainingEstimateMinutes(issue.getRemainingEstimateMinutes());
              // Комментарии, подписки, связи и файлы активируются отдельными задачами.
              // Нельзя обогащать авторов через глобальный каталог без контекста арендатора
              // или создавать вымышленные профили удалённых пользователей.
              return GetIssueDetailsResponse.newBuilder()
                  .setIssue(b)
                  .addAllHistory(value.history())
                  .build();
            });
  }

  public Mono<Snapshot> patch(PatchIssueRequestBody q, String key) {
    return actor(q.getActorUserId())
        .then(
            q.hasAssigneeId()
                ? store
                    .execute(s -> s.stored(uuid(q.getIssueId()), false))
                    .switchIfEmpty(Mono.error(missing()))
                    .flatMap(
                        row ->
                            access.authorize(
                                Action.ISSUE_ASSIGN,
                                row.identity().projectId(),
                                row.identity().id()))
                    .then()
                : Mono.empty())
        .then(
            mutate(
                uuid(q.getIssueId()),
                q.getVersion(),
                key,
                hash(q),
                Action.ISSUE_UPDATE,
                "UPDATED",
                false,
                row -> {
                  if (q.hasIssueTypeKey() && !q.getIssueTypeKey().equals(row.issueType()))
                    return Mono.error(
                        new DomainException(DomainStatus.UNAVAILABLE, "NATIVE_TYPE_MOVE_REQUIRED"));
                  UUID assignee =
                      q.hasAssigneeId()
                          ? (q.getAssigneeId().getIsNull()
                              ? null
                              : uuid(q.getAssigneeId().getValue()))
                          : row.assigneeId();
                  Mono<Void> canAssign =
                      q.hasAssigneeId()
                          ? access
                              .authorize(
                                  Action.ISSUE_ASSIGN,
                                  row.identity().projectId(),
                                  row.identity().id())
                              .then(access.assignee(row.identity().projectId(), assignee))
                          : Mono.empty();
                  return canAssign.thenReturn(
                      new CreateIssue(
                          row.identity().projectId(),
                          projectKey(row),
                          row.issueType(),
                          q.hasSummary() ? q.getSummary() : row.summary(),
                          q.hasDescription()
                              ? (q.getDescription().getIsNull()
                                  ? null
                                  : q.getDescription().getValue())
                              : row.description(),
                          row.statusKey(),
                          q.hasPriority() ? priority(q.getPriority()) : row.priority(),
                          assignee,
                          q.hasStoryPoints()
                              ? (q.getStoryPoints().getIsNull()
                                  ? null
                                  : points(q.getStoryPoints().getValue()))
                              : row.storyPoints(),
                          q.hasStartDate()
                              ? (q.getStartDate().getIsNull()
                                  ? null
                                  : date(q.getStartDate().getValue()))
                              : row.startDate(),
                          q.hasDueDate()
                              ? (q.getDueDate().getIsNull()
                                  ? null
                                  : date(q.getDueDate().getValue()))
                              : row.dueDate(),
                          q.hasOriginalEstimateMinutes()
                              ? (q.getOriginalEstimateMinutes().getIsNull()
                                  ? null
                                  : q.getOriginalEstimateMinutes().getValue())
                              : row.originalEstimateMinutes(),
                          q.hasRemainingEstimateMinutes()
                              ? (q.getRemainingEstimateMinutes().getIsNull()
                                  ? null
                                  : q.getRemainingEstimateMinutes().getValue())
                              : row.remainingEstimateMinutes()));
                }));
  }

  public Mono<Snapshot> update(UpdateIssueRequestBody q, String key) {
    return actor(q.getActorUserId())
        .then(
            mutate(
                uuid(q.getIssueId()),
                null,
                key,
                hash(q),
                Action.ISSUE_UPDATE,
                "UPDATED",
                false,
                row ->
                    Mono.just(
                        new CreateIssue(
                            row.identity().projectId(),
                            projectKey(row),
                            row.issueType(),
                            q.getSummary(),
                            q.getDescription(),
                            row.statusKey(),
                            priority(q.getPriority()),
                            row.assigneeId(),
                            q.hasStoryPoints() ? points(q.getStoryPoints()) : row.storyPoints(),
                            q.hasStartDate() ? date(q.getStartDate()) : row.startDate(),
                            q.hasDueDate() ? date(q.getDueDate()) : row.dueDate(),
                            q.hasOriginalEstimateMinutes()
                                ? q.getOriginalEstimateMinutes()
                                : row.originalEstimateMinutes(),
                            q.hasRemainingEstimateMinutes()
                                ? q.getRemainingEstimateMinutes()
                                : row.remainingEstimateMinutes()))));
  }

  public Mono<Snapshot> assign(AssignIssueRequestBody q, String key) {
    return actor(q.getActorUserId())
        .then(
            mutate(
                uuid(q.getIssueId()),
                null,
                key,
                hash(q),
                Action.ISSUE_ASSIGN,
                "ASSIGNED",
                false,
                row -> {
                  UUID user = q.getAssigneeId().isBlank() ? null : uuid(q.getAssigneeId());
                  return access
                      .assignee(row.identity().projectId(), user)
                      .thenReturn(fields(row, user, row.statusKey()));
                }));
  }

  public Mono<Snapshot> transition(TransitionIssueRequestBody q, String key) {
    return actor(q.getActorUserId())
        .then(
            mutate(
                uuid(q.getIssueId()),
                q.hasExpectedVersion() ? q.getExpectedVersion() : null,
                key,
                hash(q),
                Action.ISSUE_TRANSITION,
                "TRANSITIONED",
                false,
                row ->
                    access
                        .transition(row, uuid(q.getTransitionId()), q.getPayload())
                        .map(status -> fields(row, row.assigneeId(), status))));
  }

  public Mono<Snapshot> delete(DeleteIssueRequestBody q, String key) {
    return actor(q.getActorUserId())
        .then(
            mutate(
                uuid(q.getIssueId()),
                null,
                key,
                hash(q),
                Action.ISSUE_DELETE,
                "DELETED",
                true,
                row -> Mono.just(fields(row, row.assigneeId(), row.statusKey()))));
  }

  private Mono<Snapshot> mutate(
      UUID id,
      Integer expected,
      String key,
      String hash,
      Action action,
      String event,
      boolean delete,
      Function<Snapshot, Mono<CreateIssue>> command) {
    if (expected != null && expected < 1) return Mono.error(invalid());
    return store
        .execute(s -> s.stored(id, false))
        .switchIfEmpty(Mono.error(missing()))
        .flatMap(
            before ->
                access
                    .authorize(action, before.identity().projectId(), id)
                    .flatMap(
                        permit ->
                            store
                                .execute(s -> s.replay(key, hash, id, event, permit))
                                .switchIfEmpty(
                                    Mono.defer(
                                        () -> {
                                          if (before.deletedAt() != null)
                                            return Mono.error(missing());
                                          int version =
                                              expected == null
                                                  ? before.identity().version()
                                                  : expected;
                                          if (version != before.identity().version())
                                            return Mono.error(
                                                new DomainException(
                                                    DomainStatus.ABORTED,
                                                    "VERSION_CONFLICT",
                                                    java.util.Map.of("code", "VERSION_CONFLICT")));
                                          return access
                                              .authorize(action, before.identity().projectId(), id)
                                              .flatMap(
                                                  current ->
                                                      store.execute(
                                                          s ->
                                                              s.mutate(
                                                                  key, hash, id, version, event,
                                                                  delete, command, current)));
                                        }))));
  }

  static CreateIssue fields(Snapshot r, UUID assignee, String status) {
    return new CreateIssue(
        r.identity().projectId(),
        projectKey(r),
        r.issueType(),
        r.summary(),
        r.description(),
        status,
        r.priority(),
        assignee,
        r.storyPoints(),
        r.startDate(),
        r.dueDate(),
        r.originalEstimateMinutes(),
        r.remainingEstimateMinutes());
  }

  static Mono<Void> actor(String supplied) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> new DomainException(DomainStatus.UNAUTHENTICATED, "Verified context required"))
        .flatMap(
            c ->
                c.actorId() != null && c.actorId().equals(supplied)
                    ? Mono.empty()
                    : Mono.error(
                        new DomainException(DomainStatus.PERMISSION_DENIED, "ACTOR_MISMATCH")));
  }

  static UUID uuid(String id) {
    try {
      var v = UUID.fromString(id);
      if (v.equals(new UUID(0, 0)) || !v.toString().equals(id))
        throw new IllegalArgumentException();
      return v;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  static String type(String s) {
    if (!s.matches("[A-Z][A-Z0-9_]{1,31}")) throw invalid();
    return s;
  }

  static String legacy(IssueType t) {
    return switch (t) {
      case ISSUE_TYPE_TASK -> "TASK";
      case ISSUE_TYPE_BUG -> "BUG";
      case ISSUE_TYPE_STORY -> "STORY";
      default -> throw invalid();
    };
  }

  static String priority(IssuePriority p) {
    return switch (p) {
      case ISSUE_PRIORITY_LOW -> "LOW";
      case ISSUE_PRIORITY_MEDIUM -> "MEDIUM";
      case ISSUE_PRIORITY_HIGH -> "HIGH";
      default -> throw invalid();
    };
  }

  static BigDecimal points(double d) {
    if (!Double.isFinite(d)) throw invalid();
    return BigDecimal.valueOf(d);
  }

  static LocalDate date(String d) {
    try {
      return LocalDate.parse(d);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  static String projectKey(Snapshot r) {
    return r.identity().key().substring(0, r.identity().key().lastIndexOf('-'));
  }

  static DomainException missing() {
    return new DomainException(DomainStatus.NOT_FOUND, "ISSUE_NOT_FOUND");
  }

  static DomainException invalid() {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_ISSUE_COMMAND");
  }

  static String hash(MessageLite m) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(m.toByteArray()));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Timestamp timestamp(OffsetDateTime v) {
    return Timestamp.newBuilder().setSeconds(v.toEpochSecond()).setNanos(v.getNano()).build();
  }

  public static IssueResponse response(Snapshot r) {
    var b =
        IssueResponse.newBuilder()
            .setId(r.identity().id().toString())
            .setProjectId(r.identity().projectId().toString())
            .setIssueNumber(r.identity().number())
            .setIssueKey(r.identity().key())
            .setVersion(r.identity().version())
            .setIssueTypeKey(r.issueType())
            .setSummary(r.summary())
            .setStatusKey(r.statusKey())
            .setPriority(IssuePriority.valueOf("ISSUE_PRIORITY_" + r.priority()))
            .setReporterId(r.reporterId().toString())
            .setTimeSpentMinutes(r.timeSpentMinutes())
            .setCreatedAt(timestamp(r.createdAt()))
            .setUpdatedAt(timestamp(r.updatedAt()));
    try {
      b.setIssueType(IssueType.valueOf("ISSUE_TYPE_" + r.issueType()));
    } catch (IllegalArgumentException ignored) {
    }
    if (r.description() != null) b.setDescription(r.description());
    if (r.assigneeId() != null) b.setAssigneeId(r.assigneeId().toString());
    if (r.storyPoints() != null) b.setStoryPoints(r.storyPoints().doubleValue());
    if (r.startDate() != null) b.setStartDate(r.startDate().toString());
    if (r.dueDate() != null) b.setDueDate(r.dueDate().toString());
    if (r.originalEstimateMinutes() != null)
      b.setOriginalEstimateMinutes(r.originalEstimateMinutes());
    if (r.remainingEstimateMinutes() != null)
      b.setRemainingEstimateMinutes(r.remainingEstimateMinutes());
    if (r.deletedAt() != null) b.setDeletedAt(timestamp(r.deletedAt()));
    return b.build();
  }
}
