package ru.taska.tenant;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.LeaveTenantRpcRequest;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.MutationOptions;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.SetTenantMemberRoleRpcRequest;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Serializes membership changes on the tenant row, including the last-admin decision. */
public final class TenantMembershipMutationService {
  private final TenantTransactionExecutor transactions;
  private final Clock clock;

  private record Command(UUID tenant, UUID actor, UUID target, String action, String role,
      String key, long expected, byte[] hash, boolean leave, String actorGeneration) {}

  public TenantMembershipMutationService(TenantTransactionExecutor transactions, Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
  }

  public Mono<Membership> setRole(SetTenantMemberRoleRpcRequest request) {
    return command(request.getTenantId(), request.getUserId(), request.getMutation(),
            "setTenantMemberRole", request.getPayload().getRole().name(), false)
        .flatMap(c -> transactions.execute(db -> execute(db, c).map(Result::membership)));
  }

  public Mono<Operation> leave(LeaveTenantRpcRequest request) {
    if (!request.getPayload().getConfirmed())
      return Mono.error(TenantRegistryService.fail(DomainStatus.FAILED_PRECONDITION,
          "CONFIRMATION_REQUIRED"));
    return command(request.getTenantId(), null, request.getMutation(), "leaveTenant", "REMOVED", true)
        .flatMap(c -> transactions.execute(db -> execute(db, c).map(r ->
            Operation.newBuilder().setId(r.operation().toString()).setType("leaveTenant")
                .setState(Operation.StateValue.STATE_SUCCEEDED)
                .setTenantId(c.tenant().toString()).setResourceId(c.target().toString())
                .setCreatedAt(r.created().toString()).setUpdatedAt(r.created().toString()).build())));
  }

