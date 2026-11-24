package ru.taska.issuestorage;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.exception.*;
import ru.taska.quota.StorageQuotaProtocol.Reservation;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/**
 * Tenant-bound retained metadata and durable deletion queue. No network effects in transactions.
 */
public final class AttachmentLifecycleStore {
  public record Item(
      Reservation reservation,
      String state,
      String key,
      String version,
      String filename,
      String mime,
      long size,
      String checksum,
      Instant created) {
    public UUID id() {
      return reservation.resource();
    }

    public String downloadPath() {
      return "/api/v1/projects/"
          + reservation.project()
          + "/issues/"
          + reservation.issue()
          + "/attachments/"
          + id()
          + "/content";
    }

    public AttachmentResponse response() {
      return AttachmentResponse.newBuilder()
          .setId(id().toString())
          .setIssueId(reservation.issue().toString())
          .setUploadedBy(reservation.actor().toString())
          .setFileName(filename)
          .setContentType(mime)
          .setSizeBytes(size)
          .setChecksum(checksum)
          .setUrl(downloadPath())
          .setCreatedAt(
              com.google.protobuf.Timestamp.newBuilder()
                  .setSeconds(created.getEpochSecond())
                  .setNanos(created.getNano()))
          .build();
    }

    @Override
    public String toString() {
      return "Attachment[id=" + id() + ", state=" + state + "]";
    }
  }

  public record Claim(Item item, long epoch) {}

  private final TenantTransactionExecutor tx;
  private static final String SELECT =
      "SELECT m.*,a.file_name,a.created_at AS attachment_created FROM"
          + " taska.attachment_object_manifest m JOIN taska.issue_attachments a ON"
          + " a.tenant_id=m.tenant_id AND a.id=m.id ";

  public AttachmentLifecycleStore(TenantTransactionExecutor tx) {
    this.tx = tx;
  }

  private <T> Mono<T> scoped(java.util.function.BiFunction<DatabaseClient, UUID, Mono<T>> work) {
    return VerifiedTenantContext.current()
        .flatMap(c -> tx.execute(db -> work.apply(db, UUID.fromString(c.tenantId()))));
  }

  public Mono<Item> item(UUID project, UUID issue, UUID id, boolean visible) {
    return scoped(
            (db, t) ->
                db.sql(
                        SELECT
                            + "WHERE m.tenant_id=:t AND m.project_id=:p AND m.issue_id=:i AND"
                            + " m.id=:id"
                            + (visible
                                ? " AND m.state='CONFIRMED'"
                                : " AND m.state IN ('CONFIRMED','DELETE_PENDING','DELETED')"))
                    .bind("t", t)
                    .bind("p", project)
                    .bind("i", issue)
                    .bind("id", id)
                    .fetch()
                    .one()
                    .map(AttachmentLifecycleStore::row))
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND")));
  }

  public Mono<List<Item>> list(UUID project, UUID issue) {
    return scoped(
        (db, t) ->
            db.sql(
                    SELECT
                        + "WHERE m.tenant_id=:t AND m.project_id=:p AND m.issue_id=:i AND"
                        + " m.state='CONFIRMED' ORDER BY a.created_at,m.id")
                .bind("t", t)
                .bind("p", project)
                .bind("i", issue)
                .fetch()
                .all()
                .map(AttachmentLifecycleStore::row)
                .collectList());
  }

  public Mono<Boolean> hasDeletion(UUID operation) {
    return scoped(
        (db, t) ->
            db.sql(
                    "SELECT EXISTS(SELECT 1 FROM taska.attachment_deletion_jobs WHERE tenant_id=:t"
                        + " AND operation_id=:op) AS found")
                .bind("t", t)
                .bind("op", operation)
                .map((r, m) -> r.get("found", Boolean.class))
                .one());
  }

