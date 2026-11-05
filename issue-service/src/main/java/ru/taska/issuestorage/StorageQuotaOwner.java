package ru.taska.issuestorage;

import static ru.taska.quota.StorageQuotaProtocol.*;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.*;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol.State;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;
import ru.taska.storage.versioned.VersionedObjects;

/**
 * Primary attachment manifest. URLs, client sizes and bucket listings are never admission proofs.
 */
public final class StorageQuotaOwner implements Owner {
  public enum Use {
    PREPARE,
    UPLOAD_WINDOW,
    MATERIALIZE,
    INSPECT,
    ACTIVATE,
    CANCEL,
    DIRECT_DELETE,
    PROJECT_CLEANUP,
    INVENTORY
  }

  @FunctionalInterface
  public interface Policy {
    Mono<Void> require(
        VerifiedTenantContext context, Use use, Reservation reservation, String contentType);
  }

  private final TenantTransactionExecutor tx;
  private final Authority authority;
  private final VersionedObjects objects;
  private final Policy policy;
  private final Clock clock;

  public StorageQuotaOwner(
      TenantTransactionExecutor tx,
      Authority authority,
      VersionedObjects objects,
      Policy policy,
      Clock clock) {
    this.tx = Objects.requireNonNull(tx);
    this.authority = Objects.requireNonNull(authority);
    this.objects = Objects.requireNonNull(objects);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
  }

  public static String stagingKey(Reservation r) {
    return prefix(r) + "/staging";
  }

  public static String finalKey(Reservation r) {
    return prefix(r) + "/final";
  }

  private static String prefix(Reservation r) {
    return "tenant/"
        + r.tenant()
        + "/project/"
        + r.project()
        + "/issue/"
        + r.issue()
        + "/"
        + r.resource();
  }

