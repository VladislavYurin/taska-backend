package ru.taska.projectstorage;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Primary resource owner. Never calls remote authority while holding owner transaction locks. */
public final class ProjectQuotaOwner implements Owner {
  public enum Use {
    PROVISION,
    OPEN,
    INSPECT,
    CANCEL,
    INVENTORY,
    DELETE_RECEIPT
  }

  @FunctionalInterface
  public interface Policy {
    /** Current identity/tenant/project ACL/lifecycle/purpose and accepted-operation binding. */
    Mono<Void> require(VerifiedTenantContext context, Use use, UUID operation, UUID project);
  }

  public record Create(String key, String name, UUID creator) {
    public Create {
      if (key == null
          || !key.matches("[A-Z][A-Z0-9]{1,15}")
          || name == null
          || name.isBlank()
          || name.length() > 200
          || creator == null) throw fail(DomainStatus.INVALID_ARGUMENT, "INVALID_PROJECT");
    }
  }

  private final TenantTransactionExecutor tx;
  private final ProjectQuotaProtocol.Authority authority;
  private final Policy policy;
  private final Clock clock;

  public ProjectQuotaOwner(
      TenantTransactionExecutor tx,
      ProjectQuotaProtocol.Authority authority,
      Policy policy,
      Clock clock) {
    this.tx = Objects.requireNonNull(tx);
    this.authority = Objects.requireNonNull(authority);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<Receipt> provision(Reservation expected, Create command) {
    Objects.requireNonNull(command);
    return authorized(expected, Use.PROVISION)
        .flatMap(
            actor -> {
              if (actor.actorId() == null || !command.creator().toString().equals(actor.actorId()))
                return Mono.<Reservation>error(
                    fail(DomainStatus.PERMISSION_DENIED, "CREATOR_MUST_BE_CURRENT_ACTOR"));
              return primary(expected);
            })
        .flatMap(
            proof -> {
              if (proof.cancelRequested())
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_CANCELLING"));
              if (proof.state() != State.ACTIVE && proof.state() != State.COMMITTED)
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_CLOSED"));
              String hash = hash(command);
              return tx.execute(
                  db ->
                      claim(db, expected, hash, command)
                          .then(row(db, expected))
                          .flatMap(
                              row -> {
                                matching(expected, row);
                                if (row.get("request_hash") != null
                                    && !hash.equals(row.get("request_hash")))
                                  return Mono.error(
                                      fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                                if ("DELETED".equals(row.get("state")))
                                  return Mono.error(
                                      fail(DomainStatus.NOT_FOUND, "PROJECT_DELETED"));
                                if (!"COMMITTED".equals(row.get("state")))
                                  return Mono.error(
                                      fail(
                                          DomainStatus.FAILED_PRECONDITION, "OPERATION_CANCELLED"));
                                if (row.get("committed_at") != null) return receipt(db, expected);
                                return Mono.error(
                                    fail(DomainStatus.INTERNAL, "INVALID_OWNER_RECEIPT"));
                              }));
            });
  }

  private Mono<Void> claim(DatabaseClient db, Reservation r, String hash, Create command) {
    // Serialize with cancellation even before the first resource exists.
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .bind("key", r.tenant() + "/" + r.operation())
        .then()
        .then(
            row(db, r)
                .flatMap(
                    existing -> {
                      matching(r, existing);
                      return Mono.just(true);
                    })
                .defaultIfEmpty(false))
        .flatMap(
            exists -> {
              if (exists) return Mono.empty();
              if (!clock.instant().isBefore(r.expiresAt()))
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_EXPIRED"));
              return db.sql(
                      "INSERT INTO"
                          + " taska.projects(tenant_id,id,project_key,name,created_by,admission_state)"
                          + " VALUES(:t,:id,:key,:name,:creator,'PROVISIONING')")
                  .bind("t", r.tenant())
                  .bind("id", r.resource())
                  .bind("key", command.key())
                  .bind("name", command.name())
                  .bind("creator", command.creator())
                  .fetch()
                  .rowsUpdated()
                  .then(
                      db.sql(
                              "INSERT INTO"
                                  + " taska.project_members(tenant_id,project_id,user_id,role,added_by)"
                                  + " VALUES(:t,:id,:creator,'ADMIN',:creator)")
                          .bind("t", r.tenant())
                          .bind("id", r.resource())
                          .bind("creator", command.creator())
                          .fetch()
                          .rowsUpdated())
                  .then(insertReceipt(db, r, hash, "COMMITTED"))
                  .then(
                      db.sql(
                              "INSERT INTO"
                                  + " taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload)"
                                  + " VALUES(:t,'project',:id,'ProjectProvisioned',jsonb_build_object('projectId',CAST(:text"
                                  + " AS text)))")
                          .bind("t", r.tenant())
                          .bind("id", r.resource())
                          .bind("text", r.resource().toString())
                          .fetch()
                          .rowsUpdated())
                  .then();
            });
  }

  @Override
  public Mono<Receipt> inspect(Reservation r) {
    return authorized(r, Use.INSPECT)
        .then(
            tx.execute(
                db ->
                    row(db, r)
                        .flatMap(
                            row -> {
                              matching(r, row);
                              return receipt(db, r);
                            })));
  }

  @Override
  public Mono<Receipt> cancel(Reservation r) {
    return authorized(r, Use.CANCEL)
        .then(
            primary(r)
                .flatMap(
                    proof ->
                        proof.cancelRequested() && proof.state() != State.COMMITTED
                            ? Mono.just(proof)
                            : Mono.error(
                                fail(DomainStatus.FAILED_PRECONDITION, "CANCEL_NOT_REQUESTED"))))
        .then(
            tx.execute(
                db ->
                    operationLock(db, r)
                        .then(
                            row(db, r)
                                .flatMap(
                                    existing -> {
                                      matching(r, existing);
                                      if (existing.get("opened_at") != null
                                          || "DELETED".equals(existing.get("state")))
                                        return Mono.error(
                                            fail(
                                                DomainStatus.FAILED_PRECONDITION,
                                                "VISIBLE_RESOURCE_CANNOT_CANCEL"));
                                      return Mono.just(true);
                                    })
                                .defaultIfEmpty(false))
                        .flatMap(
                            exists -> {
                              if (!exists)
                                return insertReceipt(db, r, null, "CANCELLED").then(receipt(db, r));
                              return db.sql(
                                      "UPDATE taska.resource_operation_receipts SET"
                                          + " state='CANCELLED' WHERE tenant_id=:t AND"
                                          + " operation_id=:op AND resource_kind='PROJECT' AND"
                                          + " state='COMMITTED' AND opened_at IS NULL")
                                  .bind("t", r.tenant())
                                  .bind("op", r.operation())
                                  .fetch()
                                  .rowsUpdated()
                                  .then(
                                      db.sql(
                                              "DELETE FROM taska.project_members WHERE tenant_id=:t"
                                                  + " AND project_id=:id AND EXISTS(SELECT 1 FROM"
                                                  + " taska.projects WHERE tenant_id=:t AND id=:id"
                                                  + " AND admission_state='PROVISIONING')")
                                          .bind("t", r.tenant())
                                          .bind("id", r.resource())
                                          .fetch()
                                          .rowsUpdated())
                                  .then(
                                      db.sql(
                                              "DELETE FROM taska.projects WHERE tenant_id=:t AND"
                                                  + " id=:id AND admission_state='PROVISIONING'")
                                          .bind("t", r.tenant())
                                          .bind("id", r.resource())
                                          .fetch()
                                          .rowsUpdated())
                                  .then(receipt(db, r));
                            })));
  }

  @Override
  public Mono<Receipt> open(Reservation expected) {
    return authorized(expected, Use.OPEN)
        .then(primary(expected))
        .flatMap(
            proof -> {
              if (proof.state() != State.COMMITTED)
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "ADMISSION_NOT_COMMITTED"));
              return tx.execute(
                  db ->
                      operationLock(db, expected)
                          .then(row(db, expected))
                          .switchIfEmpty(
                              Mono.error(fail(DomainStatus.NOT_FOUND, "OWNER_RECEIPT_NOT_FOUND")))
                          .flatMap(
                              row -> {
                                matching(expected, row);
                                if ("DELETED".equals(row.get("state")))
                                  return receipt(db, expected);
                                if (!"COMMITTED".equals(row.get("state")))
                                  return Mono.error(
                                      fail(DomainStatus.FAILED_PRECONDITION, "OPERATION_CLOSED"));
                                if (row.get("opened_at") != null) return receipt(db, expected);
                                // Receipt and project become OPEN atomically; the DB trigger
                                // requires this proof.
                                return db.sql(
                                        "UPDATE taska.resource_operation_receipts SET"
                                            + " opened_at=:now WHERE tenant_id=:t AND"
                                            + " operation_id=:op AND resource_kind='PROJECT'")
                                    .bind("t", expected.tenant())
                                    .bind("op", expected.operation())
                                    .bind("now", offset(clock.instant()))
                                    .fetch()
                                    .rowsUpdated()
                                    .then(
                                        db.sql(
                                                "UPDATE taska.projects SET admission_state='OPEN'"
                                                    + " WHERE tenant_id=:t AND id=:id AND"
                                                    + " admission_state='PROVISIONING'")
                                            .bind("t", expected.tenant())
                                            .bind("id", expected.resource())
                                            .fetch()
                                            .rowsUpdated())
                                    .flatMap(
                                        n -> {
                                          if (n != 1)
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "PROVISIONAL_PROJECT_MISSING"));
                                          return db.sql(
                                                  "INSERT INTO"
                                                      + " taska.outbox_events(tenant_id,aggregate_type,aggregate_id,event_type,payload)"
                                                      + " VALUES(:t,'project',:id,'ProjectAdmissionOpened',jsonb_build_object('projectId',CAST(:text"
                                                      + " AS text)))")
                                              .bind("t", expected.tenant())
                                              .bind("id", expected.resource())
                                              .bind("text", expected.resource().toString())
                                              .fetch()
                                              .rowsUpdated()
                                              .then(receipt(db, expected));
                                        });
                              }));
            });
  }

  @Override
  public Mono<Inventory> inventory(UUID tenant) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!tenant.toString().equals(c.tenantId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return policy
                  .require(c, Use.INVENTORY, null, null)
                  .timeout(Duration.ofSeconds(5))
                  .then(
                      tx.execute(
                          db ->
                              db.sql(
                                      "SELECT id FROM taska.projects WHERE tenant_id=:t AND"
                                          + " admission_state='OPEN' ORDER BY id LIMIT 10001")
                                  .bind("t", tenant)
                                  .map((r, m) -> r.get("id", UUID.class))
                                  .all()
                                  .collectList()
                                  .map(
                                      ids -> {
                                        if (ids.size() > 10000)
                                          throw fail(
                                              DomainStatus.RESOURCE_EXHAUSTED,
                                              "COMPLETE_INVENTORY_TOO_LARGE");
                                        return new Inventory(tenant, new HashSet<>(ids));
                                      })));
            });
  }

  /**
   * TAS-459 joins a confirmed physical deletion transaction, after every owned resource is gone.
   */
  public Mono<Void> recordDeleted(DatabaseClient db, Reservation expected) {
    return authorized(expected, Use.DELETE_RECEIPT)
        .then(operationLock(db, expected))
        .then(row(db, expected))
        .flatMap(
            row -> {
              matching(expected, row);
              if ("DELETED".equals(row.get("state"))) return Mono.empty();
              if (!"COMMITTED".equals(row.get("state")) || row.get("opened_at") == null)
                return Mono.error(
                    fail(DomainStatus.FAILED_PRECONDITION, "VISIBLE_PROJECT_REQUIRED"));
              return db.sql("SELECT id FROM taska.projects WHERE tenant_id=:t AND id=:id")
                  .bind("t", expected.tenant())
                  .bind("id", expected.resource())
                  .fetch()
                  .one()
                  .flatMap(
                      found ->
                          Mono.<Boolean>error(
                              fail(DomainStatus.FAILED_PRECONDITION, "PHYSICAL_DELETION_REQUIRED")))
                  .defaultIfEmpty(true)
                  .then(
                      db.sql(
                              "UPDATE taska.resource_operation_receipts SET state='DELETED' WHERE"
                                  + " tenant_id=:t AND operation_id=:op AND"
                                  + " resource_kind='PROJECT'")
                          .bind("t", expected.tenant())
                          .bind("op", expected.operation())
                          .fetch()
                          .rowsUpdated())
                  .then();
            });
  }

  private Mono<VerifiedTenantContext> authorized(Reservation r, Use use) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (!r.tenant().toString().equals(c.tenantId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return policy
                  .require(c, use, r.operation(), r.resource())
                  .timeout(Duration.ofSeconds(5))
                  .thenReturn(c);
            });
  }

  private Mono<Reservation> primary(Reservation expected) {
    return authority
        .get(expected.operation())
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ADMISSION_UNAVAILABLE")))
        .map(
            proof -> {
              if (!expected.sameIdentity(proof) || !expected.expiresAt().equals(proof.expiresAt()))
                throw fail(DomainStatus.FAILED_PRECONDITION, "STALE_FENCE");
              return proof;
            });
  }

  private Mono<Void> operationLock(DatabaseClient db, Reservation r) {
    return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .bind("key", r.tenant() + "/" + r.operation())
        .then();
  }

  private Mono<Map<String, Object>> row(DatabaseClient db, Reservation r) {
    return db.sql(
            "SELECT * FROM taska.resource_operation_receipts WHERE tenant_id=:t AND"
                + " operation_id=:op AND resource_kind='PROJECT' FOR UPDATE")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .fetch()
        .one();
  }

  private Mono<Void> insertReceipt(DatabaseClient db, Reservation r, String hash, String state) {
    var q =
        db.sql(
                "INSERT INTO"
                    + " taska.resource_operation_receipts(tenant_id,operation_id,resource_kind,resource_id,reservation_id,reservation_fence,reservation_expiry,state,amount,request_hash,committed_at)"
                    + " VALUES(:t,:op,'PROJECT',:resource,:reservation,:f,:expires,:state,1,:hash,:now)")
            .bind("t", r.tenant())
            .bind("op", r.operation())
            .bind("resource", r.resource())
            .bind("reservation", r.id())
            .bind("f", r.fence())
            .bind("expires", offset(r.expiresAt()))
            .bind("state", state);
    q =
        hash == null
            ? q.bindNull("hash", String.class).bindNull("now", OffsetDateTime.class)
            : q.bind("hash", hash).bind("now", offset(clock.instant()));
    return q.fetch().rowsUpdated().then();
  }

  private Mono<Receipt> receipt(DatabaseClient db, Reservation r) {
    return row(db, r)
        .map(
            row -> {
              matching(r, row);
              String state = (String) row.get("state");
              return new Receipt(
                  r,
                  switch (state) {
                    case "CANCELLED" -> OwnerState.CANCELLED;
                    case "DELETED" -> OwnerState.DELETED;
                    default ->
                        row.get("opened_at") == null ? OwnerState.PROVISIONING : OwnerState.OPEN;
                  });
            });
  }

  private static void matching(Reservation r, Map<String, Object> row) {
    if (!r.resource().equals(row.get("resource_id"))
        || !r.id().equals(row.get("reservation_id"))
        || r.fence() != ((Long) row.get("reservation_fence"))
        || !r.expiresAt().equals(((OffsetDateTime) row.get("reservation_expiry")).toInstant()))
      throw fail(DomainStatus.FAILED_PRECONDITION, "OWNER_IDENTITY_CONFLICT");
  }

  private static String hash(Create c) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      (c.key() + "\n" + c.name() + "\n" + c.creator())
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
