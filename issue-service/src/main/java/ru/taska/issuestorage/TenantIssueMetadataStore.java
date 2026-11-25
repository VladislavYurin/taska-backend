package ru.taska.issuestorage;

import io.r2dbc.spi.Row;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.ObjectMapper;

/** Только scoped SQL: сетевые проверки завершены до транзакции, эффекты сохраняются вместе. */
public final class TenantIssueMetadataStore {
  public record Issue(UUID id, UUID project, long version) {}

  public record Label(
      UUID id,
      UUID project,
      String name,
      String color,
      UUID creator,
      OffsetDateTime created,
      OffsetDateTime deleted) {}

  public record Assignment(UUID issue, UUID label, UUID creator, OffsetDateTime created) {}

  public record Watcher(
      UUID id, UUID project, UUID issue, UUID user, UUID creator, OffsetDateTime created) {}

  private final TenantTransactionExecutor tx;
  private final ObjectMapper json;
  private final Clock clock;

  public TenantIssueMetadataStore(TenantTransactionExecutor tx, ObjectMapper json, Clock clock) {
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  private <T> Mono<T> read(Function<Session, Mono<T>> work) {
    return VerifiedTenantContext.current()
        .flatMap(c -> tx.execute(db -> work.apply(new Session(db, c))));
  }

  private <T> Mono<T> allowed(TenantIssueAccess.Permit p, Function<Session, Mono<T>> work) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              p.check(UUID.fromString(c.tenantId()), c.actorId(), c.action(), p.project());
              return tx.execute(
                  db -> {
                    p.check(UUID.fromString(c.tenantId()), c.actorId(), c.action(), p.project());
                    var s = new Session(db, c);
                    return work.apply(s).flatMap(value -> s.audit(p).thenReturn(value));
                  });
            });
  }

  public Mono<Issue> issue(UUID id) {
    return read(s -> s.issue(id, false));
  }

  public Mono<List<Label>> projectLabels(TenantIssueAccess.Permit p) {
    return allowed(
        p,
        s ->
            s.db
                .sql(
                    "SELECT * FROM taska.project_labels WHERE tenant_id=:t AND project_id=:p AND"
                        + " deleted_at IS NULL ORDER BY name,id")
                .bind("t", s.tenant)
                .bind("p", p.project())
                .map((r, m) -> label(r))
                .all()
                .collectList());
  }

  public Mono<Label> createLabel(TenantIssueAccess.Permit p, String name, String color) {
    return allowed(
        p,
        s ->
            s.db
                .sql(
                    "INSERT INTO taska.project_labels(tenant_id,project_id,name,color,created_by)"
                        + " VALUES(:t,:p,:n,:c,:a) RETURNING *")
                .bind("t", s.tenant)
                .bind("p", p.project())
                .bind("n", name)
                .bind("c", color)
                .bind("a", s.actor)
                .map((r, m) -> label(r))
                .one());
  }

  public Mono<Label> updateLabel(TenantIssueAccess.Permit p, UUID id, String name, String color) {
    return allowed(
        p,
        s ->
            s.label(id, p.project(), false, true)
                .flatMap(
                    old ->
                        s.db
                            .sql(
                                "UPDATE taska.project_labels SET name=:n,color=:c,updated_at=:now"
                                    + " WHERE tenant_id=:t AND project_id=:p AND id=:id RETURNING"
                                    + " *")
                            .bind("n", name)
                            .bind("c", color)
                            .bind("now", OffsetDateTime.now(clock))
                            .bind("t", s.tenant)
                            .bind("p", p.project())
                            .bind("id", id)
                            .map((r, m) -> label(r))
                            .one()));
  }

  public Mono<Label> deleteLabel(TenantIssueAccess.Permit p, UUID id) {
    return allowed(
        p,
        s ->
            s.label(id, p.project(), true, true)
                .flatMap(
                    old ->
                        old.deleted() != null
                            ? Mono.just(old)
                            : s.db
                                .sql(
                                    "UPDATE taska.project_labels SET"
                                        + " deleted_at=:now,updated_at=:now WHERE tenant_id=:t AND"
                                        + " project_id=:p AND id=:id RETURNING *")
                                .bind("now", OffsetDateTime.now(clock))
                                .bind("t", s.tenant)
                                .bind("p", p.project())
                                .bind("id", id)
                                .map((r, m) -> label(r))
                                .one()));
  }

  public Mono<List<Label>> issueLabels(TenantIssueAccess.Permit p, UUID issue) {
    return allowed(
        p,
        s ->
            s.issue(issue, false)
                .filter(i -> i.project().equals(p.project()))
                .switchIfEmpty(Mono.error(missing()))
                .thenMany(
                    s.db
                        .sql(
                            "SELECT l.* FROM taska.issue_labels a JOIN taska.project_labels l ON"
                                + " l.tenant_id=a.tenant_id AND l.project_id=a.project_id AND"
                                + " l.id=a.label_id WHERE a.tenant_id=:t AND a.project_id=:p AND"
                                + " a.issue_id=:i AND l.deleted_at IS NULL ORDER BY l.name,l.id")
                        .bind("t", s.tenant)
                        .bind("p", p.project())
                        .bind("i", issue)
                        .map((r, m) -> label(r))
                        .all())
                .collectList());
  }

  public Mono<Assignment> attach(TenantIssueAccess.Permit p, UUID issue, UUID label) {
    return allowed(
        p,
        s ->
            s.label(label, p.project(), false, true)
                .then(s.issue(issue, true))
                .flatMap(
                    i -> {
                      if (!i.project().equals(p.project())) return Mono.error(missing());
                      return s.db
                          .sql(
                              "INSERT INTO"
                                  + " taska.issue_labels(tenant_id,project_id,issue_id,label_id,created_by)"
                                  + " VALUES(:t,:p,:i,:l,:a) ON CONFLICT DO NOTHING RETURNING *")
                          .bind("t", s.tenant)
                          .bind("p", p.project())
                          .bind("i", issue)
                          .bind("l", label)
                          .bind("a", s.actor)
                          .map((r, m) -> assignment(r))
                          .one()
                          .flatMap(
                              a -> s.effect(i, "LABEL_ADDED", "LABEL_ADDED", label).thenReturn(a))
                          .switchIfEmpty(
                              Mono.defer(
                                  () ->
                                      s.db
                                          .sql(
                                              "SELECT * FROM taska.issue_labels WHERE tenant_id=:t"
                                                  + " AND project_id=:p AND issue_id=:i AND"
                                                  + " label_id=:l")
                                          .bind("t", s.tenant)
                                          .bind("p", p.project())
                                          .bind("i", issue)
                                          .bind("l", label)
                                          .map((r, m) -> assignment(r))
                                          .one()));
                    }));
  }

  public Mono<Boolean> detach(TenantIssueAccess.Permit p, UUID issue, UUID label) {
    return allowed(
        p,
        s ->
            s.label(label, p.project(), true, true)
                .then(s.issue(issue, true))
                .flatMap(
                    i -> {
                      if (!i.project().equals(p.project())) return Mono.error(missing());
                      return s.db
                          .sql(
                              "DELETE FROM taska.issue_labels WHERE tenant_id=:t AND project_id=:p"
                                  + " AND issue_id=:i AND label_id=:l")
                          .bind("t", s.tenant)
                          .bind("p", p.project())
                          .bind("i", issue)
                          .bind("l", label)
                          .fetch()
                          .rowsUpdated()
                          .flatMap(
                              n ->
                                  n == 0
                                      ? Mono.just(false)
                                      : s.effect(i, "LABEL_REMOVED", "LABEL_REMOVED", label)
                                          .thenReturn(true));
                    }));
  }

  public Mono<Watcher> watch(TenantIssueAccess.Permit p, UUID issue, UUID user) {
    return allowed(
        p,
        s ->
            s.issue(issue, true)
                .flatMap(
                    i -> {
                      if (!i.project().equals(p.project())) return Mono.error(missing());
                      return s.db
                          .sql(
                              "INSERT INTO"
                                  + " taska.issue_watchers(tenant_id,project_id,issue_id,user_id,created_by)"
                                  + " VALUES(:t,:p,:i,:u,:a) ON CONFLICT DO NOTHING RETURNING *")
                          .bind("t", s.tenant)
                          .bind("p", p.project())
                          .bind("i", issue)
                          .bind("u", user)
                          .bind("a", s.actor)
                          .map((r, m) -> watcher(r))
                          .one()
                          .flatMap(w -> s.effect(i, "WATCHED", "UPDATED", user).thenReturn(w))
                          .switchIfEmpty(
                              Mono.defer(
                                  () ->
                                      s.db
                                          .sql(
                                              "SELECT * FROM taska.issue_watchers WHERE"
                                                  + " tenant_id=:t AND project_id=:p AND"
                                                  + " issue_id=:i AND user_id=:u")
                                          .bind("t", s.tenant)
                                          .bind("p", p.project())
                                          .bind("i", issue)
                                          .bind("u", user)
                                          .map((r, m) -> watcher(r))
                                          .one()));
                    }));
  }

  public Mono<Boolean> unwatch(TenantIssueAccess.Permit p, UUID issue, UUID user) {
    return allowed(
        p,
        s ->
            s.issue(issue, true)
                .flatMap(
                    i -> {
                      if (!i.project().equals(p.project())) return Mono.error(missing());
                      return s.db
                          .sql(
                              "DELETE FROM taska.issue_watchers WHERE tenant_id=:t AND"
                                  + " project_id=:p AND issue_id=:i AND user_id=:u")
                          .bind("t", s.tenant)
                          .bind("p", p.project())
                          .bind("i", issue)
                          .bind("u", user)
                          .fetch()
                          .rowsUpdated()
                          .flatMap(
                              n ->
                                  n == 0
                                      ? Mono.just(false)
                                      : s.effect(i, "UNWATCHED", "UPDATED", user).thenReturn(true));
                    }));
  }

  public Mono<List<Watcher>> watchers(TenantIssueAccess.Permit p, UUID issue) {
    return allowed(
        p,
        s ->
            s.issue(issue, false)
                .filter(i -> i.project().equals(p.project()))
                .switchIfEmpty(Mono.error(missing()))
                .thenMany(
                    s.db
                        .sql(
                            "SELECT * FROM taska.issue_watchers WHERE tenant_id=:t AND"
                                + " project_id=:p AND issue_id=:i ORDER BY created_at DESC,id DESC")
                        .bind("t", s.tenant)
                        .bind("p", p.project())
                        .bind("i", issue)
                        .map((r, m) -> watcher(r))
                        .all())
                .collectList());
  }

  public Mono<List<Watcher>> candidates(UUID issue, UUID after, int limit) {
    if (limit < 1 || limit > 100)
      return Mono.error(
          new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_RECIPIENT_PAGE"));
    return read(
        s ->
            s.issue(issue, false)
                .thenMany(
                    s.db
                        .sql(
                            "SELECT * FROM taska.issue_watchers WHERE tenant_id=:t AND issue_id=:i"
                                + " AND (:first OR user_id>:after) ORDER BY user_id LIMIT :n")
                        .bind("t", s.tenant)
                        .bind("i", issue)
                        .bind("after", after == null ? new UUID(0, 0) : after)
                        .bind("first", after == null)
                        .bind("n", limit)
                        .map((r, m) -> watcher(r))
                        .all())
                .collectList());
  }

  private final class Session {
    final DatabaseClient db;
    final UUID tenant, actor;
    final VerifiedTenantContext context;

    Session(DatabaseClient db, VerifiedTenantContext c) {
      this.db = db;
      context = c;
      tenant = UUID.fromString(c.tenantId());
      actor = c.actorId() == null ? null : UUID.fromString(c.actorId());
    }

    Mono<Issue> issue(UUID id, boolean lock) {
      return db.sql(
              "SELECT id,project_id,version FROM taska.issues WHERE tenant_id=:t AND id=:i AND"
                  + " deleted_at IS NULL"
                  + (lock ? " FOR UPDATE" : ""))
          .bind("t", tenant)
          .bind("i", id)
          .map(
              (r, m) ->
                  new Issue(
                      id,
                      r.get("project_id", UUID.class),
                      r.get("version", Integer.class).longValue()))
          .one()
          .switchIfEmpty(Mono.error(missing()));
    }

    Mono<Label> label(UUID id, UUID project, boolean deleted, boolean lock) {
      return db.sql(
              "SELECT * FROM taska.project_labels WHERE tenant_id=:t AND project_id=:p AND id=:id"
                  + (deleted ? "" : " AND deleted_at IS NULL")
                  + (lock ? " FOR UPDATE" : ""))
          .bind("t", tenant)
          .bind("p", project)
          .bind("id", id)
          .map((r, m) -> TenantIssueMetadataStore.label(r))
          .one()
          .switchIfEmpty(Mono.error(missing()));
    }

    Mono<Void> effect(Issue issue, String event, String history, UUID resource) {
      String payload =
          json.writeValueAsString(
              json.createObjectNode()
                  .put("issueId", issue.id().toString())
                  .put("projectId", issue.project().toString())
                  .put(event.startsWith("LABEL") ? "labelId" : "watcherUserId", resource.toString())
                  .put("actorUserId", actor.toString())
                  .put("operation", event)
                  .put("version", issue.version() + 1));
      OffsetDateTime now = OffsetDateTime.now(clock);
      return db.sql(
              "UPDATE taska.issues SET version=version+1,updated_at=:now WHERE tenant_id=:t AND"
                  + " id=:i AND version=:v")
          .bind("now", now)
          .bind("t", tenant)
          .bind("i", issue.id())
          .bind("v", Math.toIntExact(issue.version()))
          .fetch()
          .rowsUpdated()
          .flatMap(
              n ->
                  n == 1
                      ? Mono.empty()
                      : Mono.error(new DomainException(DomainStatus.ABORTED, "VERSION_CONFLICT")))
          .then(
              db.sql(
                      "INSERT INTO"
                          + " taska.issue_history(tenant_id,issue_id,event_type,actor_user_id,occurred_at,payload)"
                          + " VALUES(:t,:i,:e,:a,:now,CAST(:body AS jsonb))")
                  .bind("t", tenant)
                  .bind("i", issue.id())
                  .bind("e", history)
                  .bind("a", actor)
                  .bind("now", now)
                  .bind("body", payload)
                  .fetch()
                  .rowsUpdated())
          .then(
              db.sql(
                      "INSERT INTO"
                          + " taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload,scope_kind,aggregate_version,schema_version,request_id)"
                          + " VALUES(:t,'ISSUE',:i,:e,CAST(:body AS jsonb),'TENANT',:v,1,:r)")
                  .bind("t", tenant)
                  .bind("i", issue.id())
                  .bind("e", event)
                  .bind("body", payload)
                  .bind("v", issue.version() + 1)
                  .bind("r", context.requestId())
                  .fetch()
                  .rowsUpdated())
          .then();
    }

    Mono<Void> audit(TenantIssueAccess.Permit p) {
      if (!p.auditRequired()) return Mono.empty();
      return db.sql(
              "INSERT INTO"
                  + " taska.issue_authorization_audit(tenant_id,actor_id,project_id,action,request_id,bypassed)"
                  + " VALUES(:t,:a,:p,:op,:r,CAST(:b AS jsonb))")
          .bind("t", tenant)
          .bind("a", actor)
          .bind("p", p.project())
          .bind("op", context.action())
          .bind("r", context.requestId())
          .bind("b", json.writeValueAsString(p.bypassed()))
          .fetch()
          .rowsUpdated()
          .then();
    }
  }

  private static Label label(Row r) {
    return new Label(
        r.get("id", UUID.class),
        r.get("project_id", UUID.class),
        r.get("name", String.class),
        r.get("color", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("deleted_at", OffsetDateTime.class));
  }

  private static Assignment assignment(Row r) {
    return new Assignment(
        r.get("issue_id", UUID.class),
        r.get("label_id", UUID.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static Watcher watcher(Row r) {
    return new Watcher(
        r.get("id", UUID.class),
        r.get("project_id", UUID.class),
        r.get("issue_id", UUID.class),
        r.get("user_id", UUID.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static DomainException missing() {
    return new DomainException(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
  }
}
