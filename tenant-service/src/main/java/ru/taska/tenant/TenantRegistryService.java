package ru.taska.tenant;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CreateTenantResponse;
import ru.taska.api.b2b.v1.CreateTenantRpcRequest;
import ru.taska.api.b2b.v1.GetTenantRpcRequest;
import ru.taska.api.b2b.v1.Operation;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.TenantCreationTransaction;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.tenant.RegistryDependencies.FreeAdmission;
import ru.taska.tenant.RegistryDependencies.PublishedPlan;
import ru.taska.tenant.RegistryDependencies.PublishedPlans;

public final class TenantRegistryService {
  private final TenantTransactionExecutor transactions;
  private final TenantCreationTransaction creations;
  private final PublishedPlans plans;
  private final FreeAdmission free;
  private final Clock clock;
  private final Function<DatabaseClient, Mono<Duration>> creationWindows;

  public TenantRegistryService(
      TenantTransactionExecutor transactions,
      PublishedPlans plans,
      FreeAdmission free,
      Clock clock,
      Duration window) {
    this(transactions, plans, free, clock, db -> Mono.just(window));
    if (window.isNegative() || window.isZero())
      throw new IllegalArgumentException("creation window must be positive");
  }

  public TenantRegistryService(
      TenantTransactionExecutor transactions,
      PublishedPlans plans,
      FreeAdmission free,
      Clock clock,
      Function<DatabaseClient, Mono<Duration>> creationWindows) {
    this.transactions = transactions;
    this.creations = new TenantCreationTransaction(transactions);
    this.plans = plans;
    this.free = free;
    this.clock = clock;
    this.creationWindows = creationWindows;
  }

  static DomainException fail(DomainStatus status, String code) {
    return new DomainException(status, code, Map.of("code", code));
  }