  /** TAS-365 must persist the PUT window before issuing any client capability. */
  public Mono<Void> prepare(Reservation r, String mime) {
    if (mime == null || mime.isBlank() || mime.length() > 255)
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "CONTENT_TYPE_REQUIRED"));
    return authorized(r, Use.PREPARE, mime)
        .flatMap(
            c -> {
              if (!r.actor().toString().equals(c.actorId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "ORIGINAL_ACTOR_REQUIRED"));
              return primary(r)
                  .doOnNext(this::active)
                  .then(objects.requireVersioning())
                  .then(
                      tx.execute(
                          db ->
                              lock(db, r)
                                  .then(
                                      manifest(db, r)
                                          .flatMap(
                                              row -> {
                                                match(r, row);
                                                if (!mime.equals(row.get("content_type")))
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.ALREADY_EXISTS,
                                                          "UPLOAD_CONFLICT"));
                                                return Mono.just(true);
                                              })
                                          .switchIfEmpty(
                                              Mono.defer(
                                                  () ->
                                                      receiptRow(db, r)
                                                          .flatMap(
                                                              row ->
                                                                  Mono.<Boolean>error(
                                                                      fail(
                                                                          DomainStatus
                                                                              .FAILED_PRECONDITION,
                                                                          "UPLOAD_CANCELLED")))
                                                          .switchIfEmpty(
                                                              Mono.defer(
                                                                  () ->
                                                                      db.sql(
                                                                              """
                                                                              INSERT INTO taska.attachment_object_manifest(id,tenant_id,project_id,issue_id,actor_id,object_key,staging_object_key,reservation_id,operation_id,generation,state,expected_bytes,content_type,expires_at,created_at,maximum_attachment_bytes,policy_version,request_hash,reservation_version)
                                                                              VALUES(:id,:t,:p,:i,:a,:key,:staging,:reservation,:op,:f,'RESERVED',:bytes,:mime,:expires,:now,:max,:policy,:hash,:version)
                                                                              """)
                                                                          .bind("id", r.resource())
                                                                          .bind("t", r.tenant())
                                                                          .bind("p", r.project())
                                                                          .bind("i", r.issue())
                                                                          .bind("a", r.actor())
                                                                          .bind("key", finalKey(r))
                                                                          .bind(
                                                                              "staging",
                                                                              stagingKey(r))
                                                                          .bind(
                                                                              "reservation", r.id())
                                                                          .bind("op", r.operation())
                                                                          .bind("f", r.fence())
                                                                          .bind("bytes", r.bytes())
                                                                          .bind("mime", mime)
                                                                          .bind(
                                                                              "expires",
                                                                              offset(r.expiresAt()))
                                                                          .bind(
                                                                              "now",
                                                                              offset(
                                                                                  clock.instant()))
                                                                          .bind(
                                                                              "max",
                                                                              r
                                                                                  .maximumAttachmentBytes())
                                                                          .bind(
                                                                              "policy",
                                                                              r.policyVersion())
                                                                          .bind(
                                                                              "hash", hash(r, mime))
                                                                          .bind(
                                                                              "version",
                                                                              r.version())
                                                                          .fetch()
                                                                          .rowsUpdated()
                                                                          .then(
                                                                              bump(db, r.tenant()))
                                                                          .then(
                                                                              event(
                                                                                  db,
                                                                                  r,
                                                                                  c,
                                                                                  "AttachmentReserved"))
                                                                          .thenReturn(true))))))
                                  .then()));
            });
  }

  public Mono<Void> recordUploadWindow(Reservation r, Instant notAfter) {
    if (notAfter == null
        || !notAfter.isAfter(clock.instant())
        || notAfter.isAfter(r.expiresAt())
        || notAfter.isAfter(clock.instant().plus(Duration.ofMinutes(5))))
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "PUT_WINDOW_INVALID"));
    var until = notAfter.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    return authorized(r, Use.UPLOAD_WINDOW, null)
        .then(primary(r).doOnNext(this::active))
        .then(
            tx.execute(
                db ->
                    lock(db, r)
                        .then(required(db, r))
                        .flatMap(
                            row -> {
                              if (!"RESERVED".equals(row.get("state")))
                                return Mono.error(
                                    fail(DomainStatus.FAILED_PRECONDITION, "UPLOAD_WINDOW_CLOSED"));
                              if (row.get("put_not_after") != null)
                                return until.equals(
                                        ((OffsetDateTime) row.get("put_not_after")).toInstant())
                                    ? Mono.empty()
                                    : Mono.error(
                                        fail(DomainStatus.ALREADY_EXISTS, "PUT_WINDOW_CONFLICT"));
                              return db.sql(
                                      "UPDATE taska.attachment_object_manifest SET"
                                          + " put_not_after=:until WHERE tenant_id=:t AND"
                                          + " operation_id=:op")
                                  .bind("until", offset(until))
                                  .bind("t", r.tenant())
                                  .bind("op", r.operation())
                                  .fetch()
                                  .rowsUpdated()
                                  .then();
                            })));
  }

  /**
   * Versions are supplied by the trusted upload producer after copying the pinned staging version.
   */
  public Mono<Receipt> materialize(Reservation r, String stagingVersion, String finalVersion) {
    return authorized(r, Use.MATERIALIZE, null)
        .flatMap(
            c ->
                primary(r)
                    .doOnNext(this::active)
                    .then(tx.execute(db -> required(db, r)))
                    .flatMap(
                        row -> {
                          if (!Set.of("RESERVED", "UPLOADED").contains(row.get("state")))
                            return Mono.error(
                                fail(DomainStatus.FAILED_PRECONDITION, "UPLOAD_CLOSED"));
                          return objects
                              .requireVersioning()
                              .then(
                                  Mono.zip(
                                      objects.measure(stagingKey(r), stagingVersion, r.bytes()),
                                      objects.measure(finalKey(r), finalVersion, r.bytes())))
                              .flatMap(
                                  pair -> {
                                    var s = pair.getT1();
                                    var f = pair.getT2();
                                    if (s.bytes() != f.bytes()
                                        || !s.checksumSha256().equals(f.checksumSha256())
                                        || !s.contentType().equals(f.contentType())
                                        || !f.contentType().equals(row.get("content_type")))
                                      return Mono.error(
                                          fail(
                                              DomainStatus.INVALID_ARGUMENT,
                                              "OBJECT_PROOF_MISMATCH"));
                                    return policy
                                        .require(c, Use.MATERIALIZE, r, f.contentType())
                                        .then(primary(r).doOnNext(this::active))
                                        .then(
                                            tx.execute(
                                                db ->
                                                    lock(db, r)
                                                        .then(required(db, r))
                                                        .flatMap(
                                                            now -> {
                                                              if (!Set.of("RESERVED", "UPLOADED")
                                                                      .contains(now.get("state"))
                                                                  || !clock
                                                                      .instant()
                                                                      .isBefore(r.expiresAt()))
                                                                return Mono.error(
                                                                    fail(
                                                                        DomainStatus
                                                                            .FAILED_PRECONDITION,
                                                                        "UPLOAD_CLOSED"));
                                                              if ("UPLOADED"
                                                                  .equals(now.get("state"))) {
                                                                if (!stagingVersion.equals(
                                                                        now.get(
                                                                            "staging_version_id"))
                                                                    || !finalVersion.equals(
                                                                        now.get(
                                                                            "final_object_version"))
                                                                    || !Long.valueOf(f.bytes())
                                                                        .equals(
                                                                            now.get(
                                                                                "confirmed_bytes"))
                                                                    || !f.checksumSha256()
                                                                        .equals(
                                                                            now.get(
                                                                                "checksum_sha256")))
                                                                  return Mono.error(
                                                                      fail(
                                                                          DomainStatus
                                                                              .ALREADY_EXISTS,
                                                                          "OBJECT_PROOF_CONFLICT"));
                                                                return receipt(db, r);
                                                              }
                                                              return db.sql(
                                                                      "UPDATE"
                                                                          + " taska.attachment_object_manifest"
                                                                          + " SET state='UPLOADED',confirmed_bytes=:bytes,checksum_sha256=:sha,staging_version_id=:stage,final_object_version=:final"
                                                                          + " WHERE tenant_id=:t"
                                                                          + " AND operation_id=:op")
                                                                  .bind("bytes", f.bytes())
                                                                  .bind("sha", f.checksumSha256())
                                                                  .bind("stage", stagingVersion)
                                                                  .bind("final", finalVersion)
                                                                  .bind("t", r.tenant())
                                                                  .bind("op", r.operation())
                                                                  .fetch()
                                                                  .rowsUpdated()
                                                                  .then(
                                                                      insertReceipt(
                                                                          db,
                                                                          r,
                                                                          "COMMITTED",
                                                                          f.bytes()))
                                                                  .then(bump(db, r.tenant()))
                                                                  .then(
                                                                      event(
                                                                          db,
                                                                          r,
                                                                          c,
                                                                          "AttachmentMaterialized"))
                                                                  .then(receipt(db, r));
                                                            })));
                                  });
                        }));
  }

  @Override
  public Mono<Receipt> inspect(Reservation r) {
    return authorized(r, Use.INSPECT, null)
        .then(tx.execute(db -> lock(db, r).then(receipt(db, r))));
  }

  @Override
  public Mono<Receipt> activate(Reservation r) {
    return authorized(r, Use.ACTIVATE, null)
        .flatMap(
            c ->
                primary(r)
                    .flatMap(
                        proof -> {
                          if (proof.state() != State.COMMITTED || proof.actualBytes() == null)
                            return Mono.error(
                                fail(DomainStatus.FAILED_PRECONDITION, "ROOT_COMMIT_REQUIRED"));
                          return tx.execute(
                              db ->
                                  lock(db, r)
                                      .then(required(db, r))
                                      .flatMap(
                                          row -> {
                                            if (!proof
                                                .actualBytes()
                                                .equals(row.get("confirmed_bytes")))
                                              return Mono.error(
                                                  fail(
                                                      DomainStatus.DATA_LOSS,
                                                      "ACTUAL_BYTES_CONFLICT"));
                                            if (Set.of("CONFIRMED", "DELETE_PENDING", "DELETED")
                                                .contains(row.get("state"))) return receipt(db, r);
                                            if (!"UPLOADED".equals(row.get("state")))
                                              return Mono.error(
                                                  fail(
                                                      DomainStatus.FAILED_PRECONDITION,
                                                      "OBJECT_NOT_MATERIALIZED"));
                                            return db.sql(
                                                    "UPDATE taska.attachment_object_manifest SET"
                                                        + " state='CONFIRMED' WHERE tenant_id=:t"
                                                        + " AND operation_id=:op")
                                                .bind("t", r.tenant())
                                                .bind("op", r.operation())
                                                .fetch()
                                                .rowsUpdated()
                                                .then(bump(db, r.tenant()))
                                                .then(event(db, r, c, "AttachmentConfirmed"))
                                                .then(receipt(db, r));
                                          }));
                        }));
  }

  @Override
  public Mono<Receipt> cancel(Reservation r) {
    return authorized(r, Use.CANCEL, null)
        .flatMap(
            c ->
                primary(r)
                    .flatMap(
                        proof -> {
                          if (proof.state() == State.COMMITTED || !proof.cancelRequested())
                            return Mono.error(
                                fail(
                                    DomainStatus.FAILED_PRECONDITION,
                                    "ROOT_CANCEL_INTENT_REQUIRED"));
                          // Seal the operation before touching S3. A failed purge retains the root
                          // reservation.
                          return tx.execute(
                                  db ->
                                      lock(db, r)
                                          .then(manifest(db, r))
                                          .flatMap(
                                              row -> {
                                                match(r, row);
                                                if (Set.of("CONFIRMED", "DELETE_PENDING", "DELETED")
                                                    .contains(row.get("state")))
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.FAILED_PRECONDITION,
                                                          "VISIBLE_OBJECT_CANNOT_CANCEL"));
                                                return db.sql(
                                                        "UPDATE taska.attachment_object_manifest"
                                                            + " SET state='EXPIRED' WHERE"
                                                            + " tenant_id=:t AND operation_id=:op")
                                                    .bind("t", r.tenant())
                                                    .bind("op", r.operation())
                                                    .fetch()
                                                    .rowsUpdated()
                                                    .then(sealCancelled(db, r))
                                                    .then(bump(db, r.tenant()))
                                                    .thenReturn(row);
                                              })
                                          .switchIfEmpty(
                                              Mono.defer(
                                                  () -> sealCancelled(db, r).thenReturn(Map.of()))))
                              .flatMap(
                                  row -> {
                                    if (row.isEmpty())
                                      return Mono.just(new Receipt(r, OwnerState.CANCELLED, null));
                                    return purge(r, row)
                                        .then(
                                            tx.execute(
                                                db ->
                                                    event(db, r, c, "AttachmentCancelled")
                                                        .thenReturn(
                                                            new Receipt(
                                                                r, OwnerState.CANCELLED, null))));
                                  });
                        }));
  }

  public Mono<Receipt> deleteConfirmed(Reservation r, Use purpose) {
    if (purpose != Use.DIRECT_DELETE && purpose != Use.PROJECT_CLEANUP)
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "DELETE_PURPOSE_REQUIRED"));
    return authorized(r, purpose, null)
        .flatMap(
            c ->
                primary(r)
                    .flatMap(
                        proof -> {
                          if (proof.state() != State.COMMITTED)
                            return Mono.error(
                                fail(DomainStatus.FAILED_PRECONDITION, "ROOT_COMMIT_REQUIRED"));
                          return tx.execute(
                                  db ->
                                      lock(db, r)
                                          .then(required(db, r))
                                          .flatMap(
                                              row -> {
                                                if ("DELETED".equals(row.get("state")))
                                                  return Mono.just(row);
                                                if (!Set.of("CONFIRMED", "DELETE_PENDING")
                                                    .contains(row.get("state")))
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.FAILED_PRECONDITION,
                                                          "VISIBLE_OBJECT_REQUIRED"));
                                                return db.sql(
                                                        "UPDATE taska.attachment_object_manifest"
                                                            + " SET state='DELETE_PENDING' WHERE"
                                                            + " tenant_id=:t AND operation_id=:op")
                                                    .bind("t", r.tenant())
                                                    .bind("op", r.operation())
                                                    .fetch()
                                                    .rowsUpdated()
                                                    .thenReturn(row);
                                              }))
                              .flatMap(
                                  row -> {
                                    if ("DELETED".equals(row.get("state")))
                                      return Mono.just(
                                          new Receipt(
                                              r,
                                              OwnerState.DELETED,
                                              (Long) row.get("confirmed_bytes")));
                                    return purge(r, row)
                                        .then(
                                            tx.execute(
                                                db ->
                                                    lock(db, r)
                                                        .then(required(db, r))
                                                        .flatMap(
                                                            current -> {
                                                              if (!Set.of(
                                                                      "DELETE_PENDING", "DELETED")
                                                                  .contains(current.get("state")))
                                                                return Mono.error(
                                                                    fail(
                                                                        DomainStatus
                                                                            .FAILED_PRECONDITION,
                                                                        "DELETE_FENCE_CHANGED"));
                                                              return db.sql(
                                                                      "UPDATE"
                                                                          + " taska.attachment_object_manifest"
                                                                          + " SET state='DELETED'"
                                                                          + " WHERE tenant_id=:t"
                                                                          + " AND operation_id=:op")
                                                                  .bind("t", r.tenant())
                                                                  .bind("op", r.operation())
                                                                  .fetch()
                                                                  .rowsUpdated()
                                                                  .then(
                                                                      db.sql(
                                                                              "UPDATE"
                                                                                  + " taska.resource_operation_receipts"
                                                                                  + " SET state='DELETED'"
                                                                                  + " WHERE"
                                                                                  + " tenant_id=:t"
                                                                                  + " AND operation_id=:op"
                                                                                  + " AND resource_kind='STORAGE_BYTES'")
                                                                          .bind("t", r.tenant())
                                                                          .bind("op", r.operation())
                                                                          .fetch()
                                                                          .rowsUpdated())
                                                                  .then(bump(db, r.tenant()))
                                                                  .then(
                                                                      event(
                                                                          db,
                                                                          r,
                                                                          c,
                                                                          "AttachmentDeleted"))
                                                                  .then(receipt(db, r));
                                                            })));
                                  });
                        }));
  }

  private Mono<Void> purge(Reservation r, Map<String, Object> row) {
    var until = (OffsetDateTime) row.get("put_not_after");
    if (until != null && clock.instant().isBefore(until.toInstant()))
      return Mono.error(fail(DomainStatus.UNAVAILABLE, "PUT_WINDOW_STILL_OPEN"));
    // Purge only the two server-generated keys of this manifest, never a tenant/bucket prefix.
    return objects
        .requireVersioning()
        .then(objects.purgeKey(stagingKey(r)))
        .flatMap(done -> done ? objects.purgeKey(finalKey(r)) : Mono.just(false))
        .flatMap(
            done ->
                done
                    ? Mono.empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "OWNED_OBJECT_CLEANUP_PENDING")));
  }

  @Override
  public Mono<ManifestPage> manifests(UUID tenant, UUID after, long expectedVersion) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!tenant.toString().equals(c.tenantId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return policy
                  .require(c, Use.INVENTORY, null, null)
                  .then(
                      tx.execute(
                          db ->
                              db.sql(
                                      "INSERT INTO taska.storage_manifest_versions(tenant_id)"
                                          + " VALUES(:t) ON CONFLICT DO NOTHING")
                                  .bind("t", tenant)
                                  .fetch()
                                  .rowsUpdated()
                                  .then(
                                      db.sql(
                                              "SELECT version FROM taska.storage_manifest_versions"
                                                  + " WHERE tenant_id=:t")
                                          .bind("t", tenant)
                                          .map((r, m) -> r.get("version", Long.class))
                                          .one())
                                  .flatMap(
                                      version -> {
                                        if (expectedVersion != 0 && expectedVersion != version)
                                          return Mono.error(
                                              fail(
                                                  DomainStatus.FAILED_PRECONDITION,
                                                  "MANIFEST_SNAPSHOT_CHANGED"));
                                        var q =
                                            db.sql(
                                                    "SELECT m.*,r.state AS receipt_state,r.amount"
                                                        + " AS receipt_amount FROM"
                                                        + " taska.attachment_object_manifest m JOIN"
                                                        + " taska.resource_operation_receipts r ON"
                                                        + " r.tenant_id=m.tenant_id AND"
                                                        + " r.operation_id=m.operation_id AND"
                                                        + " r.resource_kind='STORAGE_BYTES' WHERE"
                                                        + " m.tenant_id=:t AND (:after::uuid IS"
                                                        + " NULL OR m.id>:after) ORDER BY m.id"
                                                        + " LIMIT 101")
                                                .bind("t", tenant);
                                        q =
                                            after == null
                                                ? q.bindNull("after", UUID.class)
                                                : q.bind("after", after);
                                        return q.fetch()
                                            .all()
                                            .collectList()
                                            .flatMap(
                                                rows ->
                                                    db.sql(
                                                            "SELECT version FROM"
                                                                + " taska.storage_manifest_versions"
                                                                + " WHERE tenant_id=:t")
                                                        .bind("t", tenant)
                                                        .map((r, m) -> r.get("version", Long.class))
                                                        .one()
                                                        .map(
                                                            endVersion -> {
                                                              if (endVersion != version.longValue())
                                                                throw fail(
                                                                    DomainStatus
                                                                        .FAILED_PRECONDITION,
                                                                    "MANIFEST_SNAPSHOT_CHANGED");
                                                              var items =
                                                                  rows
                                                                      .subList(
                                                                          0,
                                                                          Math.min(
                                                                              100, rows.size()))
                                                                      .stream()
                                                                      .map(
                                                                          row -> {
                                                                            var state =
                                                                                switch ((String)
                                                                                    row.get(
                                                                                        "receipt_state")) {
                                                                                  case "CANCELLED" ->
                                                                                      OwnerState
                                                                                          .CANCELLED;
                                                                                  case "DELETED" ->
                                                                                      OwnerState
                                                                                          .DELETED;
                                                                                  default ->
                                                                                      "UPLOADED"
                                                                                              .equals(
                                                                                                  row
                                                                                                      .get(
                                                                                                          "state"))
                                                                                          ? OwnerState
                                                                                              .MATERIALIZED
                                                                                          : OwnerState
                                                                                              .CONFIRMED;
                                                                                };
                                                                            return new Receipt(
                                                                                fromManifest(row),
                                                                                state,
                                                                                state
                                                                                        == OwnerState
                                                                                            .CANCELLED
                                                                                    ? null
                                                                                    : (Long)
                                                                                        row.get(
                                                                                            "receipt_amount"));
                                                                          })
                                                                      .toList();
                                                              return new ManifestPage(
                                                                  tenant,
                                                                  version,
                                                                  items,
                                                                  rows.size() > 100
                                                                      ? (UUID)
                                                                          rows.get(99).get("id")
                                                                      : null,
                                                                  rows.size() <= 100);
                                                            }));
                                      })));
            });
  }

  private Mono<VerifiedTenantContext> authorized(Reservation r, Use use, String mime) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!r.tenant().toString().equals(c.tenantId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return policy.require(c, use, r, mime).timeout(Duration.ofSeconds(5)).thenReturn(c);
            });
  }

  private Mono<Reservation> primary(Reservation r) {
    return authority
        .get(r.operation())
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ROOT_ADMISSION_UNAVAILABLE")))
        .map(
            proof -> {
              if (!r.sameIdentity(proof))
                throw fail(DomainStatus.FAILED_PRECONDITION, "STALE_STORAGE_FENCE");
              return proof;
            });
  }

  private void active(Reservation r) {
    if (r.state() != State.ACTIVE
        || r.cancelRequested()
        || !clock.instant().isBefore(r.expiresAt()))
      throw fail(DomainStatus.FAILED_PRECONDITION, "STORAGE_RESERVATION_CLOSED");
  }

  private Mono<Void> lock(DatabaseClient db, Reservation r) {
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .bind("key", r.tenant() + "/" + r.operation())
        .then();
  }

  private Mono<Map<String, Object>> manifest(DatabaseClient db, Reservation r) {
    return db.sql(
            "SELECT * FROM taska.attachment_object_manifest WHERE tenant_id=:t AND operation_id=:op"
                + " FOR UPDATE")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .fetch()
        .one();
  }

  private Mono<Map<String, Object>> required(DatabaseClient db, Reservation r) {
    return manifest(db, r)
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "MANIFEST_NOT_FOUND")))
        .doOnNext(row -> match(r, row));
  }

  private Mono<Map<String, Object>> receiptRow(DatabaseClient db, Reservation r) {
    return db.sql(
            "SELECT * FROM taska.resource_operation_receipts WHERE tenant_id=:t AND"
                + " operation_id=:op AND resource_kind='STORAGE_BYTES'")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .fetch()
        .one();
  }

  private Mono<Void> insertReceipt(DatabaseClient db, Reservation r, String state, long amount) {
    var q =
        db.sql(
                "INSERT INTO"
                    + " taska.resource_operation_receipts(tenant_id,operation_id,resource_kind,resource_id,reservation_id,reservation_fence,state,amount,committed_at)"
                    + " VALUES(:t,:op,'STORAGE_BYTES',:id,:reservation,:f,:state,:bytes,:now)")
            .bind("t", r.tenant())
            .bind("op", r.operation())
            .bind("id", r.resource())
            .bind("reservation", r.id())
            .bind("f", r.fence())
            .bind("state", state)
            .bind("bytes", amount);
    q =
        "CANCELLED".equals(state)
            ? q.bindNull("now", OffsetDateTime.class)
            : q.bind("now", offset(clock.instant()));
    return q.fetch().rowsUpdated().then();
  }

  private Mono<Void> sealCancelled(DatabaseClient db, Reservation r) {
    return receiptRow(db, r)
        .flatMap(
            row -> {
              if (!r.id().equals(row.get("reservation_id"))
                  || !r.resource().equals(row.get("resource_id"))
                  || r.fence() != ((Long) row.get("reservation_fence")))
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "OWNER_IDENTITY_CONFLICT"));
              if ("DELETED".equals(row.get("state")))
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "OBJECT_ALREADY_DELETED"));
              return db.sql(
                      "UPDATE taska.resource_operation_receipts SET state='CANCELLED' WHERE"
                          + " tenant_id=:t AND operation_id=:op AND resource_kind='STORAGE_BYTES'")
                  .bind("t", r.tenant())
                  .bind("op", r.operation())
                  .fetch()
                  .rowsUpdated();
            })
        .switchIfEmpty(
            Mono.defer(() -> insertReceipt(db, r, "CANCELLED", r.bytes()).thenReturn(1L)))
        .then();
  }

  private Mono<Receipt> receipt(DatabaseClient db, Reservation r) {
    return receiptRow(db, r)
        .flatMap(
            row -> {
              if (!r.id().equals(row.get("reservation_id"))
                  || !r.resource().equals(row.get("resource_id"))
                  || r.fence() != ((Long) row.get("reservation_fence")))
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "OWNER_IDENTITY_CONFLICT"));
              if ("CANCELLED".equals(row.get("state")))
                return Mono.just(new Receipt(r, OwnerState.CANCELLED, null));
              if ("DELETED".equals(row.get("state")))
                return Mono.just(new Receipt(r, OwnerState.DELETED, (Long) row.get("amount")));
              return required(db, r)
                  .map(
                      m ->
                          new Receipt(
                              r,
                              "UPLOADED".equals(m.get("state"))
                                  ? OwnerState.MATERIALIZED
                                  : OwnerState.CONFIRMED,
                              (Long) row.get("amount")));
            });
  }

  private static void match(Reservation r, Map<String, Object> row) {
    if (!r.sameIdentity(fromManifest(row)))
      throw fail(DomainStatus.FAILED_PRECONDITION, "OWNER_IDENTITY_CONFLICT");
  }

  private static Reservation fromManifest(Map<String, Object> m) {
    return new Reservation(
        (UUID) m.get("tenant_id"),
        new Request(
            (UUID) m.get("operation_id"),
            (UUID) m.get("id"),
            (UUID) m.get("project_id"),
            (UUID) m.get("issue_id"),
            (Long) m.get("expected_bytes"),
            ((OffsetDateTime) m.get("expires_at")).toInstant()),
        (UUID) m.get("reservation_id"),
        (Long) m.get("generation"),
        State.ACTIVE,
        (Long) m.get("reservation_version"),
        (Long) m.get("confirmed_bytes"),
        false,
        (Long) m.get("maximum_attachment_bytes"),
        (Long) m.get("policy_version"),
        (UUID) m.get("actor_id"));
  }

  private Mono<Void> bump(DatabaseClient db, UUID tenant) {
    return db.sql(
            "INSERT INTO taska.storage_manifest_versions(tenant_id) VALUES(:t) ON"
                + " CONFLICT(tenant_id) DO UPDATE SET"
                + " version=taska.storage_manifest_versions.version+1")
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> event(DatabaseClient db, Reservation r, VerifiedTenantContext c, String name) {
    return db.sql(
            "INSERT INTO"
                + " taska.storage_quota_outbox(tenant_id,operation_id,event_type,actor_id,issuer)"
                + " VALUES(:t,:op,:event,:actor,:issuer) ON CONFLICT DO NOTHING")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .bind("event", name)
        .bind("actor", r.actor())
        .bind("issuer", RpcContextBridge.details(c).getContext().getIssuerService())
        .fetch()
        .rowsUpdated()
        .then();
  }

  private static String hash(Reservation r, String mime) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      (r.request() + "\n" + mime)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static OffsetDateTime offset(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static DomainException fail(DomainStatus status, String message) {
    return new DomainException(status, message);
  }
}