  private Mono<Command> command(String tenantValue, String targetValue, MutationOptions mutation,
      String action, String roleValue, boolean leave) {
    return Mono.defer(() -> {
      UUID tenant = TenantRegistryService.uuid(tenantValue);
      if (!leave && !Set.of("ROLE_MEMBER", "ROLE_TENANT_ADMIN").contains(roleValue))
        throw TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
      String key = mutation.getIdempotencyKey();
      TenantRegistryService.uuid(key);
      if (!mutation.hasExpectedVersion())
        throw TenantRegistryService.fail(DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT");
      long expected;
      try {
        expected = Long.parseLong(mutation.getExpectedVersion());
        if (expected < 1) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        throw TenantRegistryService.fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
      }
      return VerifiedTenantContext.current()
          .onErrorMap(IllegalStateException.class, e -> TenantRegistryService.fail(
              DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
          .map(verified -> {
            var claims = RpcContextBridge.details(verified).getContext();
            if (!tenantValue.equals(verified.tenantId()) || !action.equals(verified.action()))
              throw TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
            boolean credential = switch (claims.getCredentialKind()) {
              case CREDENTIAL_KIND_WEB_TENANT -> claims.hasSessionId() && !claims.getSessionId().isBlank();
              case CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_USER_OAUTH ->
                  claims.hasCredentialId() && !claims.getCredentialId().isBlank();
              default -> false;
            };
            if (!credential || verified.actorId() == null || !verified.scopes().contains("write")
                || claims.getActorGeneration().isBlank() || !claims.hasMembershipGeneration()
                || claims.getMembershipGeneration().isBlank())
              throw TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
            UUID actor = TenantRegistryService.uuid(verified.actorId());
            UUID target = leave ? actor : TenantRegistryService.uuid(targetValue);
            byte[] hash = TenantRegistryService.digest("taska:membership-mutation:v1", action,
                tenant.toString(), target.toString(), roleValue, Long.toString(expected));
            return new Command(tenant, actor, target, action, roleValue, key, expected, hash,
                leave, claims.getMembershipGeneration());
          });
    });
  }

  private record Result(Membership membership, UUID operation, Instant created) {}

  private Mono<Result> execute(DatabaseClient db, Command c) {
    return db.sql("SELECT owner_id,lifecycle FROM taska.tenants WHERE id=:tenant FOR UPDATE")
        .bind("tenant", c.tenant()).fetch().one()
        .switchIfEmpty(Mono.error(TenantRegistryService.fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .flatMap(tenant -> {
          if (!"ACTIVE".equals(tenant.get("lifecycle")))
            return Mono.error(TenantRegistryService.fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"));
          UUID owner = (UUID) tenant.get("owner_id");
          Mono<Result> fresh = member(db, c.tenant(), c.actor())
              .switchIfEmpty(Mono.error(TenantRegistryService.fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
              .flatMap(actor -> {
                if (!"ACTIVE".equals(actor.get("status"))
                    || !actor.get("version").toString().equals(c.actorGeneration()))
                  return Mono.error(TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED,
                      "MEMBERSHIP_INACTIVE"));
                if (!c.leave() && !"TENANT_ADMIN".equals(actor.get("role")))
                  return Mono.error(TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED,
                      "SCOPE_DENIED"));
                Mono<Result> previous = c.leave() ? Mono.empty() : replay(db, c, owner);
                return previous
                    .switchIfEmpty(member(db, c.tenant(), c.target())
                        .switchIfEmpty(Mono.error(TenantRegistryService.fail(
                            DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                        .flatMap(target -> change(db, c, target, owner)));
              });
          // A successful self-leave revokes the actor's membership generation. The same signed
          // request can only retrieve its own saved receipt; it cannot perform another mutation.
          return c.leave() ? replay(db, c, owner).switchIfEmpty(fresh) : fresh;
        });
  }

  private Mono<Map<String, Object>> member(DatabaseClient db, UUID tenant, UUID user) {
    return db.sql("SELECT tenant_id,user_id,role,status,version,joined_at FROM"
        + " taska.tenant_memberships WHERE tenant_id=:tenant AND user_id=:user")
        .bind("tenant", tenant).bind("user", user).fetch().one();
  }

  private static byte[] bytes(Object value) {
    if (value instanceof byte[] array) return array;
    ByteBuffer buffer = ((ByteBuffer) value).duplicate();
    byte[] array = new byte[buffer.remaining()];
    buffer.get(array);
    return array;
  }

  private Mono<Result> replay(DatabaseClient db, Command c, UUID owner) {
    return db.sql("SELECT request_hash,operation_id,created_at,"
        + " response->>'role' AS role,response->>'status' AS status,"
        + " (response->>'version')::bigint AS version FROM taska.idempotency_records"
        + " WHERE tenant_id=:tenant AND actor_id=:actor AND operation_code=:action AND key=:key")
        .bind("tenant", c.tenant()).bind("actor", c.actor()).bind("action", c.action())
        .bind("key", c.key()).fetch().one().map(row -> {
          if (!MessageDigest.isEqual(c.hash(), bytes(row.get("request_hash"))))
            throw TenantRegistryService.fail(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT");
          Map<String, Object> member = new HashMap<>(row);
          member.put("tenant_id", c.tenant());
          member.put("user_id", c.target());
          member.put("owner_id", owner);
          return new Result(TenantMembershipService.publicMember(member),
              (UUID) row.get("operation_id"), ((OffsetDateTime) row.get("created_at")).toInstant());
        });
  }

  private Mono<Result> change(DatabaseClient db, Command c, Map<String, Object> target, UUID owner) {
    if (!"ACTIVE".equals(target.get("status")))
      return Mono.error(TenantRegistryService.fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE"));
    if (c.target().equals(owner) && (c.leave() || !"ROLE_TENANT_ADMIN".equals(c.role())))
      return Mono.error(TenantRegistryService.fail(DomainStatus.FAILED_PRECONDITION,
          "OWNER_MEMBERSHIP_PROTECTED"));
    if (((Number) target.get("version")).longValue() != c.expected())
      return Mono.error(TenantRegistryService.fail(DomainStatus.ABORTED, "VERSION_CONFLICT"));
    String oldRole = target.get("role").toString();
    String newRole = c.leave() ? oldRole : c.role().substring(5);
    String newStatus = c.leave() ? "REMOVED" : "ACTIVE";
    boolean changed = !oldRole.equals(newRole) || c.leave();
    Mono<Long> adminCount = "TENANT_ADMIN".equals(oldRole) &&
        (!"TENANT_ADMIN".equals(newRole) || c.leave())
        ? db.sql("SELECT count(*) AS n FROM taska.tenant_memberships WHERE tenant_id=:tenant"
            + " AND role='TENANT_ADMIN' AND status='ACTIVE'")
            .bind("tenant", c.tenant()).map((r, m) -> r.get("n", Long.class)).one()
        : Mono.just(2L);
    return adminCount.flatMap(count -> {
      if (count <= 1)
        return Mono.error(TenantRegistryService.fail(DomainStatus.FAILED_PRECONDITION, "LAST_ADMIN"));
      Instant now = clock.instant();
      UUID operation = UUID.randomUUID();
      Mono<Map<String, Object>> updated = changed
          ? db.sql("UPDATE taska.tenant_memberships SET role=:role,status=:status,"
              + " version=version+1,removed_at=CASE WHEN :leaving THEN :now ELSE removed_at END"
              + " WHERE tenant_id=:tenant AND user_id=:target RETURNING tenant_id,user_id,role,status,version")
              .bind("role", newRole).bind("status", newStatus).bind("leaving", c.leave())
              .bind("now", now.atOffset(ZoneOffset.UTC)).bind("tenant", c.tenant())
              .bind("target", c.target()).fetch().one()
          : Mono.just(target);
      return updated.flatMap(row -> {
        long version = ((Number) row.get("version")).longValue();
        Map<String, Object> response = new HashMap<>(row);
        response.put("owner_id", owner);
        Membership membership = TenantMembershipService.publicMember(response);
        Mono<?> event = changed
            ? db.sql("UPDATE taska.tenants SET membership_version=membership_version+1,"
                + " updated_at=:now WHERE id=:tenant")
                .bind("tenant", c.tenant()).bind("now", now.atOffset(ZoneOffset.UTC))
                .fetch().rowsUpdated()
                .then(db.sql("INSERT INTO taska.outbox_events"
                    + "(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,"
                    + "schema_version,payload,operation_id,status,created_at) VALUES"
                    + "(:id,:tenant,'MEMBERSHIP',:target,:version,'TenantMembershipChanged',1,"
                    + "jsonb_build_object('tenantId',:tenant::text,'userId',:target::text,"
                    + "'actorId',:actor::text,'role',:role,'status',:status,'result','SUCCEEDED'),"
                    + ":operation,'NEW',:now)" )
                    .bind("id", UUID.randomUUID()).bind("tenant", c.tenant())
                    .bind("target", c.target()).bind("version", version)
                    .bind("actor", c.actor()).bind("role", newRole).bind("status", newStatus)
                    .bind("operation", operation).bind("now", now.atOffset(ZoneOffset.UTC))
                    .fetch().rowsUpdated()) : Mono.empty();
        return event.then(db.sql("INSERT INTO taska.membership_audit"
            + "(id,tenant_id,actor_id,target_user_id,action,result,old_role,new_role,"
            + "old_status,new_status,membership_version,operation_id,created_at) VALUES"
            + "(:id,:tenant,:actor,:target,:action,:result,:oldRole,:newRole,'ACTIVE',"
            + ":newStatus,:version,:operation,:now)")
            .bind("id", UUID.randomUUID()).bind("tenant", c.tenant())
            .bind("actor", c.actor()).bind("target", c.target())
            .bind("action", c.action()).bind("result", changed ? "SUCCEEDED" : "NO_CHANGE")
            .bind("oldRole", oldRole).bind("newRole", newRole)
            .bind("newStatus", newStatus).bind("version", version)
            .bind("operation", operation).bind("now", now.atOffset(ZoneOffset.UTC))
            .fetch().rowsUpdated()).then(db.sql("INSERT INTO taska.idempotency_records"
            + "(tenant_id,actor_id,operation_code,key,request_hash,operation_id,state,response,"
            + "created_at,expires_at) VALUES (:tenant,:actor,:action,:key,:hash,:operation,"
            + "'SUCCEEDED',jsonb_build_object('role',:role,'status',:status,'version',:version),"
            + ":now,:expires)")
            .bind("tenant", c.tenant()).bind("actor", c.actor()).bind("action", c.action())
            .bind("key", c.key()).bind("hash", c.hash()).bind("operation", operation)
            .bind("role", newRole).bind("status", newStatus).bind("version", version)
            .bind("now", now.atOffset(ZoneOffset.UTC))
            .bind("expires", now.plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
            .fetch().rowsUpdated()).thenReturn(new Result(membership, operation, now));
      });
    });
  }
}