  static UUID uuid(String s) {
    try {
      var id = UUID.fromString(s);
      if (!id.toString().equals(s)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  static byte[] digest(String... parts) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (String p : parts) {
        byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
      }
      return hash.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static UUID creationId(UUID actor, String key) {
    var b = ByteBuffer.wrap(digest("taska:createTenant:v1", actor.toString(), key));
    long hi = b.getLong(), lo = b.getLong();
    return new UUID(
        (hi & 0xffffffffffff0fffL) | 0x8000L, (lo & 0x3fffffffffffffffL) | 0x8000000000000000L);
  }

  public Mono<CreateTenantResponse> create(CreateTenantRpcRequest request) {
    return Mono.defer(
        () -> {
          String name = request.getPayload().getName(),
              key = request.getMutation().getIdempotencyKey();
          UUID plan = uuid(request.getPayload().getPlanVersionId());
          uuid(key);
          if (name.isBlank()
              || !name.equals(name.strip())
              || name.codePointCount(0, name.length()) > 120
              || name.codePoints().anyMatch(Character::isISOControl))
            throw new DomainException(
                DomainStatus.INVALID_ARGUMENT,
                "VALIDATION_FAILED",
                Map.of("code", "VALIDATION_FAILED", "field", "name", "reason", "INVALID_NAME"));
          if (key.isBlank()
              || key.length() > 128
              || key.codePoints().anyMatch(c -> c < 33 || c > 126)
              || request.getMutation().hasExpectedVersion())
            throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
          byte[] hash;
          try {
            var canonical =
                new java.util.TreeMap<String, String>(
                    Map.of(
                        "operationId",
                        "createTenant",
                        "name",
                        name,
                        "planVersionId",
                        plan.toString()));
            hash =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        tools.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .writeValueAsBytes(canonical));
          } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
          }
          return VerifiedTenantContext.current()
              .flatMap(
                  identity -> {
                    UUID actor = uuid(Objects.requireNonNullElse(identity.actorId(), ""));
                    UUID tenant = creationId(actor, key);
                    // Replay is checked under the same exact tenant/actor scope, before consulting
                    // a retired catalog.
                    return creations
                        .execute(
                            tenant,
                            db ->
                                existing(db, tenant)
                                    .flatMap(row -> replay(db, row, actor, hash))
                                    .map(Optional::of)
                                    .defaultIfEmpty(Optional.empty()))
                        .flatMap(
                            cached ->
                                cached.isPresent()
                                    ? Mono.just(cached.get())
                                    : plans
                                        .resolve(plan)
                                        .switchIfEmpty(
                                            Mono.error(
                                                fail(
                                                    DomainStatus.INVALID_ARGUMENT,
                                                    "VALIDATION_FAILED")))
                                        .flatMap(
                                            p -> {
                                              if (!p.id().equals(plan))
                                                return Mono.error(
                                                    fail(
                                                        DomainStatus.UNAVAILABLE,
                                                        "DEPENDENCY_UNAVAILABLE"));
                                              return (p.free()
                                                      ? free.prepare(actor)
                                                      : Mono.just(free))
                                                  .flatMap(
                                                      admission ->
                                                          creations.execute(
                                                              tenant,
                                                              db ->
                                                                  insert(
                                                                      db, tenant, actor, name, key,
                                                                      hash, p, admission)));
                                            }));
                  });
        });
  }

  private Mono<CreateTenantResponse> insert(
      DatabaseClient db,
      UUID tenant,
      UUID actor,
      String name,
      String key,
      byte[] hash,
      PublishedPlan plan,
      FreeAdmission admission) {
    return (plan.free() ? Mono.just(Duration.ZERO) : creationWindows.apply(db))
        .flatMap(
            window ->
                insertWithWindow(db, tenant, actor, name, key, hash, plan, window, admission));
  }

  private Mono<CreateTenantResponse> insertWithWindow(
      DatabaseClient db,
      UUID tenant,
      UUID actor,
      String name,
      String key,
      byte[] hash,
      PublishedPlan plan,
      Duration window,
      FreeAdmission preparedAdmission) {
    Instant now = clock.instant();
    UUID operation = UUID.randomUUID();
    var sql =
        db.sql(
                "INSERT INTO"
                    + " taska.tenants(id,name,owner_id,lifecycle,creation_kind,creation_deadline_at,created_at,updated_at,plan_version_id,creation_actor_id,creation_request_hash,creation_operation_id)"
                    + " VALUES"
                    + " (:id,:name,:owner,:lifecycle,:kind,:deadline,:created,:created,:plan,:owner,:hash,:operation)"
                    + " ON CONFLICT (id) DO NOTHING RETURNING id")
            .bind("id", tenant)
            .bind("name", name)
            .bind("owner", actor)
            .bind("lifecycle", plan.free() ? "PROVISIONING" : "AWAITING_PAYMENT")
            .bind("kind", plan.free() ? "FREE" : "PAID")
            .bind("created", now.atOffset(ZoneOffset.UTC))
            .bind("plan", plan.id())
            .bind("hash", hash)
            .bind("operation", operation);
    sql =
        plan.free()
            ? sql.bindNull("deadline", OffsetDateTime.class)
            : sql.bind("deadline", now.plus(window).atOffset(ZoneOffset.UTC));
    return sql.map((r, m) -> r.get("id", UUID.class))
        .one()
        .flatMap(
            id -> {
              // FREE claim and registry insert must roll back together; unavailable admission never
              // creates a tenant.
              Mono<Void> admission =
                  plan.free() ? preparedAdmission.reserve(db, actor, tenant) : Mono.empty();
              return admission
                  .then(
                      db.sql(
                              "INSERT INTO taska.tenant_memberships(tenant_id,user_id,role,status)"
                                  + " VALUES (:tenant,:actor,'TENANT_ADMIN','ACTIVE')")
                          .bind("tenant", tenant)
                          .bind("actor", actor)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      db.sql(
                              "INSERT INTO"
                                  + " taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)"
                                  + " VALUES"
                                  + " (:id,:tenant,'TENANT',:tenant,1,'TenantCreationRequested',1,jsonb_build_object('tenantId',:tenant::text,'operationId',:operation::text,'planVersionId',:plan::text),:operation,'NEW',:created)")
                          .bind("id", UUID.randomUUID())
                          .bind("tenant", tenant)
                          .bind("operation", operation)
                          .bind("plan", plan.id())
                          .bind("created", now.atOffset(ZoneOffset.UTC))
                          .fetch()
                          .rowsUpdated())
                  .then(
                      TenantProvisioning.insert(
                          db, tenant, actor, operation, key, hash, plan.id(), plan.free(), now))
                  .then(existing(db, tenant))
                  .flatMap(
                      row ->
                          response(row)
                              .flatMap(
                                  result ->
                                      db.sql(
                                              "INSERT INTO"
                                                  + " taska.idempotency_records(tenant_id,actor_id,operation_code,key,request_hash,operation_id,state,response,created_at,expires_at)"
                                                  + " VALUES"
                                                  + " (:tenant,:actor,'createTenant',:key,:hash,:operation,'SUCCEEDED',jsonb_build_object('tenantId',:tenant::text,'operationId',:operation::text),:created,:expires)")
                                          .bind("tenant", tenant)
                                          .bind("actor", actor)
                                          .bind("key", key)
                                          .bind("hash", hash)
                                          .bind("operation", operation)
                                          .bind("created", now.atOffset(ZoneOffset.UTC))
                                          .bind(
                                              "expires",
                                              now.plus(Duration.ofHours(24))
                                                  .atOffset(ZoneOffset.UTC))
                                          .fetch()
                                          .rowsUpdated()
                                          .thenReturn(result)));
            })
        .switchIfEmpty(
            existing(db, tenant)
                .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
                .flatMap(row -> replay(db, row, actor, hash)));
  }

  private Mono<Map<String, Object>> existing(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT * FROM taska.tenants WHERE id=:id FOR UPDATE")
        .bind("id", tenant)
        .fetch()
        .one();
  }

  static byte[] binary(Object value) {
    if (value instanceof byte[] bytes) return bytes;
    var buffer = ((ByteBuffer) value).duplicate();
    var bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private Mono<CreateTenantResponse> replay(
      DatabaseClient db, Map<String, Object> row, UUID actor, byte[] hash) {
    if ("DELETED".equals(row.get("lifecycle")))
      return Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND"));
    return activeMember(db, (UUID) row.get("id"), actor)
        .then(
            Mono.defer(
                () -> {
                  if (!actor.equals(row.get("creation_actor_id"))
                      || !MessageDigest.isEqual(hash, binary(row.get("creation_request_hash"))))
                    return Mono.error(fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT"));
                  return response(row);
                }));
  }

  private Mono<Void> activeMember(DatabaseClient db, UUID tenant, UUID actor) {
    return db.sql(
            "SELECT user_id FROM taska.tenant_memberships WHERE tenant_id=:tenant AND"
                + " user_id=:actor AND status='ACTIVE'")
        .bind("tenant", tenant)
        .bind("actor", actor)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .then();
  }

  public Mono<Tenant> get(GetTenantRpcRequest request) {
    return Mono.defer(
        () -> {
          UUID id = uuid(request.getTenantId());
          return VerifiedTenantContext.current()
              .flatMap(
                  identity -> {
                    if (!"getTenant".equals(identity.action())
                        || !id.toString().equals(identity.tenantId())
                        || identity.actorId() == null)
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
                    return transactions.execute(
                        db ->
                            activeMember(db, id, uuid(identity.actorId()))
                                .then(
                                    db.sql(
                                            "SELECT * FROM taska.tenants WHERE id=:id AND"
                                                + " lifecycle<>'DELETED'")
                                        .bind("id", id)
                                        .fetch()
                                        .one())
                                .switchIfEmpty(
                                    Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
                                .map(TenantRegistryService::tenant));
                  });
        });
  }

  public Mono<Tenant> cancelCreation(ru.taska.api.b2b.v1.CancelTenantCreationRpcRequest request) {
    return new TenantCreationLifecycle(
            transactions,
            (tenant, decision) ->
                Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")),
            clock)
        .cancel(request);
  }

  static Tenant tenant(Map<String, Object> row) {
    var result =
        Tenant.newBuilder()
            .setId(row.get("id").toString())
            .setName((String) row.get("name"))
            .setOwnerId(row.get("owner_id").toString())
            .setLifecycle(Tenant.LifecycleValue.valueOf("LIFECYCLE_" + row.get("lifecycle")))
            .setCreatedAt(((OffsetDateTime) row.get("created_at")).toInstant().toString())
            .setVersion(row.get("version").toString())
            .setOwnerVersion(row.get("owner_version").toString())
            .setPlanVersionId(row.get("plan_version_id").toString())
            .setCreationOperationId(row.get("creation_operation_id").toString())
            .setCreationKind(
                Tenant.CreationKindValue.valueOf("CREATION_KIND_" + row.get("creation_kind")));
    if (row.get("creation_closure_reason") instanceof String reason)
      result.setCreationClosureReason(
          Tenant.CreationClosureReason.valueOf("CREATION_CLOSURE_REASON_" + reason));
    if (row.get("creation_closed_at") instanceof OffsetDateTime closed)
      result.setCreationClosedAt(closed.toInstant().toString());
    if (row.get("creation_deadline_at") instanceof OffsetDateTime d)
      result.setCreationDeadline(d.toInstant().toString());
    if (row.get("creation_kind").equals("PAID") && row.get("first_payment_fact_id") == null)
      result.addRestrictions(RestrictionCode.RESTRICTION_CODE_PENDING_FIRST_PAYMENT);
    return result.build();
  }

  public Mono<Operation> getOperation(ru.taska.api.b2b.v1.GetTenantOperationRpcRequest request) {
    return new TenantProvisioning(transactions, null, clock).get(request);
  }

  private Mono<CreateTenantResponse> response(Map<String, Object> row) {
    var t = tenant(row);
    return transactions
        .execute(
            db ->
                TenantProvisioning.operation(
                    db, (UUID) row.get("id"), (UUID) row.get("creation_operation_id")))
        .map(op -> CreateTenantResponse.newBuilder().setTenant(t).setOperation(op).build());
  }
}
