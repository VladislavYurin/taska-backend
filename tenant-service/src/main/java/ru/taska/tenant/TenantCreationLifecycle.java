package ru.taska.tenant;

import static ru.taska.tenant.TenantRegistryService.fail;
import static ru.taska.tenant.TenantRegistryService.uuid;

import com.google.protobuf.MessageLite;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ApplyTenantInitialPaymentDecisionRequest;
import ru.taska.api.b2b.v1.CancelTenantCreationRpcRequest;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.Tenant;
import ru.taska.api.b2b.v1.TenantInitialPaymentResult;
import ru.taska.api.b2b.v1.TenantInitialPaymentResult.Outcome;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.TenantCancellationTransaction;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.tenant.TenantCreationDecisions.InitialPayment;
import ru.taska.tenant.TenantCreationDecisions.Precision;

/** All creation lifecycle writers take tenant, then provisioning-operation locks in this order. */
public final class TenantCreationLifecycle {
  private final TenantTransactionExecutor transactions;
  private final TenantCreationDecisions.Authority decisions;
  private final Clock clock;

  public TenantCreationLifecycle(
      TenantTransactionExecutor transactions,
      TenantCreationDecisions.Authority decisions,
      Clock clock) {
    this.transactions = transactions;
    this.decisions = decisions;
    this.clock = clock;
  }