  public Mono<Void> accept(Item item, TenantIssueAccess.Permit permit) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                tx.execute(
                    db -> {
                      var r = item.reservation();
                      permit.check(r.tenant(), c.actorId(), c.action(), r.project());
                      return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                          .bind("key", r.tenant() + "/" + r.operation())
                          .then()
                          .then(
                              db.sql(
                                      "SELECT state FROM taska.attachment_object_manifest WHERE"
                                          + " tenant_id=:t AND id=:id FOR UPDATE")
                                  .bind("t", r.tenant())
                                  .bind("id", r.resource())
                                  .map((v, m) -> v.get("state", String.class))
                                  .one())
                          .switchIfEmpty(
                              Mono.error(fail(DomainStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND")))
                          .flatMap(
                              state -> {
                                permit.check(r.tenant(), c.actorId(), c.action(), r.project());
                                if (!Set.of("CONFIRMED", "DELETE_PENDING", "DELETED")
                                    .contains(state))
                                  return Mono.error(
                                      fail(
                                          DomainStatus.FAILED_PRECONDITION,
                                          "ATTACHMENT_NOT_CONFIRMED"));
                                if (!state.equals("CONFIRMED"))
                                  return db.sql(
                                          "SELECT EXISTS(SELECT 1 FROM"
                                              + " taska.attachment_deletion_jobs WHERE tenant_id=:t"
                                              + " AND attachment_id=:id AND operation_id=:op) AS"
                                              + " found")
                                      .bind("t", r.tenant())
                                      .bind("id", r.resource())
                                      .bind("op", r.operation())
                                      .map((v, m) -> v.get("found", Boolean.class))
                                      .one()
                                      .filter(Boolean::booleanValue)
                                      .switchIfEmpty(
                                          Mono.error(
                                              fail(
                                                  DomainStatus.DATA_LOSS, "DELETE_INTENT_MISSING")))
                                      .then();
                                return db.sql(
                                        "INSERT INTO"
                                            + " taska.attachment_deletion_jobs(tenant_id,attachment_id,operation_id,initiator_id)"
                                            + " VALUES(:t,:id,:op,:actor) ON CONFLICT DO NOTHING")
                                    .bind("t", r.tenant())
                                    .bind("id", r.resource())
                                    .bind("op", r.operation())
                                    .bind("actor", UUID.fromString(c.actorId()))
                                    .fetch()
                                    .rowsUpdated()
                                    .then(
                                        db.sql(
                                                "UPDATE taska.attachment_object_manifest SET"
                                                    + " state='DELETE_PENDING' WHERE tenant_id=:t"
                                                    + " AND id=:id AND state='CONFIRMED'")
                                            .bind("t", r.tenant())
                                            .bind("id", r.resource())
                                            .fetch()
                                            .rowsUpdated())
                                    .then(
                                        db.sql(
                                                "INSERT INTO"
                                                    + " taska.storage_manifest_versions(tenant_id)"
                                                    + " VALUES(:t) ON CONFLICT(tenant_id) DO UPDATE"
                                                    + " SET version=taska.storage_manifest_versions.version+1")
                                            .bind("t", r.tenant())
                                            .fetch()
                                            .rowsUpdated())
                                    .then(
                                        db.sql(
                                                "INSERT INTO"
                                                    + " taska.storage_quota_outbox(tenant_id,operation_id,event_type,actor_id,issuer)"
                                                    + " VALUES(:t,:op,'AttachmentDeletionRequested',:actor,'issue-service')"
                                                    + " ON CONFLICT DO NOTHING")
                                            .bind("t", r.tenant())
                                            .bind("op", r.operation())
                                            .bind("actor", UUID.fromString(c.actorId()))
                                            .fetch()
                                            .rowsUpdated())
                                    .then();
                              });
                    }));
  }

  public Mono<List<UUID>> due(UUID after, int limit) {
    if (limit < 1 || limit > 100)
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "BOUNDED_RECOVERY_REQUIRED"));
    return scoped(
        (db, t) -> {
          var q =
              db.sql(
                      "SELECT attachment_id FROM taska.attachment_deletion_jobs WHERE tenant_id=:t"
                          + " AND state='PENDING' AND next_attempt_at<=now() AND (lease_until IS"
                          + " NULL OR lease_until<=now())"
                          + (after == null ? "" : " AND attachment_id>:after")
                          + " ORDER BY attachment_id LIMIT :limit")
                  .bind("t", t)
                  .bind("limit", limit);
          if (after != null) q = q.bind("after", after);
          return q.map((r, m) -> r.get("attachment_id", UUID.class)).all().collectList();
        });
  }

  public Mono<Claim> claim(UUID id) {
    return scoped(
        (db, t) ->
            db.sql(
                    "UPDATE taska.attachment_deletion_jobs SET"
                        + " lease_epoch=lease_epoch+1,lease_until=now()+interval '60"
                        + " seconds',attempts=attempts+1 WHERE tenant_id=:t AND attachment_id=:id"
                        + " AND state='PENDING' AND next_attempt_at<=now() AND (lease_until IS NULL"
                        + " OR lease_until<=now()) RETURNING lease_epoch")
                .bind("t", t)
                .bind("id", id)
                .map((r, m) -> r.get("lease_epoch", Long.class))
                .one()
                .flatMap(
                    epoch ->
                        db.sql(
                                SELECT
                                    + "WHERE m.tenant_id=:t AND m.id=:id AND m.state IN"
                                    + " ('DELETE_PENDING','DELETED')")
                            .bind("t", t)
                            .bind("id", id)
                            .fetch()
                            .one()
                            .switchIfEmpty(
                                Mono.error(fail(DomainStatus.DATA_LOSS, "DELETE_MANIFEST_MISSING")))
                            .map(r -> new Claim(row(r), epoch))));
  }

  public Mono<Boolean> finish(Claim claim, String error) {
    return scoped(
        (db, t) -> {
          var q =
              db.sql(
                      "UPDATE taska.attachment_deletion_jobs SET lease_until=NULL,"
                          + (error == null
                              ? "state='COMPLETED',completed_at=now(),last_code=NULL"
                              : "next_attempt_at=now()+interval '60 seconds',last_code=:code")
                          + " WHERE tenant_id=:t AND attachment_id=:id AND state='PENDING' AND"
                          + " lease_epoch=:epoch AND lease_until>now()")
                  .bind("t", t)
                  .bind("id", claim.item().id())
                  .bind("epoch", claim.epoch());
          if (error != null) q = q.bind("code", error);
          return q.fetch().rowsUpdated().map(n -> n == 1);
        });
  }

  public Mono<Long> pending() {
    return scoped(
        (db, t) ->
            db.sql(
                    "SELECT count(*) AS n FROM taska.attachment_deletion_jobs WHERE tenant_id=:t"
                        + " AND state='PENDING'")
                .bind("t", t)
                .map((r, m) -> r.get("n", Long.class))
                .one());
  }

  private static Item row(Map<String, Object> m) {
    return new Item(
        StorageQuotaOwner.fromManifest(m),
        (String) m.get("state"),
        (String) m.get("object_key"),
        (String) m.get("final_object_version"),
        (String) m.get("file_name"),
        (String) m.get("content_type"),
        (Long) m.get("confirmed_bytes"),
        (String) m.get("checksum_sha256"),
        ((OffsetDateTime) m.get("attachment_created")).toInstant());
  }

  private static DomainException fail(DomainStatus status, String code) {
    return new DomainException(status, code);
  }
}
