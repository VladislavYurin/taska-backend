package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;
import static ru.taska.tenant.TenantRegistryService.uuid;

import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.GetTenantOperationRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Durable saga. Network calls never hold a local SQL transaction or pooled connection. */
public final class TenantProvisioning {
  public static final UUID DEFAULT_TEMPLATE =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final TenantTransactionExecutor transactions;
  private final Dependencies dependencies;
  private final Clock clock;

  public interface Dependencies {
    Mono<Void> workflow(UUID tenant, UUID operation, UUID template);

    Mono<Void> billing(UUID tenant, UUID operation, UUID plan, boolean free);
  }

  public TenantProvisioning(
      TenantTransactionExecutor transactions, Dependencies dependencies, Clock clock) {
    this.transactions = transactions;
    this.dependencies = dependencies;
    this.clock = clock;
  }

  static Mono<Void> insert(
      DatabaseClient db,
      UUID tenant,
      UUID actor,
      UUID operation,
      String key,
      byte[] hash,
      UUID plan,
      boolean free,
      Instant now) {
    return db.sql(
            """
            INSERT INTO taska.tenant_operations(id,tenant_id,kind,actor_id,channel,request_key,request_hash,payload,created_at,updated_at)
            VALUES(:op,:tenant,'TENANT_PROVISION',:actor,'WEB_GLOBAL',:key,:hash,
              jsonb_build_object('planVersionId',:plan::text,'creationKind',:kind::text,'templateId',:template::text),:now,:now)
            """)
        .bind("op", operation)
        .bind("tenant", tenant)
        .bind("actor", actor)
        .bind("key", key)
        .bind("hash", hash)
        .bind("plan", plan)
        .bind("kind", free ? "FREE" : "PAID")
        .bind("template", DEFAULT_TEMPLATE)
        .bind("now", at(now))
        .then()
        .then(
            db.sql(
                    "INSERT INTO"
                        + " taska.tenant_provisioning_dispatch(tenant_id,operation_id,next_attempt_at)"
                        + " VALUES(:tenant,:op,:now)")
                .bind("tenant", tenant)
                .bind("op", operation)
                .bind("now", at(now))
                .then());
  }

  static Mono<Operation> operation(DatabaseClient db, UUID tenant, UUID id) {
    return db.sql("SELECT * FROM taska.tenant_operations WHERE tenant_id=:tenant AND id=:id")
        .bind("tenant", tenant)
        .bind("id", id)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .map(TenantProvisioning::view);
  }

  private static Operation view(Map<String, Object> row) {
    String state = row.get("state").toString();
    var result =
        Operation.newBuilder()
            .setId(row.get("id").toString())
            .setType("TENANT_PROVISION")
            .setState(
                Operation.StateValue.valueOf(
                    "STATE_" + (state.equals("ACCEPTED") ? "PENDING" : state)))
            .setTenantId(row.get("tenant_id").toString())
            .setResourceId(row.get("tenant_id").toString())
            .setCreatedAt(((OffsetDateTime) row.get("created_at")).toInstant().toString())
            .setUpdatedAt(((OffsetDateTime) row.get("updated_at")).toInstant().toString());
    if (row.get("failure_code") instanceof String code)
      result.setFailureCode(ErrorCode.valueOf("ERROR_CODE_" + code));
    return result.build();
  }

  public Mono<Operation> get(GetTenantOperationRpcRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = uuid(request.getTenantId()), id = uuid(request.getOperationId());
          return VerifiedTenantContext.current()
              .flatMap(
                  v -> {
                    var c = RpcContextBridge.details(v).getContext();
                    boolean global =
                        c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                            && v.tenantId() == null;
                    boolean scoped =
                        c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                            && tenant.toString().equals(v.tenantId());
                    if (!"getTenantOperation".equals(v.action())
                        || v.actorId() == null
                        || !c.hasSessionId()
                        || !v.scopes().contains("read")
                        || (!global && !scoped))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SESSION_REQUIRED"));
                    // Global sessions may inspect only their own accepted creation. They never mint
                    // a tenant session.
                    Mono<Operation> read =
                        transactions.execute(
                            db ->
                                db.sql(
                                        """
                                        SELECT o.* FROM taska.tenant_operations o JOIN taska.tenants t ON t.id=o.tenant_id
                                        JOIN taska.tenant_memberships m ON m.tenant_id=t.id AND m.user_id=:actor
                                        WHERE o.tenant_id=:tenant AND o.id=:id AND o.actor_id=:actor AND m.status='ACTIVE' AND t.lifecycle<>'DELETED'
                                        """)
                                    .bind("tenant", tenant)
                                    .bind("id", id)
                                    .bind("actor", uuid(v.actorId()))
                                    .fetch()
                                    .one()
                                    .switchIfEmpty(
                                        Mono.error(
                                            fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                                    .map(TenantProvisioning::view));
                    return global
                        ? new ru.taska.security.tenant.ProvisioningReadTransaction()
                            .execute(tenant, read)
                        : read;
                  });
        });
  }

