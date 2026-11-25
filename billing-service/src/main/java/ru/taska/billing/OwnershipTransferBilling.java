package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.security.tenant.OwnershipTransferGuard.id;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.OwnershipTransferReceipt;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.OwnershipTransferGuard;

/**
 * Финансовый fence и отзыв согласия: только подтверждённый Root outcome, без новых PSP операций.
 */
public final class OwnershipTransferBilling {
  private final TenantTransactionExecutor tx;
  private final SubscriptionStore subscriptions;
  private final EntitlementStore entitlements;
  private final BiFunction<UUID, UUID, Mono<OwnershipTransferReceipt>> roots;
  private final Clock clock;

  public OwnershipTransferBilling(
      TenantTransactionExecutor tx,
      SubscriptionStore subscriptions,
      EntitlementStore entitlements,
      BiFunction<UUID, UUID, Mono<OwnershipTransferReceipt>> roots,
      Clock clock) {
    this.tx = tx;
    this.subscriptions = subscriptions;
    this.entitlements = entitlements;
    this.roots = roots;
    this.clock = clock;
  }

  public Mono<OwnershipTransferFence> prepare(OwnershipTransferRequest q) {
    return OwnershipTransferGuard.require(
            q, "tenant-service", "prepareOwnershipTransfer", "ownership:prepare", true)
        .then(Mono.defer(() -> roots.apply(id(q.getTenantId()), id(q.getOperationId()))))
        .flatMap(
            root -> {
              binding(q, root);
              return tx.execute(
                  db ->
                      subscriptions
                          .lock(db, id(q.getTenantId()))
                          .flatMap(
                              subscription ->
                                  find(db, q)
                                      .map(
                                          row -> {
                                            matching(root, row);
                                            return dto(row);
                                          })
                                      .switchIfEmpty(
                                          Mono.defer(() -> create(db, q, root, subscription)))));
            });
  }

