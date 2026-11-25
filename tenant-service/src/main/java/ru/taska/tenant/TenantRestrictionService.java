package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.*;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/** Independent owner/platform reasons, serialized with other tenant commands. */
public final class TenantRestrictionService {
  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  public TenantRestrictionService(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
  }

  public Mono<Tenant> pause(PauseTenantRpcRequest q) {
    return command(
        q.getTenantId(),
        q.getMutation(),
        "pauseTenant",
        "OWNER_PAUSE",
        true,
        q.getPayload().getConfirmed(),
        "OWNER_PAUSE");
  }

  public Mono<Tenant> resume(ResumeTenantRpcRequest q) {
    return command(
        q.getTenantId(),
        q.getMutation(),
        "resumeTenant",
        "OWNER_PAUSE",
        false,
        true,
        "OWNER_RESUME");
  }

  public Mono<Tenant> block(SetPlatformBlockRpcRequest q) {
    return command(
        q.getTenantId(),
        q.getMutation(),
        "setPlatformBlock",
        "PLATFORM_BLOCK",
        q.getPayload().getEnabled(),
        q.getPayload().getConfirmed(),
        q.getPayload().getReason());
  }

  private Mono<Tenant> command(
      String target,
      MutationOptions mutation,
      String action,
      String kind,
      boolean enabled,
      boolean confirmed,
      String reason) {
    return Mono.defer(
        () -> {
          UUID tenant = uuid(target);
          uuid(mutation.getIdempotencyKey());
          if (!confirmed)
            return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "CONFIRMATION_REQUIRED"));
          if (reason == null || reason.isBlank() || reason.length() > 2000)
            return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED"));
          if (!mutation.hasExpectedVersion())
            return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT"));
          long expected = version(mutation.getExpectedVersion());
          return VerifiedTenantContext.current()
              .onErrorMap(
                  IllegalStateException.class,
                  e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
              .flatMap(
                  v -> {
                    var c = RpcContextBridge.details(v).getContext();
                    if (!target.equals(v.tenantId())
                        || !action.equals(v.action())
                        || v.actorId() == null
                        || !v.scopes().contains("write")
                        || c.getActorGeneration().isBlank())
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                    boolean admin = TenantRenameService.administrative(c, tenant);
                    if ((kind.equals("PLATFORM_BLOCK") && !admin)
                        || (!admin && !TenantRenameService.ordinary(c)))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "ADMIN_SCOPE_DENIED"));
                    UUID actor = uuid(v.actorId());
                    String authority =
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                digest(
                                    c.getCredentialKind().name(),
                                    c.getSessionId(),
                                    c.getCredentialId(),
                                    c.getGrantId(),
                                    c.getGrantGeneration()));
                    String operation = "tenantRestriction/" + authority;
                    byte[] hash =
                        digest(
                            action,
                            target,
                            Boolean.toString(enabled),
                            reason,
                            Long.toString(expected));
                    return transactions.execute(
                        db ->
                            TenantCreationLifecycle.lock(db, tenant)
                                .flatMap(
                                    row ->
                                        TenantRenameService.authorize(db, row, actor, c, admin)
                                            .then(
                                                replay(
                                                    db,
                                                    tenant,
                                                    actor,
                                                    operation,
                                                    mutation.getIdempotencyKey(),
                                                    hash))
                                            .switchIfEmpty(
                                                Mono.defer(
                                                    () ->
                                                        gates(db, row, admin, kind, enabled)
                                                            .then(
                                                                change(
                                                                    db,
                                                                    row,
                                                                    actor,
                                                                    operation,
                                                                    mutation.getIdempotencyKey(),
                                                                    hash,
                                                                    expected,
                                                                    action,
                                                                    kind,
                                                                    enabled,
                                                                    reason,
                                                                    admin))))));
                  });
        });
  }

  private static long version(String value) {
    try {
      long result = Long.parseLong(value);
      if (result < 1 || !Long.toString(result).equals(value)) throw new IllegalArgumentException();
      return result;
    } catch (IllegalArgumentException error) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private Mono<Void> gates(
      DatabaseClient db, Map<String, Object> row, boolean admin, String kind, boolean enabled) {
    if (admin) return Mono.empty();
    return TenantRestrictions.decorate(db, row)
        .flatMap(
            current -> {
              var reasons = TenantRestrictions.codes(current.get("independent_restrictions"));
              // Approved OWNER_CONTROL policy; proposed relaxation is tracked separately in
              // TAS-535.
              for (var code :
                  List.of(
                      RestrictionCode.RESTRICTION_CODE_PLATFORM_BLOCK,
                      RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE))
                if (reasons.contains(code))
                  return Mono.error(
                      new ru.taska.exception.DomainException(
                          DomainStatus.PERMISSION_DENIED,
                          "CAPABILITY_DISABLED",
                          Map.of(
                              "code",
                              "CAPABILITY_DISABLED",
                              "reason",
                              code.name().replace("RESTRICTION_CODE_", ""))));
              if (!enabled || !kind.equals("OWNER_PAUSE")) return Mono.empty();
              return db.sql(
                      "SELECT COALESCE((SELECT v.value::text FROM"
                          + " taska.platform_configuration_versions v WHERE v.key=d.key AND"
                          + " v.effective_at<=:now ORDER BY v.version DESC LIMIT"
                          + " 1),d.default_value::text) AS value FROM"
                          + " taska.platform_configuration_definitions d WHERE d.key='OWNER_PAUSE'")
                  .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
                  .map((r, m) -> r.get("value", String.class))
                  .one()
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED")))
                  .flatMap(
                      value ->
                          "true".equals(value)
                              ? Mono.empty()
                              : Mono.error(
                                  fail(DomainStatus.PERMISSION_DENIED, "CAPABILITY_DISABLED")));
            });
  }

  private Mono<Tenant> replay(
      DatabaseClient db, UUID tenant, UUID actor, String operation, String key, byte[] hash) {
    return db.sql(
            "SELECT request_hash,response->>'tenant' AS response FROM taska.idempotency_records"
                + " WHERE tenant_id=:t AND actor_id=:a AND operation_code=:o AND key=:k")
        .bind("t", tenant)
        .bind("a", actor)
        .bind("o", operation)
        .bind("k", key)
        .fetch()
        .one()
        .map(
            row -> {
              if (!MessageDigest.isEqual(hash, binary(row.get("request_hash"))))
                throw fail(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT");
              try {
                return Tenant.parseFrom(Base64.getDecoder().decode(row.get("response").toString()));
              } catch (com.google.protobuf.InvalidProtocolBufferException error) {
                throw new IllegalStateException("Invalid restriction receipt", error);
              }
            });
  }

  private Mono<Tenant> change(
      DatabaseClient db,
      Map<String, Object> row,
      UUID actor,
      String operation,
      String key,
      byte[] hash,
      long expected,
      String action,
      String kind,
      boolean enabled,
      String reason,
      boolean admin) {
    if (((Number) row.get("version")).longValue() != expected)
      return Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
    UUID tenant = (UUID) row.get("id");
    return db.sql(
            "SELECT active FROM taska.tenant_restrictions WHERE tenant_id=:t AND kind=:k AND"
                + " source_id=:t")
        .bind("t", tenant)
        .bind("k", kind)
        .map((r, m) -> r.get("active", Boolean.class))
        .one()
        .defaultIfEmpty(false)
        .flatMap(
            active -> {
              boolean changed = active != enabled;
              var now = clock.instant().atOffset(ZoneOffset.UTC);
              UUID id = UUID.randomUUID();
              Mono<Map<String, Object>> update =
                  changed
                      ? db.sql(
                              "UPDATE taska.tenants SET version=version+1,"
                                  + " restriction_version=restriction_version+1,updated_at=:now"
                                  + " WHERE id=:t AND version=:v RETURNING *")
                          .bind("t", tenant)
                          .bind("v", expected)
                          .bind("now", now)
                          .fetch()
                          .one()
                          .switchIfEmpty(Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT")))
                      : Mono.just(row);
              return update.flatMap(
                  current -> {
                    Mono<Void> write =
                        changed
                            ? db.sql(
                                    "INSERT INTO taska.tenant_restrictions"
                                        + " (tenant_id,kind,source_id,active,version,reason_code,created_by,created_at,resolved_at)"
                                        + " VALUES(:t,:k,:t,:active,:v,:k,:a,:now,CASE WHEN :active"
                                        + " THEN NULL ELSE :now END) ON"
                                        + " CONFLICT(tenant_id,kind,source_id) DO UPDATE SET"
                                        + " active=EXCLUDED.active,"
                                        + " version=EXCLUDED.version,reason_code=EXCLUDED.reason_code,created_by=EXCLUDED.created_by,"
                                        + " created_at=CASE WHEN EXCLUDED.active THEN"
                                        + " EXCLUDED.created_at ELSE"
                                        + " taska.tenant_restrictions.created_at END,"
                                        + " resolved_at=EXCLUDED.resolved_at")
                                .bind("t", tenant)
                                .bind("k", kind)
                                .bind("active", enabled)
                                .bind("v", current.get("restriction_version"))
                                .bind("a", actor)
                                .bind("now", now)
                                .then()
                            : Mono.empty();
                    Mono<Void> event =
                        changed
                            ? db.sql(
                                    "INSERT INTO taska.outbox_events"
                                        + " (id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                                        + " VALUES(:id,:t,'TENANT',:t,:v,'TenantRestrictionChanged',1,jsonb_build_object("
                                        + " 'tenantId',:t::text,'actorId',:a::text,'kind',:k,'active',:active,'version',:v::text,"
                                        + " 'restrictionVersion',:rv::text,'operationId',:op::text),:op,'NEW',:now)")
                                .bind("id", UUID.randomUUID())
                                .bind("t", tenant)
                                .bind("a", actor)
                                .bind("k", kind)
                                .bind("active", enabled)
                                .bind("v", current.get("version"))
                                .bind("rv", current.get("restriction_version"))
                                .bind("op", id)
                                .bind("now", now)
                                .then()
                            : Mono.empty();
                    return write
                        .then(event)
                        .then(TenantRestrictions.decorate(db, current))
                        .map(TenantRegistryService::tenant)
                        .flatMap(
                            result ->
                                db.sql(
                                        "INSERT INTO taska.tenant_restriction_audit"
                                            + " (id,tenant_id,actor_id,action,administrative,reason,result,tenant_version,restriction_version,operation_id,created_at)"
                                            + " VALUES(:id,:t,:a,:action,:admin,:reason,:result,:v,:rv,:op,:now)")
                                    .bind("id", UUID.randomUUID())
                                    .bind("t", tenant)
                                    .bind("a", actor)
                                    .bind("action", action)
                                    .bind("admin", admin)
                                    .bind("reason", reason)
                                    .bind("result", changed ? "SUCCEEDED" : "NO_CHANGE")
                                    .bind("v", current.get("version"))
                                    .bind("rv", current.get("restriction_version"))
                                    .bind("op", id)
                                    .bind("now", now)
                                    .then()
                                    .then(
                                        db.sql(
                                                "INSERT INTO taska.idempotency_records"
                                                    + " (tenant_id,actor_id,operation_code,key,request_hash,operation_id,state,response,created_at,expires_at)"
                                                    + " VALUES(:t,:a,:o,:k,:hash,:id,'SUCCEEDED',jsonb_build_object('tenant',:result::text),:now,:expires)")
                                            .bind("t", tenant)
                                            .bind("a", actor)
                                            .bind("o", operation)
                                            .bind("k", key)
                                            .bind("hash", hash)
                                            .bind("id", id)
                                            .bind(
                                                "result",
                                                Base64.getEncoder()
                                                    .encodeToString(result.toByteArray()))
                                            .bind("now", now)
                                            .bind("expires", now.plusDays(1))
                                            .then())
                                    .thenReturn(result));
                  });
            });
  }
}
