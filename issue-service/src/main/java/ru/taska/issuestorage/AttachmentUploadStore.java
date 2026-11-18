package ru.taska.issuestorage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.StorageQuotaProtocol.Request;
import ru.taska.quota.StorageQuotaProtocol.Reservation;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Долговечное намерение: входные данные и обе версии фиксируются до следующего внешнего шага. */
public final class AttachmentUploadStore {
  public record Upload(
      UUID tenant,
      UUID actor,
      UUID id,
      UUID operation,
      UUID project,
      UUID issue,
      String mime,
      long bytes,
      String fileName,
      Instant expiresAt,
      Instant putUntil,
      String stagingKey,
      String finalKey,
      String stagingVersion,
      String finalVersion,
      boolean accepted,
      boolean confirmRequested,
      boolean copyStarted,
      Long maximum,
      Long policyVersion) {
    public Request request() {
      return new Request(operation, id, project, issue, bytes, expiresAt);
    }

    @Override
    public String toString() {
      return "Upload[id=" + id + ", accepted=" + accepted + "]";
    }
  }

  private final TenantTransactionExecutor tx;
  private final Clock clock;
  private final java.util.Set<String> allowedMimes;

  public AttachmentUploadStore(
      TenantTransactionExecutor tx, Clock clock, java.util.Set<String> allowedMimes) {
    if (allowedMimes == null || allowedMimes.isEmpty())
      throw new IllegalArgumentException("Allowed MIME configuration required");
    this.allowedMimes = java.util.Set.copyOf(allowedMimes);
    this.tx = tx;
    this.clock = clock;
  }