  private record Claim(
      UUID tenant, UUID id, long fence, int step, UUID plan, UUID template, boolean free) {}

  public Mono<Operation> resume(UUID tenant, UUID id) {
    return VerifiedTenantContext.current()
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!tenant.toString().equals(v.tenantId())
                  || !"provisionTenant".equals(v.action())
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !"tenant-service".equals(c.getIssuerService())
                  || !v.scopes().contains("write"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return claim(tenant, id)
                  .flatMap(
                      claim -> advance(claim).onErrorResume(error -> recordFailure(claim, error)))
                  .then(transactions.execute(db -> operation(db, tenant, id)));
            });
  }

  private Mono<Claim> claim(UUID tenant, UUID id) {
    Instant now = clock.instant();
    return transactions.execute(
        db ->
            db.sql(
                    """
                    UPDATE taska.tenant_operations o SET state='RUNNING', fence=fence+1, lease_until=:lease, updated_at=:now
                    FROM taska.tenants t WHERE o.tenant_id=:tenant AND o.id=:id AND t.id=o.tenant_id
                    AND o.state IN ('ACCEPTED','RUNNING') AND (o.lease_until IS NULL OR o.lease_until<=:now)
                    AND t.lifecycle IN ('PROVISIONING','AWAITING_PAYMENT')
                    RETURNING o.*,o.payload->>'planVersionId' plan,o.payload->>'templateId' template,o.payload->>'creationKind' creation_kind
                    """)
                .bind("tenant", tenant)
                .bind("id", id)
                .bind("lease", at(now.plus(LEASE)))
                .bind("now", at(now))
                .fetch()
                .one()
                .map(
                    row ->
                        new Claim(
                            tenant,
                            id,
                            ((Number) row.get("fence")).longValue(),
                            ((Number) row.get("completed_steps")).intValue(),
                            uuid(row.get("plan").toString()),
                            uuid(row.get("template").toString()),
                            "FREE".equals(row.get("creation_kind")))));
  }

  private Mono<Void> advance(Claim c) {
    Mono<Void> work = Mono.empty();
    if (c.step() < 1)
      work =
          work.then(Mono.defer(() -> dependencies.workflow(c.tenant(), c.id(), c.template())))
              .then(checkpoint(c, 1));
    if (c.step() < 2)
      work =
          work.then(Mono.defer(() -> dependencies.billing(c.tenant(), c.id(), c.plan(), c.free())))
              .then(checkpoint(c, 2));
    return work.then(finish(c));
  }

  private Mono<Void> checkpoint(Claim c, int step) {
    Instant now = clock.instant();
    return transactions
        .execute(
            db ->
                db.sql(
                        """
                        UPDATE taska.tenant_operations SET completed_steps=:step,updated_at=:now,lease_until=:lease,failure_code=NULL
                        WHERE tenant_id=:tenant AND id=:id AND fence=:fence AND state='RUNNING' AND lease_until>:now
                        """)
                    .bind("step", step)
                    .bind("now", at(now))
                    .bind("lease", at(now.plus(LEASE)))
                    .bind("tenant", c.tenant())
                    .bind("id", c.id())
                    .bind("fence", c.fence())
                    .fetch()
                    .rowsUpdated()
                    .flatMap(
                        n ->
                            n == 1
                                ? Mono.just(n)
                                : Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT"))))
        .then();
  }

  private Mono<Void> finish(Claim c) {
    Instant now = clock.instant();
    return transactions
        .execute(
            db ->
                db.sql(
                        "SELECT id FROM taska.tenant_operations WHERE tenant_id=:tenant AND id=:id"
                            + " AND fence=:fence AND state='RUNNING' AND completed_steps=2 AND"
                            + " lease_until>:now FOR UPDATE")
                    .bind("tenant", c.tenant())
                    .bind("id", c.id())
                    .bind("fence", c.fence())
                    .bind("now", at(now))
                    .fetch()
                    .one()
                    .switchIfEmpty(Mono.error(fail(DomainStatus.ABORTED, "VERSION_CONFLICT")))
                    .then(
                        db.sql(
                                """
                                UPDATE taska.tenants t SET lifecycle=:state,version=version+1,updated_at=:now
                                WHERE id=:tenant AND lifecycle=:previous AND EXISTS(SELECT 1 FROM taska.tenant_memberships m
                                WHERE m.tenant_id=t.id AND m.user_id=t.owner_id AND m.status='ACTIVE' AND m.role='TENANT_ADMIN')
                                RETURNING version
                                """)
                            .bind("state", c.free() ? "ACTIVE" : "AWAITING_PAYMENT")
                            .bind("previous", c.free() ? "PROVISIONING" : "AWAITING_PAYMENT")
                            .bind("now", at(now))
                            .bind("tenant", c.tenant())
                            .fetch()
                            .one()
                            .switchIfEmpty(
                                Mono.error(
                                    fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE"))))
                    .flatMap(
                        row ->
                            db.sql(
                                    """
                                    INSERT INTO taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)
                                    VALUES(:event,:tenant,'TENANT',:tenant,:version,'TenantProvisioned',1,
                                      jsonb_build_object('tenantId',:tenant::text,'operationId',:op::text),:op,'NEW',:now)
                                    """)
                                .bind("event", UUID.randomUUID())
                                .bind("tenant", c.tenant())
                                .bind("version", row.get("version"))
                                .bind("op", c.id())
                                .bind("now", at(now))
                                .then())
                    .then(
                        db.sql(
                                "UPDATE taska.tenant_operations SET"
                                    + " state='SUCCEEDED',lease_until=NULL,failure_code=NULL,updated_at=:now"
                                    + " WHERE tenant_id=:tenant AND id=:id AND fence=:fence")
                            .bind("now", at(now))
                            .bind("tenant", c.tenant())
                            .bind("id", c.id())
                            .bind("fence", c.fence())
                            .then())
                    .then(
                        db.sql(
                                "UPDATE taska.tenant_provisioning_dispatch SET completed_at=:now"
                                    + " WHERE tenant_id=:tenant AND operation_id=:id")
                            .bind("now", at(now))
                            .bind("tenant", c.tenant())
                            .bind("id", c.id())
                            .then())
                    .thenReturn(true))
        .then();
  }

  private Mono<Void> recordFailure(Claim c, Throwable error) {
    // Unknown errors and transport failures are retriable; never persist messages or remote bodies.
    String code = "DEPENDENCY_UNAVAILABLE";
    boolean terminal = false;
    if (error instanceof DomainException e
        && (e.getStatus() == DomainStatus.INVALID_ARGUMENT
            || e.getStatus() == DomainStatus.FAILED_PRECONDITION
            || e.getStatus() == DomainStatus.ALREADY_EXISTS)) {
      code = "INVALID_STATE";
      terminal = true;
    }
    var status = Status.fromThrowable(error).getCode();
    if (status == Status.Code.INVALID_ARGUMENT
        || status == Status.Code.FAILED_PRECONDITION
        || status == Status.Code.ALREADY_EXISTS) {
      code = "INVALID_STATE";
      terminal = true;
    }
    String failure = code;
    boolean stop = terminal;
    Instant now = clock.instant();
    return transactions
        .execute(
            db ->
                db.sql(
                        """
                        UPDATE taska.tenant_operations SET state=:state,failure_code=:code,lease_until=NULL,updated_at=:now
                        WHERE tenant_id=:tenant AND id=:id AND fence=:fence AND state='RUNNING'
                        """)
                    .bind("state", stop ? "FAILED" : "RUNNING")
                    .bind("code", failure)
                    .bind("now", at(now))
                    .bind("tenant", c.tenant())
                    .bind("id", c.id())
                    .bind("fence", c.fence())
                    .fetch()
                    .rowsUpdated()
                    .flatMap(
                        n ->
                            n == 0
                                ? Mono.just(n)
                                : db.sql(
                                        stop
                                            ? "UPDATE taska.tenant_provisioning_dispatch SET"
                                                + " completed_at=:next WHERE tenant_id=:tenant"
                                                + " AND operation_id=:id"
                                            : "UPDATE taska.tenant_provisioning_dispatch SET"
                                                + " next_attempt_at=:next WHERE tenant_id=:tenant"
                                                + " AND operation_id=:id")
                                    .bind("next", at(stop ? now : now.plusSeconds(5)))
                                    .bind("tenant", c.tenant())
                                    .bind("id", c.id())
                                    .fetch()
                                    .rowsUpdated()))
        .then();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
