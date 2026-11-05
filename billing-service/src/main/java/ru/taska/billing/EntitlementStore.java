package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;
import static ru.taska.billing.CatalogErrors.invalid;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.Entitlement;
import ru.taska.api.b2b.v1.GetEntitlementRpcRequest;
import ru.taska.api.b2b.v1.GetEntitlementSnapshotRequest;
import ru.taska.api.b2b.v1.QuotaLimit;
import ru.taska.api.b2b.v1.RestrictionCode;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable financial rights, never actor capabilities. All writers serialize on the subscription.
 */
public final class EntitlementStore {
  public interface ReadAuthority {
    /**
     * Current tenant membership/scoped admin, or explicitly authorized internal service purpose.
     */
    Mono<Boolean> allowed(VerifiedTenantContext context);
  }

  private final TenantTransactionExecutor transactions;
  private final ReadAuthority readers;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();
  private static final Set<String> CONSUMERS =
      Set.of(
          "api-gateway",
          "tenant-service",
          "project-service",
          "issue-service",
          "workflow-service",
          "notification-service",
          "auth-service",
          "admin-service",
          "billing-service");

  public EntitlementStore(
      TenantTransactionExecutor transactions, ReadAuthority readers, Clock clock) {
    this.transactions = transactions;
    this.readers = readers;
    this.clock = clock;
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  /** Safe for active members; financial documents retain their separate owner/admin authority. */
  public Mono<Entitlement> get(GetEntitlementRpcRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return new FinancialAccess(readers::allowed, "SCOPE_DENIED")
        .authorize(tenant, "getEntitlement", false)
        .flatMap(v -> current(tenant, 0));
  }

  public Mono<Entitlement> getSnapshot(GetEntitlementSnapshotRequest request) {
    UUID tenant = uuid(request.getTenantId());
    long minimum = 0;
    if (request.hasMinimumVersion()) {
      if (!request.getMinimumVersion().matches("[1-9][0-9]{0,18}")) throw invalid();
      try {
        minimum = Long.parseLong(request.getMinimumVersion());
      } catch (NumberFormatException e) {
        throw invalid();
      }
    }
    final long required = minimum;
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!tenant.toString().equals(v.tenantId())
                  || !"getEntitlementSnapshot".equals(v.action()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              if (c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !CONSUMERS.contains(c.getIssuerService())
                  || !v.scopes().contains("read"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return readers
                  .allowed(v)
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
                  .flatMap(
                      ok ->
                          ok
                              ? current(tenant, required)
                              : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED")));
            });
  }

  private Mono<Entitlement> current(UUID tenant, long minimum) {
    return transactions.execute(
        db ->
            db.sql("SELECT tenant_id FROM taska.subscriptions WHERE tenant_id=:tenant FOR UPDATE")
                .bind("tenant", tenant)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                .flatMap(ignored -> refresh(db, tenant, false))
                .flatMap(
                    snapshot ->
                        Long.parseLong(snapshot.getVersion()) >= minimum
                            ? Mono.just(snapshot)
                            : Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"))));
  }

  /** Called only within an already authorized subscription mutation, on its same connection. */
  Mono<Void> changed(DatabaseClient db, UUID tenant) {
    return refresh(db, tenant, true).then();
  }

  private Mono<Entitlement> refresh(DatabaseClient db, UUID tenant, boolean force) {
    Instant now = clock.instant();
    return latest(db, tenant)
        .defaultIfEmpty(Map.of())
        .flatMap(
            previous ->
                projection(db, tenant, previous, now)
                    .flatMap(
                        projected -> {
                          if (!force && projected.signature().equals(previous.get("state_key")))
                            return Mono.just(dto(previous));
                          long version =
                              previous.isEmpty()
                                  ? 1
                                  : ((Number) previous.get("version")).longValue() + 1;
                          return insert(db, tenant, version, projected, now);
                        }));
  }

  private Mono<Map<String, Object>> latest(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT *,features::text AS features_text,restrictions::text AS restrictions_text FROM"
                + " taska.entitlement_snapshots WHERE tenant_id=:tenant ORDER BY version DESC LIMIT"
                + " 1")
        .bind("tenant", tenant)
        .fetch()
        .one();
  }

  private record Projection(
      UUID plan,
      UUID period,
      Instant from,
      Instant until,
      Instant paidThrough,
      long projects,
      long members,
      Long tasks,
      long storage,
      String features,
      String restrictions,
      String coverage,
      String signature) {}

  private Mono<Projection> projection(
      DatabaseClient db, UUID tenant, Map<String, Object> previous, Instant now) {
    return db.sql(
            """
            SELECT s.*,s.selected_terms_snapshot::text selected_terms,
              p.id effective_period_id,p.plan_version_id effective_plan_id,
              p.starts_at effective_start,p.ends_at effective_end,p.terms_snapshot::text period_terms,
              (SELECT min(f.starts_at) FROM taska.subscription_periods f WHERE f.tenant_id=s.tenant_id
                 AND f.status='GRANTED' AND f.starts_at>:now) next_start,
              EXISTS(SELECT 1 FROM taska.subscription_periods r WHERE r.tenant_id=s.tenant_id
                 AND r.status='REVOKED') had_revocation
            FROM taska.subscriptions s LEFT JOIN LATERAL (
              SELECT * FROM taska.subscription_periods p WHERE p.tenant_id=s.tenant_id
                AND p.status='GRANTED' AND p.starts_at<=:now
              ORDER BY (p.ends_at>:now) DESC,p.starts_at DESC,p.id DESC LIMIT 1
            ) p ON true WHERE s.tenant_id=:tenant
            """)
        .bind("tenant", tenant)
        .bind("now", now.atOffset(ZoneOffset.UTC))
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
        .map(
            row -> {
              Instant end = instant(row.get("effective_end"));
              boolean paid = end != null && now.isBefore(end);
              JsonNode selected = json.readTree(row.get("selected_terms").toString());
              boolean free = !paid && "FREE".equals(selected.path("kind").asText());
              boolean periodTerms = !free && end != null;
              JsonNode terms =
                  periodTerms ? json.readTree(row.get("period_terms").toString()) : selected;
              UUID plan =
                  (UUID) row.get(periodTerms ? "effective_plan_id" : "selected_plan_version_id");
              // Preserve independently owned dispute restrictions across coverage changes.
              // Lifecycle/ACL/quotas are evaluated by their owners, never cleared here.
              var restrictions = new TreeSet<String>();
              if (previous.containsKey("restrictions_text"))
                for (JsonNode value : json.readTree(previous.get("restrictions_text").toString()))
                  if (value
                      .asText()
                      .equals(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE.name()))
                    restrictions.add(value.asText());
              boolean pending = !free && !paid && "AWAITING_PAYMENT".equals(row.get("mode"));
              if (!free && !paid)
                restrictions.add(
                    (pending
                            ? RestrictionCode.RESTRICTION_CODE_PENDING_FIRST_PAYMENT
                            : RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED)
                        .name());
              String coverage =
                  free
                      ? "FREE"
                      : paid
                          ? "PAID"
                          : Boolean.TRUE.equals(row.get("had_revocation")) && end == null
                              ? "REVOKED"
                              : "UNPAID";
              if (restrictions.contains(RestrictionCode.RESTRICTION_CODE_PAYMENT_DISPUTE.name()))
                coverage = "DISPUTED";
              Instant next = instant(row.get("next_start"));
              Instant until = paid ? end : null;
              if (next != null && (until == null || next.isBefore(until))) until = next;
              Instant from = paid ? instant(row.get("effective_start")) : now;
              UUID period = free ? null : (UUID) row.get("effective_period_id");
              Instant paidThrough = free ? null : end;
              long projects = terms.path("max_projects").asLong();
              long members = terms.path("max_members").asLong();
              long storage = terms.path("max_storage_bytes").asLong();
              Long tasks =
                  terms.path("max_tasks_per_project").isNull()
                      ? null
                      : terms.path("max_tasks_per_project").asLong();
              String features = json.writeValueAsString(terms.path("features"));
              String restrictionJson = json.writeValueAsString(restrictions);
              String signature =
                  json.writeValueAsString(
                      List.of(
                          plan.toString(),
                          period == null ? "" : period.toString(),
                          until == null ? "" : until.toString(),
                          paidThrough == null ? "" : paidThrough.toString(),
                          projects,
                          members,
                          tasks == null ? "unlimited" : tasks,
                          storage,
                          features,
                          restrictionJson,
                          coverage));
              return new Projection(
                  plan,
                  period,
                  from,
                  until,
                  paidThrough,
                  projects,
                  members,
                  tasks,
                  storage,
                  features,
                  restrictionJson,
                  coverage,
                  signature);
            });
  }

  private Mono<Entitlement> insert(
      DatabaseClient db, UUID tenant, long version, Projection p, Instant now) {
    UUID operation = UUID.randomUUID();
    var statement =
        db.sql(
                """
                INSERT INTO taska.entitlement_snapshots(tenant_id,version,plan_version_id,period_id,
                  valid_from,valid_until,paid_through,max_projects,max_members,max_tasks_per_project,
                  max_storage_bytes,features,restrictions,coverage_state,state_key,source_operation_id,created_at)
                VALUES(:tenant,:version,:plan,:period,:from,:until,:paid,:projects,:members,:tasks,:storage,
                  CAST(:features AS jsonb),CAST(:restrictions AS jsonb),:coverage,:signature,:operation,:now)
                """)
            .bind("tenant", tenant)
            .bind("version", version)
            .bind("plan", p.plan())
            .bind("from", p.from().atOffset(ZoneOffset.UTC))
            .bind("projects", p.projects())
            .bind("members", p.members())
            .bind("storage", p.storage())
            .bind("features", p.features())
            .bind("restrictions", p.restrictions())
            .bind("coverage", p.coverage())
            .bind("signature", p.signature())
            .bind("operation", operation)
            .bind("now", now.atOffset(ZoneOffset.UTC));
    statement =
        p.period() == null
            ? statement.bindNull("period", UUID.class)
            : statement.bind("period", p.period());
    statement =
        p.until() == null
            ? statement.bindNull("until", OffsetDateTime.class)
            : statement.bind("until", p.until().atOffset(ZoneOffset.UTC));
    statement =
        p.paidThrough() == null
            ? statement.bindNull("paid", OffsetDateTime.class)
            : statement.bind("paid", p.paidThrough().atOffset(ZoneOffset.UTC));
    statement =
        p.tasks() == null
            ? statement.bindNull("tasks", Long.class)
            : statement.bind("tasks", p.tasks());
    return statement
        .fetch()
        .rowsUpdated()
        .then(latest(db, tenant))
        .map(this::dto)
        .flatMap(snapshot -> event(db, tenant, version, operation, snapshot).thenReturn(snapshot));
  }

  private Mono<Void> event(
      DatabaseClient db, UUID tenant, long version, UUID operation, Entitlement e) {
    var body =
        json.createObjectNode()
            .put("tenantId", e.getTenantId())
            .put("version", e.getVersion())
            .put("planVersionId", e.getPlanVersionId())
            .put("validFrom", e.getValidFrom())
            .put("issuedAt", e.getIssuedAt());
    if (e.hasCoveragePeriodId()) body.put("coveragePeriodId", e.getCoveragePeriodId());
    if (e.hasPaidThrough()) body.put("paidThrough", e.getPaidThrough());
    if (e.hasValidUntil()) body.put("validUntil", e.getValidUntil());
    var restrictions = body.putArray("restrictions");
    e.getRestrictionsList().forEach(r -> restrictions.add(r.name()));
    var features = body.putArray("features");
    e.getFeaturesList().forEach(features::add);
    var limits = body.putArray("limits");
    e.getLimitsList()
        .forEach(
            limit -> {
              var item =
                  limits
                      .addObject()
                      .put("kind", limit.getKind().name())
                      .put("unlimited", limit.getUnlimited());
              if (limit.hasLimit()) item.put("limit", limit.getLimit());
            });
    return db.sql(
            """
            INSERT INTO taska.outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,
              event_type,schema_version,payload,operation_id,status,created_at)
            VALUES(:id,:tenant,'Entitlement',:tenant,:version,'EntitlementSnapshotChanged',2,
              CAST(:payload AS jsonb),:operation,'NEW',:now)
            """)
        .bind("id", UUID.randomUUID())
        .bind("tenant", tenant)
        .bind("version", version)
        .bind("payload", json.writeValueAsString(body))
        .bind("operation", operation)
        .bind("now", Instant.parse(e.getIssuedAt()).atOffset(ZoneOffset.UTC))
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Entitlement dto(Map<String, Object> row) {
    var out =
        Entitlement.newBuilder()
            .setTenantId(row.get("tenant_id").toString())
            .setVersion(row.get("version").toString())
            .setPlanVersionId(row.get("plan_version_id").toString())
            .setValidFrom(instant(row.get("valid_from")).toString())
            .setIssuedAt(instant(row.get("created_at")).toString())
            .addLimits(limit(QuotaLimit.KindValue.KIND_PROJECT, row.get("max_projects")))
            .addLimits(limit(QuotaLimit.KindValue.KIND_SEAT, row.get("max_members")))
            .addLimits(limit(QuotaLimit.KindValue.KIND_STORAGE_BYTES, row.get("max_storage_bytes")))
            .addLimits(
                limit(QuotaLimit.KindValue.KIND_TASK_LIFETIME, row.get("max_tasks_per_project")));
    if (row.get("period_id") != null) out.setCoveragePeriodId(row.get("period_id").toString());
    if (row.get("valid_until") != null)
      out.setValidUntil(instant(row.get("valid_until")).toString());
    if (row.get("paid_through") != null)
      out.setPaidThrough(instant(row.get("paid_through")).toString());
    for (JsonNode value : json.readTree(row.get("features_text").toString()))
      out.addFeatures(value.asText());
    for (JsonNode value : json.readTree(row.get("restrictions_text").toString()))
      out.addRestrictions(RestrictionCode.valueOf(value.asText()));
    return out.build();
  }

  private static QuotaLimit limit(QuotaLimit.KindValue kind, Object value) {
    var out = QuotaLimit.newBuilder().setKind(kind).setUnlimited(value == null);
    if (value != null) out.setLimit(value.toString());
    return out.build();
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((OffsetDateTime) value).toInstant();
  }
}