  public Mono<Upload> begin(
      UUID project, UUID issue, String mime, long bytes, String filename, String key) {
    if (key == null
        || key.isBlank()
        || key.length() > 128
        || bytes < 1
        || mime == null
        || !mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        || mime.length() > 255
        || !allowedMimes.contains(mime)
        || filename == null
        || filename.isBlank()
        || filename.length() > 255
        || filename.codePoints().anyMatch(Character::isISOControl))
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "INVALID_UPLOAD"));
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId()),
                  actor = UUID.fromString(c.actorId()),
                  id = UUID.randomUUID(),
                  op = UUID.randomUUID();
              var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
              var expires = now.plusSeconds(900);
              var until = now.plusSeconds(300);
              String prefix =
                  "tenant/" + tenant + "/project/" + project + "/issue/" + issue + "/" + id;
              String hash = hash(project + ":" + issue + ":" + mime + ":" + bytes + ":" + filename);
              return tx.execute(
                  db ->
                      db.sql(
                              "INSERT INTO"
                                  + " taska.attachment_uploads(id,tenant_id,actor_id,operation_id,project_id,issue_id,request_key,request_hash,content_type,declared_bytes,file_name,expires_at,put_not_after,staging_key,final_key)"
                                  + " VALUES(:id,:t,:a,:op,:p,:i,:key,:hash,:mime,:bytes,:file,:expires,:until,:stage,:final)"
                                  + " ON CONFLICT(tenant_id,actor_id,request_key) DO NOTHING")
                          .bind("id", id)
                          .bind("t", tenant)
                          .bind("a", actor)
                          .bind("op", op)
                          .bind("p", project)
                          .bind("i", issue)
                          .bind("key", key)
                          .bind("hash", hash)
                          .bind("mime", mime)
                          .bind("bytes", bytes)
                          .bind("file", filename)
                          .bind("expires", at(expires))
                          .bind("until", at(until))
                          .bind("stage", prefix + "/staging")
                          .bind("final", prefix + "/final")
                          .fetch()
                          .rowsUpdated()
                          .then(
                              db.sql(
                                      "SELECT * FROM taska.attachment_uploads WHERE tenant_id=:t"
                                          + " AND actor_id=:a AND request_key=:key")
                                  .bind("t", tenant)
                                  .bind("a", actor)
                                  .bind("key", key)
                                  .fetch()
                                  .one())
                          .map(
                              r -> {
                                if (!hash.equals(r.get("request_hash")))
                                  throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
                                return row(r);
                              }));
            });
  }

  public Mono<Upload> operation(UUID operation) {
    return select("operation_id", operation);
  }

  public Mono<Upload> staging(String key) {
    return select("staging_key", key);
  }

  private Mono<Upload> select(String column, Object key) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                tx.execute(
                    db ->
                        db.sql(
                                "SELECT * FROM taska.attachment_uploads WHERE tenant_id=:t AND "
                                    + column
                                    + "=:key")
                            .bind("t", UUID.fromString(c.tenantId()))
                            .bind("key", key)
                            .fetch()
                            .one()
                            .map(this::row)))
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "UPLOAD_NOT_FOUND")));
  }

  public Mono<Upload> accepted(Upload u, Reservation r) {
    if (!u.request().equals(r.request())
        || !u.tenant().equals(r.tenant())
        || !u.actor().equals(r.actor()))
      return Mono.error(fail(DomainStatus.DATA_LOSS, "UPLOAD_ADMISSION_MISMATCH"));
    return locked(
            u,
            (db, previous) ->
                db.sql(
                        "UPDATE taska.attachment_uploads SET"
                            + " accepted=true,maximum_attachment_bytes=:max,policy_version=:policy"
                            + " WHERE tenant_id=:t AND id=:id")
                    .bind("max", r.maximumAttachmentBytes())
                    .bind("policy", r.policyVersion())
                    .bind("t", u.tenant())
                    .bind("id", u.id())
                    .fetch()
                    .rowsUpdated()
                    .thenReturn(true))
        .then(operation(u.operation()));
  }

  @FunctionalInterface
  public interface LockedWork<T> {
    Mono<T> run(DatabaseClient db, Upload current);
  }

  public <T> Mono<T> locked(Upload u, LockedWork<T> work) {
    return tx.execute(
        db ->
            db.sql(
                    "SELECT * FROM taska.attachment_uploads WHERE tenant_id=:t AND id=:id FOR"
                        + " UPDATE")
                .bind("t", u.tenant())
                .bind("id", u.id())
                .fetch()
                .one()
                .map(this::row)
                .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "UPLOAD_NOT_FOUND")))
                .flatMap(row -> work.run(db, row)));
  }

  public Mono<String> pin(Upload u, boolean stage, String version) {
    if (version == null || version.isBlank() || version.equals("null"))
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "VERSION_REQUIRED"));
    return locked(
        u,
        (db, current) -> {
          String previous = stage ? current.stagingVersion() : current.finalVersion();
          if (previous != null) return Mono.just(previous);
          return db.sql(
                  "UPDATE taska.attachment_uploads SET "
                      + (stage ? "staging_version" : "final_version")
                      + "=:v WHERE tenant_id=:t AND id=:id")
              .bind("v", version)
              .bind("t", u.tenant())
              .bind("id", u.id())
              .fetch()
              .rowsUpdated()
              .thenReturn(version);
        });
  }

  public Mono<Boolean> claimCopy(Upload u) {
    return tx.execute(
        db ->
            db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .bind("key", u.tenant() + "/" + u.operation())
                .then()
                .then(
                    db.sql(
                            "SELECT * FROM taska.attachment_uploads WHERE tenant_id=:t AND id=:id"
                                + " FOR UPDATE")
                        .bind("t", u.tenant())
                        .bind("id", u.id())
                        .fetch()
                        .one()
                        .map(this::row))
                .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "UPLOAD_NOT_FOUND")))
                .flatMap(
                    current -> {
                      if (current.copyStarted()) return Mono.just(false);
                      return db.sql(
                              "SELECT state FROM taska.attachment_object_manifest WHERE"
                                  + " tenant_id=:t AND operation_id=:op")
                          .bind("t", u.tenant())
                          .bind("op", u.operation())
                          .map((r, m) -> r.get("state", String.class))
                          .one()
                          .filter(state -> state.equals("RESERVED") || state.equals("UPLOADED"))
                          .switchIfEmpty(
                              Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "UPLOAD_CLOSED")))
                          .then(
                              db.sql(
                                      "UPDATE taska.attachment_uploads SET copy_started=true WHERE"
                                          + " tenant_id=:t AND id=:id")
                                  .bind("t", u.tenant())
                                  .bind("id", u.id())
                                  .fetch()
                                  .rowsUpdated())
                          .thenReturn(true);
                    }));
  }

  public Mono<ru.taska.api.issue.attachment.v1.AttachmentResponse> result(Upload u) {
    return tx.execute(
            db ->
                db.sql(
                        "SELECT a.* FROM taska.issue_attachments a JOIN"
                            + " taska.attachment_object_manifest m ON m.tenant_id=a.tenant_id AND"
                            + " m.id=a.id WHERE a.tenant_id=:t AND a.id=:id AND"
                            + " m.state='CONFIRMED'")
                    .bind("t", u.tenant())
                    .bind("id", u.id())
                    .fetch()
                    .one()
                    .map(
                        r -> {
                          Instant created = ((OffsetDateTime) r.get("created_at")).toInstant();
                          return ru.taska.api.issue.attachment.v1.AttachmentResponse.newBuilder()
                              .setId(u.id().toString())
                              .setIssueId(u.issue().toString())
                              .setUploadedBy(u.actor().toString())
                              .setObjectKey(u.finalKey())
                              .setFileName((String) r.get("file_name"))
                              .setContentType((String) r.get("content_type"))
                              .setSizeBytes((Long) r.get("size_bytes"))
                              .setChecksum((String) r.get("checksum"))
                              .setCreatedAt(
                                  com.google.protobuf.Timestamp.newBuilder()
                                      .setSeconds(created.getEpochSecond())
                                      .setNanos(created.getNano()))
                              .build();
                        }))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ATTACHMENT_ACTIVATION_PENDING")));
  }

  public Mono<Void> requestConfirm(Upload u) {
    return locked(
        u,
        (db, current) ->
            db.sql(
                    "UPDATE taska.attachment_uploads SET confirm_requested=true WHERE tenant_id=:t"
                        + " AND id=:id")
                .bind("t", u.tenant())
                .bind("id", u.id())
                .fetch()
                .rowsUpdated()
                .then());
  }

  public Mono<java.util.List<Upload>> page(UUID after, int limit) {
    if (limit < 1 || limit > 100)
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "BOUNDED_RECOVERY_REQUIRED"));
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                tx.execute(
                    db -> {
                      var q =
                          db.sql(
                                  "SELECT * FROM taska.attachment_uploads WHERE tenant_id=:t"
                                      + (after == null ? "" : " AND id>:after")
                                      + " ORDER BY id LIMIT :limit")
                              .bind("t", UUID.fromString(c.tenantId()))
                              .bind("limit", limit);
                      if (after != null) q = q.bind("after", after);
                      return q.fetch().all().map(this::row).collectList();
                    }));
  }

  private Upload row(Map<String, Object> r) {
    return new Upload(
        (UUID) r.get("tenant_id"),
        (UUID) r.get("actor_id"),
        (UUID) r.get("id"),
        (UUID) r.get("operation_id"),
        (UUID) r.get("project_id"),
        (UUID) r.get("issue_id"),
        (String) r.get("content_type"),
        (Long) r.get("declared_bytes"),
        (String) r.get("file_name"),
        ((OffsetDateTime) r.get("expires_at")).toInstant(),
        ((OffsetDateTime) r.get("put_not_after")).toInstant(),
        (String) r.get("staging_key"),
        (String) r.get("final_key"),
        (String) r.get("staging_version"),
        (String) r.get("final_version"),
        Boolean.TRUE.equals(r.get("accepted")),
        Boolean.TRUE.equals(r.get("confirm_requested")),
        Boolean.TRUE.equals(r.get("copy_started")),
        (Long) r.get("maximum_attachment_bytes"),
        (Long) r.get("policy_version"));
  }

  private static String hash(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static OffsetDateTime at(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  static DomainException fail(DomainStatus s, String message) {
    return new DomainException(s, message);
  }
}