  private Mono<OwnershipTransferFence> create(
      DatabaseClient db,
      OwnershipTransferRequest q,
      OwnershipTransferReceipt root,
      Map<String, Object> subscription) {
    Instant now = clock.instant();
    if (root.getState() != OwnershipTransferReceipt.State.PENDING
        || !now.plusSeconds(5).isBefore(Instant.parse(root.getExpiresAt())))
      return Mono.error(fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_EXPIRED"));
    Object until = subscription.get("ownership_transfer_until");
    if (until != null) return Mono.error(fail(DomainStatus.ABORTED, "OWNERSHIP_TRANSFER_PENDING"));
    UUID tenant = id(q.getTenantId());
    return db.sql(
            "SELECT count(*) AS n FROM taska.subscription_plan_changes WHERE tenant_id=:t AND"
                + " state='PREPARED'")
        .bind("t", tenant)
        .map((r, m) -> r.get("n", Long.class))
        .one()
        .flatMap(
            pending -> {
              if (pending != 0)
                return Mono.error(fail(DomainStatus.ABORTED, "PLAN_CHANGE_PENDING"));
              return entitlements
                  .ownershipSnapshot(db, tenant)
                  .flatMap(
                      raw -> {
                        var entitlement = EntitlementProjection.validate(raw);
                        return db.sql(
                                "SELECT p.kind FROM taska.catalog_plan_versions v JOIN"
                                    + " taska.catalog_plans p USING(plan_code) WHERE v.id=:p")
                            .bind("p", id(entitlement.getPlanVersionId()))
                            .map((r, m) -> r.get("kind", String.class))
                            .one()
                            .switchIfEmpty(
                                Mono.error(fail(DomainStatus.UNAVAILABLE, "PLAN_NOT_FOUND")))
                            .flatMap(
                                kind -> {
                                  Instant end =
                                      min(now.plusSeconds(30), Instant.parse(root.getExpiresAt()));
                                  if (!root.getAdministrative()) {
                                    if (!"PAID".equals(kind)
                                        || !entitlement.hasPaidThrough()
                                        || !now.isBefore(
                                            Instant.parse(entitlement.getPaidThrough()))
                                        || entitlement.getRestrictionsList().stream()
                                            .anyMatch(
                                                r ->
                                                    r
                                                        != RestrictionCode
                                                            .RESTRICTION_CODE_OWNER_PAUSE))
                                      return Mono.error(
                                          fail(DomainStatus.FAILED_PRECONDITION, "READ_ONLY"));
                                    end = min(end, Instant.parse(entitlement.getPaidThrough()));
                                    if (entitlement.hasValidUntil())
                                      end = min(end, Instant.parse(entitlement.getValidUntil()));
                                  }
                                  if (!now.plusSeconds(5).isBefore(end))
                                    return Mono.error(
                                        fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_EXPIRED"));
                                  Instant expires = end;
                                  return db.sql(
                                          "INSERT INTO"
                                              + " taska.ownership_transfer_fences(tenant_id,operation_id,old_owner_id,old_owner_version,valid_until,entitlement_version,administrative,state,created_at)"
                                              + " VALUES(:t,:op,:owner,:ov,:until,:ev,:admin,'PREPARED',:now)"
                                              + " RETURNING *")
                                      .bind("t", tenant)
                                      .bind("op", id(q.getOperationId()))
                                      .bind("owner", id(root.getOldOwnerId()))
                                      .bind("ov", Long.parseLong(root.getOldOwnerVersion()))
                                      .bind("until", expires.atOffset(ZoneOffset.UTC))
                                      .bind("ev", Long.parseLong(entitlement.getVersion()))
                                      .bind("admin", root.getAdministrative())
                                      .bind("now", now.atOffset(ZoneOffset.UTC))
                                      .fetch()
                                      .one()
                                      .flatMap(
                                          row ->
                                              db.sql(
                                                      "UPDATE taska.subscriptions SET"
                                                          + " ownership_transfer_id=:op,ownership_transfer_until=:until"
                                                          + " WHERE tenant_id=:t")
                                                  .bind("op", id(q.getOperationId()))
                                                  .bind("until", expires.atOffset(ZoneOffset.UTC))
                                                  .bind("t", tenant)
                                                  .then()
                                                  .thenReturn(dto(row)));
                                });
                      });
            });
  }

  public Mono<OwnershipTransferFence> finish(OwnershipTransferRequest q) {
    return OwnershipTransferGuard.require(
            q, "tenant-service", "finishOwnershipTransfer", "ownership:finish", true)
        .then(Mono.defer(() -> roots.apply(id(q.getTenantId()), id(q.getOperationId()))))
        .flatMap(
            root -> {
              binding(q, root);
              if (root.getState() != OwnershipTransferReceipt.State.COMMITTED
                  && root.getState() != OwnershipTransferReceipt.State.CANCELLED)
                return Mono.error(fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_PENDING"));
              return tx.execute(
                  db ->
                      subscriptions
                          .lock(db, id(q.getTenantId()))
                          .flatMap(
                              subscription ->
                                  find(db, q)
                                      .flatMap(
                                          row -> {
                                            matching(root, row);
                                            if (!"PREPARED".equals(row.get("state")))
                                              return Mono.just(dto(row));
                                            boolean committed =
                                                root.getState()
                                                    == OwnershipTransferReceipt.State.COMMITTED;
                                            Instant end =
                                                ((OffsetDateTime) row.get("valid_until"))
                                                    .toInstant();
                                            if (committed
                                                && (root.getCommittedAt().isEmpty()
                                                    || root.getBillingFenceUntil().isEmpty()
                                                    || !end.equals(
                                                        Instant.parse(root.getBillingFenceUntil()))
                                                    || !Instant.parse(root.getCommittedAt())
                                                        .isBefore(end)))
                                              return Mono.error(
                                                  fail(
                                                      DomainStatus.UNAVAILABLE,
                                                      "INVALID_ROOT_RECEIPT"));
                                            UUID tenant = id(q.getTenantId());
                                            return db.sql(
                                                    "UPDATE taska.subscriptions SET"
                                                        + " ownership_transfer_id=NULL,ownership_transfer_until=NULL"
                                                        + " WHERE tenant_id=:t AND"
                                                        + " ownership_transfer_id=:op")
                                                .bind("t", tenant)
                                                .bind("op", id(q.getOperationId()))
                                                .then()
                                                .then(
                                                    committed
                                                        ? revoke(db, tenant, row)
                                                        : Mono.empty())
                                                .then(
                                                    db.sql(
                                                            "UPDATE taska.ownership_transfer_fences"
                                                                + " SET state=:state,finished_at=:now"
                                                                + " WHERE tenant_id=:t AND"
                                                                + " operation_id=:op RETURNING *")
                                                        .bind(
                                                            "state",
                                                            committed ? "FINISHED" : "CANCELLED")
                                                        .bind(
                                                            "now",
                                                            clock
                                                                .instant()
                                                                .atOffset(ZoneOffset.UTC))
                                                        .bind("t", tenant)
                                                        .bind("op", id(q.getOperationId()))
                                                        .fetch()
                                                        .one())
                                                .map(OwnershipTransferBilling::dto);
                                          })
                                      .switchIfEmpty(
                                          Mono.defer(
                                              () -> {
                                                if (root.getState()
                                                    != OwnershipTransferReceipt.State.CANCELLED)
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.UNAVAILABLE,
                                                          "OWNERSHIP_FENCE_NOT_FOUND"));
                                                // Отмена до Prepare не доказывает успешную передачу
                                                // и не отзывает mandate.
                                                return Mono.just(
                                                    OwnershipTransferFence.newBuilder()
                                                        .setTenantId(q.getTenantId())
                                                        .setOperationId(q.getOperationId())
                                                        .setOldOwnerId(root.getOldOwnerId())
                                                        .setOldOwnerVersion(
                                                            root.getOldOwnerVersion())
                                                        .setAdministrative(root.getAdministrative())
                                                        .build());
                                              }))));
            });
  }

  private Mono<Void> revoke(DatabaseClient db, UUID tenant, Map<String, Object> row) {
    return db.sql(
            "UPDATE taska.autopay_mandates SET"
                + " state='REVOKED',consent_version=consent_version+1,revoked_at=:now,revocation_reason='OWNER_TRANSFERRED'"
                + " WHERE tenant_id=:t AND owner_id=:o AND owner_version=:ov AND state='ACTIVE'")
        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
        .bind("t", tenant)
        .bind("o", row.get("old_owner_id"))
        .bind("ov", row.get("old_owner_version"))
        .fetch()
        .rowsUpdated()
        .flatMap(
            count ->
                count == 0
                    ? Mono.empty()
                    : db.sql(
                            "UPDATE taska.subscriptions SET"
                                + " autopay_state='DISABLED',version=version+1,updated_at=:now"
                                + " WHERE tenant_id=:t")
                        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
                        .bind("t", tenant)
                        .then());
  }

  private Mono<Map<String, Object>> find(DatabaseClient db, OwnershipTransferRequest q) {
    return db.sql(
            "SELECT * FROM taska.ownership_transfer_fences WHERE tenant_id=:t AND operation_id=:op")
        .bind("t", id(q.getTenantId()))
        .bind("op", id(q.getOperationId()))
        .fetch()
        .one();
  }

  private static void binding(OwnershipTransferRequest q, OwnershipTransferReceipt r) {
    if (!q.getTenantId().equals(r.getTenantId())
        || !q.getOperationId().equals(r.getOperationId())
        || r.getState() == OwnershipTransferReceipt.State.STATE_UNSPECIFIED
        || r.getState() == OwnershipTransferReceipt.State.UNRECOGNIZED)
      throw fail(DomainStatus.UNAVAILABLE, "INVALID_ROOT_RECEIPT");
    id(r.getOldOwnerId());
    id(r.getNewOwnerId());
    if (!r.getOldOwnerVersion().matches("[1-9][0-9]{0,18}"))
      throw fail(DomainStatus.UNAVAILABLE, "INVALID_ROOT_RECEIPT");
  }

  private static void matching(OwnershipTransferReceipt r, Map<String, Object> row) {
    if (!r.getOldOwnerId().equals(row.get("old_owner_id").toString())
        || !r.getOldOwnerVersion().equals(row.get("old_owner_version").toString())
        || r.getAdministrative() != (Boolean) row.get("administrative"))
      throw fail(DomainStatus.UNAVAILABLE, "INVALID_ROOT_RECEIPT");
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static OwnershipTransferFence dto(Map<String, Object> row) {
    return OwnershipTransferFence.newBuilder()
        .setTenantId(row.get("tenant_id").toString())
        .setOperationId(row.get("operation_id").toString())
        .setOldOwnerId(row.get("old_owner_id").toString())
        .setOldOwnerVersion(row.get("old_owner_version").toString())
        .setValidUntil(((OffsetDateTime) row.get("valid_until")).toInstant().toString())
        .setEntitlementVersion(row.get("entitlement_version").toString())
        .setAdministrative((Boolean) row.get("administrative"))
        .build();
  }
}
