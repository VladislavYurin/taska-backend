package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;
import static ru.taska.tenant.TenantRegistryService.uuid;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.api.b2b.v1.RenameTenantRpcRequest;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Rename, receipt, audit and event commit under the same tenant row lock. */
public final class TenantRenameService {
  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  public TenantRenameService(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
  }

  public Mono<Tenant> rename(RenameTenantRpcRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = uuid(request.getTenantId());
          uuid(request.getMutation().getIdempotencyKey());
          TenantRegistryService.validateName(request.getPayload().getName());
          if (!request.getUnknownFields().asMap().isEmpty()
              || !request.getPayload().getUnknownFields().asMap().isEmpty()
              || !request.getMutation().getUnknownFields().asMap().isEmpty())
            throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
          if (!request.getMutation().hasExpectedVersion())
            throw fail(DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT");
          long expected = version(request.getMutation().getExpectedVersion());
          return VerifiedTenantContext.current()
              .onErrorMap(
                  IllegalStateException.class,
                  e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
              .flatMap(
                  verified -> {
                    TrustedTenantContext claims = RpcContextBridge.details(verified).getContext();
                    if (!tenant.toString().equals(verified.tenantId()))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
                    if (!"renameTenant".equals(verified.action())
                        || verified.actorId() == null
                        || !verified.scopes().contains("write")
                        || claims.getActorGeneration().isBlank())
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                    boolean admin = administrative(claims, tenant);
                    if (!admin && !ordinary(claims))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
                    UUID actor = uuid(verified.actorId());
                    String authority =
                        Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                TenantRegistryService.digest(
                                    claims.getCredentialKind().name(),
                                    claims.getSessionId(),
                                    claims.getCredentialId(),
                                    claims.getGrantId(),
                                    claims.getGrantGeneration()));
                    String operation = "renameTenant/" + authority;
                    byte[] hash =
                        TenantRegistryService.digest(
                            "renameTenant",
                            tenant.toString(),
                            request.getPayload().getName(),
                            Long.toString(expected));
                    return transactions.execute(
                        db ->
                            TenantCreationLifecycle.lock(db, tenant)
                                .flatMap(
                                    row ->
                                        authorize(db, row, actor, claims, admin)
                                            .then(replay(db, request, actor, operation, hash))
                                            .switchIfEmpty(
                                                Mono.defer(
                                                    () ->
                                                        change(
                                                            db, request, row, actor, operation,
                                                            hash, expected, admin)))));
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

  private static boolean ordinary(TrustedTenantContext claims) {
    boolean credential =
        switch (claims.getCredentialKind()) {
          case CREDENTIAL_KIND_WEB_TENANT ->
              claims.hasSessionId() && !claims.getSessionId().isBlank();
          case CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_USER_OAUTH ->
              claims.hasCredentialId() && !claims.getCredentialId().isBlank();
          default -> false;
        };
    return credential
        && claims.hasMembershipGeneration()
        && !claims.getMembershipGeneration().isBlank();
  }

  private static boolean administrative(TrustedTenantContext claims, UUID tenant) {
    boolean credential =
        switch (claims.getCredentialKind()) {
          case CREDENTIAL_KIND_WEB_GLOBAL ->
              claims.hasSessionId() && !claims.getSessionId().isBlank();
          case CREDENTIAL_KIND_ADMIN_PAT, CREDENTIAL_KIND_ADMIN_OAUTH ->
              claims.hasCredentialId()
                  && !claims.getCredentialId().isBlank()
                  && claims.hasGrantId()
                  && !claims.getGrantId().isBlank()
                  && claims.hasGrantGeneration()
                  && !claims.getGrantGeneration().isBlank();
          default -> false;
        };
    // The transport Authority checks current GLOBAL_ADMIN and session MFA/grant online.
    var scope = claims.getAdministrativeScope();
    boolean target =
        scope.getKind() == AdminScope.KindValue.KIND_PLATFORM
            || (scope.getKind() == AdminScope.KindValue.KIND_TENANT_SET
                && scope.getTenantIdsList().contains(tenant.toString()));
    return credential
        && target
        && claims.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_MODIFY;
  }

  private Mono<Void> authorize(
      DatabaseClient db,
      Map<String, Object> row,
      UUID actor,
      TrustedTenantContext claims,
      boolean admin) {
    if ("DELETED".equals(row.get("lifecycle")))
      return Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND"));
    if (admin) {
      return "DELETING".equals(row.get("lifecycle"))
          ? Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"))
          : Mono.empty();
    }
    return db.sql(
            "SELECT status,version FROM taska.tenant_memberships"
                + " WHERE tenant_id=:tenant AND user_id=:actor")
        .bind("tenant", row.get("id"))
        .bind("actor", actor)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .flatMap(
            member -> {
              if (!"ACTIVE".equals(member.get("status")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE"));
              if (!claims.getMembershipGeneration().equals(member.get("version").toString()))
                return Mono.error(fail(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
              if (!actor.equals(row.get("owner_id")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED"));
              TenantCreationLifecycle.requireWorking(TenantRegistryService.tenant(row));
              return Mono.empty();
            });
  }

  private Mono<Tenant> replay(
      DatabaseClient db,
      RenameTenantRpcRequest request,
      UUID actor,
      String operation,
      byte[] hash) {
    return db.sql(
            "SELECT request_hash,response->>'tenant' AS result FROM taska.idempotency_records"
                + " WHERE tenant_id=:tenant AND actor_id=:actor AND operation_code=:operation"
                + " AND key=:key")
        .bind("tenant", uuid(request.getTenantId()))
        .bind("actor", actor)
        .bind("operation", operation)
        .bind("key", request.getMutation().getIdempotencyKey())
        .fetch()
        .one()
        .map(
            row -> {
              if (!MessageDigest.isEqual(
                  hash, TenantRegistryService.binary(row.get("request_hash"))))
                throw fail(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT");
              try {
                return Tenant.parseFrom(Base64.getDecoder().decode(row.get("result").toString()));
              } catch (com.google.protobuf.InvalidProtocolBufferException error) {
                throw new IllegalStateException("Invalid stored tenant rename receipt", error);
              }
            });
  }

  private Mono<Tenant> change(
      DatabaseClient db,
      RenameTenantRpcRequest request,
      Map<String, Object> row,
      UUID actor,
      String operation,
      byte[] hash,
      long expected,
      boolean admin) {
    if (((Number) row.get("version")).longValue() != expected)
      return Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
    String name = request.getPayload().getName();
    boolean changed = !name.equals(row.get("name"));
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    UUID operationId = UUID.randomUUID();
    Mono<Map<String, Object>> updated =
        changed
            ? db.sql(
                    "UPDATE taska.tenants SET name=:name,version=version+1,updated_at=:now"
                        + " WHERE id=:tenant AND version=:expected RETURNING *")
                .bind("name", name)
                .bind("tenant", row.get("id"))
                .bind("expected", expected)
                .bind("now", now)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT")))
            : Mono.just(row);
    return updated.flatMap(
        current -> {
          Tenant result = TenantRegistryService.tenant(current);
          Mono<Void> event =
              changed
                  ? db.sql(
                          """
                          INSERT INTO taska.outbox_events
                            (id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                             schema_version,payload,operation_id,status,created_at)
                          VALUES (:id,:tenant,'TENANT',:tenant,:version,'TenantRenamed',1,
                            jsonb_build_object('tenantId',:tenant::text,'actorId',:actor::text,
                              'version',:version::text,'operationId',:operation::text),
                            :operation,'NEW',:now)
                          """)
                      .bind("id", UUID.randomUUID())
                      .bind("tenant", row.get("id"))
                      .bind("actor", actor)
                      .bind("version", current.get("version"))
                      .bind("operation", operationId)
                      .bind("now", now)
                      .then()
                  : Mono.empty();
          return event
              .then(
                  db.sql(
                          """
                          INSERT INTO taska.tenant_rename_audit
                            (id,tenant_id,actor_id,administrative,result,old_name,new_name,
                             tenant_version,operation_id,created_at)
                          VALUES (:id,:tenant,:actor,:admin,:result,:oldName,:newName,
                            :version,:operation,:now)
                          """)
                      .bind("id", UUID.randomUUID())
                      .bind("tenant", row.get("id"))
                      .bind("actor", actor)
                      .bind("admin", admin)
                      .bind("result", changed ? "SUCCEEDED" : "NO_CHANGE")
                      .bind("oldName", row.get("name"))
                      .bind("newName", name)
                      .bind("version", current.get("version"))
                      .bind("operation", operationId)
                      .bind("now", now)
                      .then())
              .then(
                  db.sql(
                          """
                          INSERT INTO taska.idempotency_records
                            (tenant_id,actor_id,operation_code,key,request_hash,operation_id,
                             state,response,created_at,expires_at)
                          VALUES (:tenant,:actor,:operation,:key,:hash,:id,'SUCCEEDED',
                            jsonb_build_object('tenant',:result::text),:now,:expires)
                          """)
                      .bind("tenant", row.get("id"))
                      .bind("actor", actor)
                      .bind("operation", operation)
                      .bind("key", request.getMutation().getIdempotencyKey())
                      .bind("hash", hash)
                      .bind("id", operationId)
                      .bind("result", Base64.getEncoder().encodeToString(result.toByteArray()))
                      .bind("now", now)
                      .bind("expires", now.plus(Duration.ofHours(24)))
                      .then())
              .thenReturn(result);
        });
  }
}