  static Mono<Map<String, Object>> lock(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT * FROM taska.tenants WHERE id=:tenant FOR UPDATE")
        .bind("tenant", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")));
  }

  public Mono<Tenant> cancel(CancelTenantCreationRpcRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = uuid(request.getTenantId()),
              key = uuid(request.getMutation().getIdempotencyKey());
          String version = request.getMutation().getExpectedVersion();
          if (!request.getMutation().hasExpectedVersion())
            return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "VERSION_CONFLICT"));
          long expected = version(version);
          byte[] hash =
              TenantRegistryService.digest(
                  "cancelTenantCreation",
                  tenant.toString(),
                  version,
                  Boolean.toString(request.getPayload().getConfirmed()));
          return VerifiedTenantContext.current()
              .flatMap(
                  v -> {
                    var claims = RpcContextBridge.details(v).getContext();
                    boolean global =
                        claims.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                            && v.tenantId() == null;
                    boolean scoped =
                        claims.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                            && tenant.toString().equals(v.tenantId());
                    if (!"cancelTenantCreation".equals(v.action())
                        || v.actorId() == null
                        || !v.scopes().contains("write")
                        || !claims.hasSessionId()
                        || claims.getSessionId().isBlank()
                        || (!global && !scoped))
                      return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SESSION_REQUIRED"));
                    UUID actor = uuid(v.actorId());
                    Mono<Tenant> work =
                        transactions.execute(
                            db ->
                                lock(db, tenant)
                                    .flatMap(
                                        row ->
                                            owner(db, tenant, actor, row)
                                                .then(
                                                    receipt(
                                                        db,
                                                        tenant,
                                                        actor.toString(),
                                                        "cancelTenantCreation",
                                                        key,
                                                        hash))
                                                .flatMap(
                                                    previous -> {
                                                      if (previous.isPresent())
                                                        return Mono.just(
                                                            parseTenant(previous.get()));
                                                      if (!request.getPayload().getConfirmed())
                                                        return Mono.error(
                                                            fail(
                                                                DomainStatus.FAILED_PRECONDITION,
                                                                "CONFIRMATION_REQUIRED"));
                                                      if (((Number) row.get("version")).longValue()
                                                          != expected)
                                                        return Mono.error(
                                                            fail(
                                                                DomainStatus.ABORTED,
                                                                "VERSION_CONFLICT"));
                                                      if (!"PAID".equals(row.get("creation_kind"))
                                                          || row.get("first_payment_fact_id")
                                                              != null
                                                          || !Set.of(
                                                                  "AWAITING_PAYMENT",
                                                                  "AUTO_CLOSED",
                                                                  "OWNER_CANCELLED")
                                                              .contains(row.get("lifecycle")))
                                                        return Mono.error(
                                                            fail(
                                                                DomainStatus.FAILED_PRECONDITION,
                                                                "INVALID_STATE"));
                                                      Mono<Tenant> transition =
                                                          "OWNER_CANCELLED"
                                                                  .equals(row.get("lifecycle"))
                                                              ? Mono.just(
                                                                  TenantRegistryService.tenant(row))
                                                              : close(
                                                                  db, row, "OWNER_CANCELLED", key);
                                                      return transition.flatMap(
                                                          result ->
                                                              save(
                                                                      db,
                                                                      tenant,
                                                                      actor.toString(),
                                                                      "cancelTenantCreation",
                                                                      key,
                                                                      hash,
                                                                      result)
                                                                  .thenReturn(result));
                                                    })));
                    return global
                        ? new TenantCancellationTransaction().execute(tenant, work)
                        : work;
                  });
        });
  }

  private Mono<Void> owner(DatabaseClient db, UUID tenant, UUID actor, Map<String, Object> row) {
    if ("DELETED".equals(row.get("lifecycle")))
      return Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND"));
    return db.sql(
            "SELECT role FROM taska.tenant_memberships WHERE tenant_id=:tenant AND user_id=:actor"
                + " AND status='ACTIVE'")
        .bind("tenant", tenant)
        .bind("actor", actor)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .flatMap(
            member ->
                actor.equals(row.get("owner_id")) && "TENANT_ADMIN".equals(member.get("role"))
                    ? Mono.<Void>empty()
                    : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED")));
  }

  public Mono<Tenant> expire(UUID tenant) {
    return service(tenant, "expireTenantCreation", "tenant-service")
        .then(
            transactions.execute(
                db ->
                    lock(db, tenant)
                        .flatMap(
                            row -> {
                              if (!"PAID".equals(row.get("creation_kind")))
                                return Mono.just(TenantRegistryService.tenant(row));
                              if (row.get("first_payment_fact_id") != null
                                  || !"AWAITING_PAYMENT".equals(row.get("lifecycle")))
                                return completeExpiry(db, tenant)
                                    .thenReturn(TenantRegistryService.tenant(row));
                              Instant deadline =
                                  ((OffsetDateTime) row.get("creation_deadline_at")).toInstant();
                              if (clock.instant().isBefore(deadline))
                                return Mono.just(TenantRegistryService.tenant(row));
                              UUID key = (UUID) row.get("creation_operation_id");
                              byte[] hash =
                                  TenantRegistryService.digest(
                                      "expireTenantCreation",
                                      tenant.toString(),
                                      key.toString(),
                                      deadline.toString());
                              return close(db, row, "AUTO_EXPIRED", key)
                                  .flatMap(
                                      result ->
                                          save(
                                                  db,
                                                  tenant,
                                                  "tenant-service",
                                                  "expireTenantCreation",
                                                  key,
                                                  hash,
                                                  result)
                                              .thenReturn(result));
                            })));
  }

  private Mono<Tenant> close(
      DatabaseClient db, Map<String, Object> row, String reason, UUID operation) {
    UUID tenant = (UUID) row.get("id");
    Instant now = clock.instant();
    boolean hard = reason.equals("OWNER_CANCELLED");
    return db.sql(
            """
            UPDATE taska.tenants SET lifecycle=:state,creation_closure_reason=:reason,creation_closed_at=:now,
              version=version+1,restriction_version=restriction_version+1,updated_at=:now WHERE id=:tenant
            RETURNING *
            """)
        .bind("state", hard ? "OWNER_CANCELLED" : "AUTO_CLOSED")
        .bind("reason", reason)
        .bind("now", at(now))
        .bind("tenant", tenant)
        .fetch()
        .one()
        .flatMap(
            updated ->
                fence(db, tenant, hard, hard ? "INVALID_STATE" : "PAYMENT_PENDING")
                    .then(
                        hard
                            ? db.sql(
                                    "UPDATE taska.tenant_provisioning_dispatch SET"
                                        + " completed_at=:now WHERE tenant_id=:tenant")
                                .bind("now", at(now))
                                .bind("tenant", tenant)
                                .then()
                            : Mono.empty())
                    .then(completeExpiry(db, tenant))
                    .then(event(db, updated, operation, "TenantCreationClosed"))
                    .thenReturn(TenantRegistryService.tenant(updated)));
  }

  private Mono<Void> fence(DatabaseClient db, UUID tenant, boolean stop, String code) {
    var query =
        db.sql(
                """
                UPDATE taska.tenant_operations SET fence=fence+1,lease_until=NULL,state=CASE WHEN :stop THEN 'FAILED' ELSE state END,
                  failure_code=:code,updated_at=:now WHERE tenant_id=:tenant AND kind='TENANT_PROVISION' AND state IN ('ACCEPTED','RUNNING')
                """)
            .bind("stop", stop)
            .bind("now", at(clock.instant()))
            .bind("tenant", tenant);
    query = code == null ? query.bindNull("code", String.class) : query.bind("code", code);
    return query.then();
  }

  private Mono<Void> completeExpiry(DatabaseClient db, UUID tenant) {
    return db.sql(
            "UPDATE taska.tenant_provisioning_dispatch SET"
                + " creation_expiry_completed_at=COALESCE(creation_expiry_completed_at,:now) WHERE"
                + " tenant_id=:tenant")
        .bind("now", at(clock.instant()))
        .bind("tenant", tenant)
        .then();
  }

  public Mono<TenantInitialPaymentResult> apply(ApplyTenantInitialPaymentDecisionRequest request) {
    return Mono.defer(
        () -> {
          UUID tenant = uuid(request.getTenantId()), id = uuid(request.getDecisionId());
          return service(tenant, "applyTenantInitialPaymentDecision", "billing-service")
              .then(Mono.defer(() -> decisions.resolve(tenant, id)))
              .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
              .flatMap(
                  e -> {
                    validate(e, tenant, id);
                    byte[] hash =
                        TenantRegistryService.digest(
                            "applyTenantInitialPaymentDecision", e.toString());
                    return transactions.execute(
                        db ->
                            lock(db, tenant)
                                .flatMap(
                                    row ->
                                        receipt(
                                                db,
                                                tenant,
                                                "billing-service",
                                                "applyTenantInitialPaymentDecision",
                                                id,
                                                hash)
                                            .flatMap(
                                                previous -> {
                                                  if (previous.isPresent())
                                                    return Mono.just(parsePayment(previous.get()));
                                                  return apply(db, row, e)
                                                      .flatMap(
                                                          result ->
                                                              save(
                                                                      db,
                                                                      tenant,
                                                                      "billing-service",
                                                                      "applyTenantInitialPaymentDecision",
                                                                      id,
                                                                      hash,
                                                                      result)
                                                                  .thenReturn(result));
                                                })));
                  });
        });
  }

  private Mono<TenantInitialPaymentResult> apply(
      DatabaseClient db, Map<String, Object> row, InitialPayment e) {
    if (e.financialFactId().equals(row.get("first_payment_fact_id")))
      return Mono.just(result(row, Outcome.OUTCOME_ALREADY_APPLIED));
    if (!"PAID".equals(row.get("creation_kind"))
        || row.get("first_payment_fact_id") != null
        || !Set.of("AWAITING_PAYMENT", "AUTO_CLOSED").contains(row.get("lifecycle"))
        || !e.creationOperationId().equals(row.get("creation_operation_id"))
        || !e.planVersionId().equals(row.get("plan_version_id"))
        || !e.full()) return Mono.just(result(row, Outcome.OUTCOME_NOT_ELIGIBLE));
    if (e.coverageId() == null) return Mono.just(result(row, Outcome.OUTCOME_NEEDS_EVIDENCE));
    Instant deadline = ((OffsetDateTime) row.get("creation_deadline_at")).toInstant();
    Instant cutoff = deadline.isBefore(e.invoiceDeadline()) ? deadline : e.invoiceDeadline();
    if (e.precision() == Precision.EXACT && e.occurredAt().isAfter(cutoff)
        || e.precision() == Precision.DAY && e.occurredNotBefore().isAfter(cutoff))
      return Mono.just(result(row, Outcome.OUTCOME_NOT_ELIGIBLE));
    if (e.precision() == Precision.DAY && e.occurredBefore().isAfter(cutoff))
      return Mono.just(result(row, Outcome.OUTCOME_NEEDS_EVIDENCE));
    UUID tenant = (UUID) row.get("id");
    Instant now = clock.instant();
    return db.sql(
            "SELECT state,completed_steps FROM taska.tenant_operations WHERE tenant_id=:tenant AND"
                + " id=:operation FOR UPDATE")
        .bind("tenant", tenant)
        .bind("operation", e.creationOperationId())
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE")))
        .flatMap(
            op -> {
              boolean ready =
                  "SUCCEEDED".equals(op.get("state"))
                      && ((Number) op.get("completed_steps")).intValue() == 2;
              return db.sql(
                      """
                      UPDATE taska.tenants SET first_payment_fact_id=:fact,first_payment_decision_id=:decision,
                        first_payment_invoice_id=:invoice,first_payment_coverage_id=:coverage,lifecycle=:state,
                        creation_closure_reason=NULL,creation_closed_at=NULL,version=version+1,restriction_version=restriction_version+1,updated_at=:now
                      WHERE id=:tenant RETURNING *
                      """)
                  .bind("fact", e.financialFactId())
                  .bind("decision", e.decisionId())
                  .bind("invoice", e.invoiceId())
                  .bind("coverage", e.coverageId())
                  .bind("state", ready ? "ACTIVE" : "AWAITING_PAYMENT")
                  .bind("now", at(now))
                  .bind("tenant", tenant)
                  .fetch()
                  .one()
                  .flatMap(
                      updated ->
                          completeExpiry(db, tenant)
                              .then(
                                  ready
                                      ? Mono.empty()
                                      : fence(db, tenant, false, null)
                                          .then(
                                              db.sql(
                                                      "UPDATE taska.tenant_provisioning_dispatch"
                                                          + " SET completed_at=NULL,next_attempt_at=:now"
                                                          + " WHERE tenant_id=:tenant")
                                                  .bind("now", at(now))
                                                  .bind("tenant", tenant)
                                                  .then()))
                              .then(
                                  event(
                                      db, updated, e.decisionId(), "TenantInitialPaymentAccepted"))
                              .thenReturn(
                                  result(
                                      updated,
                                      ready
                                          ? Outcome.OUTCOME_APPLIED
                                          : Outcome.OUTCOME_WAITING_PROVISIONING)));
            });
  }

  private void validate(InitialPayment e, UUID tenant, UUID id) {
    boolean exact = e.precision() == Precision.EXACT;
    if (!tenant.equals(e.tenantId())
        || !id.equals(e.decisionId())
        || e.creationOperationId() == null
        || e.financialFactId() == null
        || e.invoiceId() == null
        || e.planVersionId() == null
        || e.invoiceDeadline() == null
        || e.precision() == null
        || (exact
            ? (e.occurredAt() == null
                || e.occurredNotBefore() != null
                || e.occurredBefore() != null)
            : (e.occurredAt() != null
                || e.occurredNotBefore() == null
                || e.occurredBefore() == null
                || !e.occurredBefore().isAfter(e.occurredNotBefore()))))
      throw fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    for (Instant time :
        new Instant[] {
          e.invoiceDeadline(), e.occurredAt(), e.occurredNotBefore(), e.occurredBefore()
        })
      if (time != null && time.getNano() % 1000 != 0)
        throw fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
  }

  private Mono<Void> service(UUID tenant, String action, String issuer) {
    return VerifiedTenantContext.current()
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              return tenant.toString().equals(v.tenantId())
                      && action.equals(v.action())
                      && c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE
                      && issuer.equals(c.getIssuerService())
                      && v.scopes().contains("write")
                  ? Mono.empty()
                  : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
            });
  }

  private Mono<Optional<String>> receipt(
      DatabaseClient db, UUID tenant, String actor, String action, UUID key, byte[] hash) {
    return db.sql(
            "SELECT request_hash,result FROM taska.tenant_creation_receipts WHERE tenant_id=:tenant"
                + " AND actor_scope=:actor AND action=:action AND key=:key")
        .bind("tenant", tenant)
        .bind("actor", actor)
        .bind("action", action)
        .bind("key", key)
        .fetch()
        .one()
        .map(
            row -> {
              if (!MessageDigest.isEqual(
                  hash, TenantRegistryService.binary(row.get("request_hash"))))
                throw fail(DomainStatus.ALREADY_EXISTS, "IDEMPOTENCY_CONFLICT");
              return Optional.of(row.get("result").toString());
            })
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Void> save(
      DatabaseClient db,
      UUID tenant,
      String actor,
      String action,
      UUID key,
      byte[] hash,
      MessageLite result) {
    return db.sql(
            "INSERT INTO"
                + " taska.tenant_creation_receipts(tenant_id,actor_scope,action,key,request_hash,result,created_at)"
                + " VALUES(:tenant,:actor,:action,:key,:hash,:result,:now)")
        .bind("tenant", tenant)
        .bind("actor", actor)
        .bind("action", action)
        .bind("key", key)
        .bind("hash", hash)
        .bind("result", Base64.getEncoder().encodeToString(result.toByteArray()))
        .bind("now", at(clock.instant()))
        .then();
  }

  private Mono<Void> event(
      DatabaseClient db, Map<String, Object> row, UUID operation, String type) {
    return db.sql(
            """
            INSERT INTO taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload,operation_id,status,created_at)
            VALUES(:id,:tenant,'TENANT',:tenant,:version,:type,1,jsonb_build_object('tenantId',:tenant::text,'operationId',:operation::text),:operation,'NEW',:now)
            """)
        .bind("id", UUID.randomUUID())
        .bind("tenant", row.get("id"))
        .bind("version", row.get("version"))
        .bind("type", type)
        .bind("operation", operation)
        .bind("now", at(clock.instant()))
        .then();
  }

  public static void requireWorking(Tenant tenant) {
    if (tenant.getLifecycle() == Tenant.LifecycleValue.LIFECYCLE_AWAITING_PAYMENT)
      throw new ru.taska.exception.DomainException(
          DomainStatus.FAILED_PRECONDITION,
          "PAYMENT_PENDING",
          Map.of("code", "PAYMENT_PENDING", "reason", "PENDING_FIRST_PAYMENT"));
    if (tenant.getLifecycle() != Tenant.LifecycleValue.LIFECYCLE_ACTIVE)
      throw fail(DomainStatus.FAILED_PRECONDITION, "INVALID_STATE");
  }

  private static long version(String value) {
    try {
      long n = Long.parseLong(value);
      if (n < 1 || !Long.toString(n).equals(value)) throw new IllegalArgumentException();
      return n;
    } catch (IllegalArgumentException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  private static Tenant parseTenant(String value) {
    try {
      return Tenant.parseFrom(Base64.getDecoder().decode(value));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored creation receipt");
    }
  }

  private static TenantInitialPaymentResult parsePayment(String value) {
    try {
      return TenantInitialPaymentResult.parseFrom(Base64.getDecoder().decode(value));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored creation receipt");
    }
  }

  private static TenantInitialPaymentResult result(Map<String, Object> row, Outcome outcome) {
    return TenantInitialPaymentResult.newBuilder()
        .setTenant(TenantRegistryService.tenant(row))
        .setOutcome(outcome)
        .build();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
