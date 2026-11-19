package ru.taska.tenant;

import static ru.taska.security.tenant.OwnershipTransferGuard.id;
import static ru.taska.tenant.TenantRegistryService.fail;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.OwnershipTransferFence;
import ru.taska.api.b2b.v1.OwnershipTransferReceipt;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TransferTenantOwnershipRpcRequest;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.OwnershipTransferClient;
import ru.taska.security.tenant.OwnershipTransferGuard;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Root receipt и финансовый fence образуют восстанавливаемую передачу без сетевых вызовов в SQL.
 */
public final class TenantOwnershipTransfers {
  private final TenantTransactionExecutor tx;
  private final OwnershipTransferAuthority authority;
  private final OwnershipTransferClient billing;
  private final Clock clock;

  public TenantOwnershipTransfers(
      TenantTransactionExecutor tx,
      OwnershipTransferAuthority authority,
      OwnershipTransferClient billing,
      Clock clock) {
    this.tx = tx;
    this.authority = authority;
    this.billing = billing;
    this.clock = clock;
  }

  private record Command(
      TransferTenantOwnershipRpcRequest q,
      UUID tenant,
      UUID actor,
      UUID target,
      UUID key,
      long expected,
      String authority,
      byte[] hash) {}

  public Mono<Tenant> transfer(TransferTenantOwnershipRpcRequest q) {
    return Mono.defer(
        () -> {
          UUID tenant = id(q.getTenantId()),
              target = id(q.getPayload().getNewOwnerUserId()),
              key = id(q.getMutation().getIdempotencyKey());
          long expected;
          try {
            expected = Long.parseLong(q.getMutation().getExpectedVersion());
            if (expected < 1
                || !Long.toString(expected).equals(q.getMutation().getExpectedVersion()))
              throw new IllegalArgumentException();
          } catch (IllegalArgumentException e) {
            throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
          }
          if (!q.getPayload().getConfirmed()
              || !q.getUnknownFields().asMap().isEmpty()
              || !q.getPayload().getUnknownFields().asMap().isEmpty()
              || !q.getMutation().getUnknownFields().asMap().isEmpty())
            throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
          long version = expected;
          return VerifiedTenantContext.current()
              .flatMap(
                  v -> {
                    if (!tenant.toString().equals(v.tenantId())
                        || v.actorId() == null
                        || !"transferTenantOwnership".equals(v.action())
                        || !v.scopes().contains("write"))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                    var c = RpcContextBridge.details(v).getContext();
                    String auth =
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                TenantRegistryService.digest(
                                    c.getCredentialKind().name(),
                                    c.getSessionId(),
                                    c.getCredentialId(),
                                    c.getGrantId(),
                                    c.getGrantGeneration()));
                    var command =
                        new Command(
                            q,
                            tenant,
                            id(v.actorId()),
                            target,
                            key,
                            version,
                            auth,
                            TenantRegistryService.digest(
                                tenant.toString(),
                                target.toString(),
                                Long.toString(version),
                                "confirmed"));
                    return authority
                        .current(v)
                        .flatMap(
                            frame ->
                                tx.execute(
                                    db ->
                                        TenantCreationLifecycle.lock(db, tenant)
                                            .flatMap(
                                                row ->
                                                    authority
                                                        .member(
                                                            db,
                                                            row,
                                                            v,
                                                            TenantRenameService.administrative(
                                                                c, tenant))
                                                        .then(findKey(db, command))
                                                        .flatMap(
                                                            op -> {
                                                              if (!MessageDigest.isEqual(
                                                                  command.hash(),
                                                                  TenantRegistryService.binary(
                                                                      op.get("request_hash"))))
                                                                return Mono.error(
                                                                    fail(
                                                                        DomainStatus.ABORTED,
                                                                        "IDEMPOTENCY_CONFLICT"));
                                                              return Mono.just(op);
                                                            })
                                                        .switchIfEmpty(
                                                            Mono.defer(
                                                                () ->
                                                                    authority
                                                                        .allow(db, row, v, frame)
                                                                        .then(
                                                                            target(
                                                                                db, row, command))
                                                                        .then(
                                                                            prepare(
                                                                                db,
                                                                                row,
                                                                                command,
                                                                                frame.until())))))))
                        .flatMap(op -> run(command, v, op));
                  });
        });
  }

  // Ошибка или потеря ответа не доказывают отмену commit: fence снимает Recover
  // после известного Root outcome. PENDING отменяется worker по deadline (не более 30 с).
  private Mono<Tenant> run(Command command, VerifiedTenantContext v, Map<String, Object> op) {
    if ("COMMITTED".equals(op.get("state")))
      return complete(command.tenant(), (UUID) op.get("id")).thenReturn(response(op));
    if ("CANCELLED".equals(op.get("state")))
      return Mono.error(fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_CANCELLED"));
    UUID operation = (UUID) op.get("id");
    return billing
        .prepare(command.tenant(), operation)
        .flatMap(
            fence ->
                authority
                    .current(v)
                    .flatMap(
                        frame ->
                            tx.execute(
                                db ->
                                    TenantCreationLifecycle.lock(db, command.tenant())
                                        .flatMap(
                                            row ->
                                                find(db, command.tenant(), operation)
                                                    .flatMap(
                                                        current -> {
                                                          if ("COMMITTED"
                                                              .equals(current.get("state")))
                                                            return authority
                                                                .member(
                                                                    db,
                                                                    row,
                                                                    v,
                                                                    TenantRenameService
                                                                        .administrative(
                                                                            RpcContextBridge
                                                                                .details(v)
                                                                                .getContext(),
                                                                            command.tenant()))
                                                                .thenReturn(response(current));
                                                          if (!"PENDING"
                                                              .equals(current.get("state")))
                                                            return Mono.error(
                                                                fail(
                                                                    DomainStatus.ABORTED,
                                                                    "OWNERSHIP_OPERATION_CANCELLED"));
                                                          if (!command
                                                                  .actor()
                                                                  .equals(current.get("actor_id"))
                                                              || !command
                                                                  .authority()
                                                                  .equals(current.get("authority")))
                                                            return Mono.error(
                                                                fail(
                                                                    DomainStatus.PERMISSION_DENIED,
                                                                    "SCOPE_DENIED"));
                                                          if (!row.get("owner_id")
                                                                  .equals(
                                                                      current.get("old_owner_id"))
                                                              || !row.get("owner_version")
                                                                  .equals(
                                                                      current.get(
                                                                          "old_owner_version")))
                                                            return Mono.error(
                                                                fail(
                                                                    DomainStatus.ABORTED,
                                                                    "VERSION_CONFLICT"));
                                                          return authority
                                                              .allow(db, row, v, frame)
                                                              .then(target(db, row, command))
                                                              .then(
                                                                  commit(db, row, current, fence));
                                                        })))))
        .flatMap(result -> complete(command.tenant(), operation).thenReturn(result));
  }

  private Mono<Void> target(DatabaseClient db, Map<String, Object> row, Command c) {
    if (((Number) row.get("version")).longValue() != c.expected())
      return Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
    if (c.target().equals(row.get("owner_id")))
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));
    return db.sql("SELECT status FROM taska.tenant_memberships WHERE tenant_id=:t AND user_id=:u")
        .bind("t", c.tenant())
        .bind("u", c.target())
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE")))
        .flatMap(
            m ->
                "ACTIVE".equals(m.get("status"))
                    ? Mono.empty()
                    : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE")));
  }

  private Mono<Map<String, Object>> prepare(
      DatabaseClient db, Map<String, Object> row, Command c, Instant authorityUntil) {
    Instant now = clock.instant(), expires = now.plusSeconds(30);
    if (authorityUntil.isBefore(expires)) expires = authorityUntil;
    if (!now.plusSeconds(5).isBefore(expires))
      return Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
    UUID operation = UUID.randomUUID();
    var claims = c.q().getContext().getContext();
    return db.sql(
            "INSERT INTO"
                + " taska.tenant_ownership_transfers(tenant_id,id,actor_id,authority,request_key,request_hash,old_owner_id,old_owner_version,new_owner_id,expected_tenant_version,administrative,state,created_at,expires_at)"
                + " VALUES(:t,:id,:actor,:auth,:key,:hash,:old,:ov,:new,:version,:admin,'PENDING',:now,:expires)"
                + " RETURNING *")
        .bind("t", c.tenant())
        .bind("id", operation)
        .bind("actor", c.actor())
        .bind("auth", c.authority())
        .bind("key", c.key())
        .bind("hash", c.hash())
        .bind("old", row.get("owner_id"))
        .bind("ov", row.get("owner_version"))
        .bind("new", c.target())
        .bind("version", c.expected())
        .bind("admin", TenantRenameService.administrative(claims, c.tenant()))
        .bind("now", now.atOffset(ZoneOffset.UTC))
        .bind("expires", expires.atOffset(ZoneOffset.UTC))
        .fetch()
        .one()
        .flatMap(
            op ->
                db.sql(
                        "INSERT INTO"
                            + " taska.tenant_ownership_dispatch(tenant_id,operation_id,next_attempt_at)"
                            + " VALUES(:t,:op,:due)")
                    .bind("t", c.tenant())
                    .bind("op", operation)
                    .bind("due", ((OffsetDateTime) op.get("expires_at")))
                    .then()
                    .thenReturn(op));
  }

  private Mono<Tenant> commit(
      DatabaseClient db,
      Map<String, Object> row,
      Map<String, Object> op,
      OwnershipTransferFence fence) {
    UUID tenant = (UUID) row.get("id"), operation = (UUID) op.get("id");
    Instant until = Instant.parse(fence.getValidUntil()), now = clock.instant();
    if (!tenant.toString().equals(fence.getTenantId())
        || !operation.toString().equals(fence.getOperationId())
        || !op.get("old_owner_id").toString().equals(fence.getOldOwnerId())
        || !op.get("old_owner_version").toString().equals(fence.getOldOwnerVersion())
        || (Boolean) op.get("administrative") != fence.getAdministrative()
        || !now.plusSeconds(5).isBefore(until)
        || until.isAfter(((OffsetDateTime) op.get("expires_at")).toInstant()))
      return Mono.error(fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_EXPIRED"));
    return db.sql(
            "UPDATE taska.tenants SET"
                + " owner_id=:owner,owner_version=owner_version+1,version=version+1,membership_version=membership_version+1,updated_at=clock_timestamp()"
                + " WHERE id=:t AND version=:v AND clock_timestamp()+interval '5 seconds'<:until"
                + " RETURNING *")
        .bind("owner", op.get("new_owner_id"))
        .bind("t", tenant)
        .bind("v", op.get("expected_tenant_version"))
        .bind("until", until.atOffset(ZoneOffset.UTC))
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT")))
        .flatMap(
            updated ->
                db.sql(
                        "UPDATE taska.tenant_memberships SET"
                            + " role='TENANT_ADMIN',version=version+1"
                            + " WHERE tenant_id=:t AND user_id IN (:old,:new) AND"
                            + " role<>'TENANT_ADMIN'")
                    .bind("t", tenant)
                    .bind("old", op.get("old_owner_id"))
                    .bind("new", op.get("new_owner_id"))
                    .then()
                    .then(TenantRestrictions.decorate(db, updated))
                    .flatMap(
                        decorated -> {
                          Tenant result = TenantRegistryService.tenant(decorated);
                          return db.sql(
                                  "UPDATE taska.tenant_ownership_transfers SET"
                                      + " state='COMMITTED',committed_at=:now,billing_fence_until=:until,response=:result"
                                      + " WHERE tenant_id=:t AND id=:op AND state='PENDING'")
                              .bind("now", updated.get("updated_at"))
                              .bind("until", until.atOffset(ZoneOffset.UTC))
                              .bind(
                                  "result",
                                  Base64.getEncoder().encodeToString(result.toByteArray()))
                              .bind("t", tenant)
                              .bind("op", operation)
                              .then()
                              .then(
                                  db.sql(
                                          "INSERT INTO"
                                              + " taska.tenant_ownership_audit(tenant_id,operation_id,actor_id,old_owner_id,new_owner_id,owner_version,administrative,created_at)"
                                              + " VALUES(:t,:op,:actor,:old,:new,:ov,:admin,:now)")
                                      .bind("t", tenant)
                                      .bind("op", operation)
                                      .bind("actor", op.get("actor_id"))
                                      .bind("old", op.get("old_owner_id"))
                                      .bind("new", op.get("new_owner_id"))
                                      .bind("ov", updated.get("owner_version"))
                                      .bind("admin", op.get("administrative"))
                                      .bind("now", updated.get("updated_at"))
                                      .then())
                              .then(
                                  db.sql(
                                          "INSERT INTO"
                                              + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                                              + " VALUES(:id,:t,'TENANT',:t,:v,'TenantOwnershipTransferred',1,jsonb_build_object('tenantId',:t::text,'operationId',:op::text,'ownerVersion',:ov::text),:op,'NEW',:now)")
                                      .bind("id", UUID.randomUUID())
                                      .bind("t", tenant)
                                      .bind("v", updated.get("version"))
                                      .bind("op", operation)
                                      .bind("ov", updated.get("owner_version"))
                                      .bind("now", updated.get("updated_at"))
                                      .then())
                              .thenReturn(result);
                        }));
  }

  private Mono<Void> complete(UUID tenant, UUID operation) {
    return billing
        .finish(tenant, operation)
        .then(
            tx.execute(
                db ->
                    db.sql(
                            "UPDATE taska.tenant_ownership_dispatch SET completed_at=:now WHERE"
                                + " tenant_id=:t AND operation_id=:op AND completed_at IS NULL")
                        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
                        .bind("t", tenant)
                        .bind("op", operation)
                        .then()));
  }

  public Mono<OwnershipTransferReceipt> inspect(OwnershipTransferRequest q) {
    return OwnershipTransferGuard.require(
            q, "billing-service", "inspectOwnershipTransfer", "ownership:inspect", false)
        .then(
            tx.execute(
                db ->
                    find(db, id(q.getTenantId()), id(q.getOperationId()))
                        .map(TenantOwnershipTransfers::receipt)));
  }

  public Mono<OwnershipTransferReceipt> recover(OwnershipTransferRequest q) {
    UUID tenant = id(q.getTenantId()), operation = id(q.getOperationId());
    return OwnershipTransferGuard.require(
            q, "tenant-service", "recoverOwnershipTransfer", "ownership:recover", true)
        .then(
            tx.execute(
                db ->
                    TenantCreationLifecycle.lock(db, tenant)
                        .then(find(db, tenant, operation))
                        .flatMap(
                            op -> {
                              if (!"PENDING".equals(op.get("state"))) return Mono.just(op);
                              if (clock
                                  .instant()
                                  .isBefore(((OffsetDateTime) op.get("expires_at")).toInstant()))
                                return Mono.error(
                                    fail(DomainStatus.ABORTED, "OWNERSHIP_OPERATION_PENDING"));
                              return db.sql(
                                      "UPDATE taska.tenant_ownership_transfers SET"
                                          + " state='CANCELLED',failure_code='OWNERSHIP_OPERATION_EXPIRED'"
                                          + " WHERE tenant_id=:t AND id=:op AND state='PENDING'"
                                          + " RETURNING *")
                                  .bind("t", tenant)
                                  .bind("op", operation)
                                  .fetch()
                                  .one();
                            })))
        .flatMap(op -> complete(tenant, operation).thenReturn(receipt(op)));
  }

  private Mono<Map<String, Object>> find(DatabaseClient db, UUID tenant, UUID operation) {
    return db.sql("SELECT * FROM taska.tenant_ownership_transfers WHERE tenant_id=:t AND id=:op")
        .bind("t", tenant)
        .bind("op", operation)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "OPERATION_NOT_FOUND")));
  }

  private Mono<Map<String, Object>> findKey(DatabaseClient db, Command c) {
    return db.sql(
            "SELECT * FROM taska.tenant_ownership_transfers WHERE tenant_id=:t AND actor_id=:a AND"
                + " authority=:auth AND request_key=:key")
        .bind("t", c.tenant())
        .bind("a", c.actor())
        .bind("auth", c.authority())
        .bind("key", c.key())
        .fetch()
        .one();
  }

  private static Tenant response(Map<String, Object> op) {
    try {
      return Tenant.parseFrom(Base64.getDecoder().decode(op.get("response").toString()));
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new IllegalStateException("Повреждён receipt передачи владения", e);
    }
  }

  private static OwnershipTransferReceipt receipt(Map<String, Object> op) {
    var b =
        OwnershipTransferReceipt.newBuilder()
            .setTenantId(op.get("tenant_id").toString())
            .setOperationId(op.get("id").toString())
            .setState(OwnershipTransferReceipt.State.valueOf(op.get("state").toString()))
            .setOldOwnerId(op.get("old_owner_id").toString())
            .setOldOwnerVersion(op.get("old_owner_version").toString())
            .setNewOwnerId(op.get("new_owner_id").toString())
            .setExpectedTenantVersion(op.get("expected_tenant_version").toString())
            .setExpiresAt(((OffsetDateTime) op.get("expires_at")).toInstant().toString())
            .setAdministrative((Boolean) op.get("administrative"));
    if (op.get("committed_at") != null)
      b.setCommittedAt(((OffsetDateTime) op.get("committed_at")).toInstant().toString());
    if (op.get("billing_fence_until") != null)
      b.setBillingFenceUntil(
          ((OffsetDateTime) op.get("billing_fence_until")).toInstant().toString());
    return b.build();
  }
}
