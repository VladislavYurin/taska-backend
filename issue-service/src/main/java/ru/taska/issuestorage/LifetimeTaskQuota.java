package ru.taska.issuestorage;

import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Issue-owned lifetime admission. Authority must check current membership/project ACL and
 * lifecycle. RETIRE additionally requires the project owner's durable, confirmed physical deletion
 * receipt. Neither a global role string nor request DTO can supply a bypass/deletion grant.
 */
public final class LifetimeTaskQuota {
  public enum Use {
    CREATE,
    READ_PROJECT,
    READ_TENANT,
    RETIRE
  }

  public record Grant(boolean bypassBusinessRules) {}

  @FunctionalInterface
  public interface Authority {
    Mono<Grant> authorize(VerifiedTenantContext context, Use use, UUID project);
  }

  private record State(
      long version, long entitlement, long maximum, Long limit, boolean unlimited) {}

  private record Terms(Entitlement snapshot, QuotaLimit limit, long version) {}

  private final TenantTransactionExecutor transactions;
  private final Authority authority;
  private final EntitlementProjection.Source billing;
  private final Clock clock;

  public LifetimeTaskQuota(
      TenantTransactionExecutor transactions,
      Authority authority,
      EntitlementProjection.Source billing,
      Clock clock) {
    this.transactions = Objects.requireNonNull(transactions);
    this.authority = Objects.requireNonNull(authority);
    this.billing = Objects.requireNonNull(billing);
    this.clock = Objects.requireNonNull(clock);
  }

  public static LifetimeTaskQuota unavailable(TenantTransactionExecutor tx, Clock clock) {
    return new LifetimeTaskQuota(
        tx,
        (c, u, p) -> Mono.error(fail(DomainStatus.UNAVAILABLE, "POLICY_NOT_CONFIGURED")),
        (t, v) -> Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE")),
        clock);
  }

  Mono<Grant> authorize(Use use, UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              String scope = (use == Use.READ_PROJECT || use == Use.READ_TENANT) ? "read" : "write";
              if (!c.scopes().contains(scope))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return authority
                  .authorize(c, use, project)
                  .timeout(Duration.ofSeconds(5))
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "AUTHORITY_UNAVAILABLE")));
            });
  }

  /** Must join the create transaction after its idempotency claim. */
  Mono<Integer> allocate(DatabaseClient db, UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return lock(db, tenant)
                  .zipWhen(state -> authorize(Use.CREATE, project))
                  .flatMap(
                      pair -> {
                        var state = pair.getT1();
                        var grant = pair.getT2();
                        return terms(tenant, state.entitlement())
                            .flatMap(
                                terms ->
                                    maximum(db, tenant)
                                        .flatMap(
                                            max -> {
                                              if (!grant.bypassBusinessRules()) {
                                                requireCurrent(terms.snapshot());
                                                if (!terms.limit().getUnlimited()
                                                    && max > limit(terms))
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.FAILED_PRECONDITION,
                                                          "TENANT_READ_ONLY_OVER_QUOTA"));
                                              }
                                              return db.sql(
                                                      "INSERT INTO"
                                                          + " taska.project_counters(tenant_id,project_id,next_issue_number)"
                                                          + " VALUES(:t,:p,1) ON CONFLICT DO"
                                                          + " NOTHING")
                                                  .bind("t", tenant)
                                                  .bind("p", project)
                                                  .fetch()
                                                  .rowsUpdated()
                                                  .then(
                                                      db.sql(
                                                              """
                                                              UPDATE taska.project_counters SET next_issue_number=next_issue_number+1,lifetime_created=lifetime_created+1,version=version+1
                                                              WHERE tenant_id=:t AND project_id=:p AND retired_at IS NULL
                                                                AND next_issue_number<2147483647 AND version<9223372036854775807 AND lifetime_created<9223372036854775807
                                                                AND (:bypass OR :unlimited OR lifetime_created<:lim)
                                                              RETURNING next_issue_number-1 AS allocated
                                                              """)
                                                          .bind("t", tenant)
                                                          .bind("p", project)
                                                          .bind(
                                                              "bypass", grant.bypassBusinessRules())
                                                          .bind(
                                                              "unlimited",
                                                              terms.limit().getUnlimited())
                                                          .bind("lim", limit(terms))
                                                          .map(
                                                              (r, m) ->
                                                                  r.get("allocated", Integer.class))
                                                          .one())
                                                  .switchIfEmpty(
                                                      allocationFailure(
                                                          db, tenant, project, terms, grant))
                                                  .flatMap(
                                                      number ->
                                                          observe(
                                                                  db,
                                                                  tenant,
                                                                  state,
                                                                  terms,
                                                                  true,
                                                                  c.requestId())
                                                              .thenReturn(number));
                                            }));
                      });
            });
  }

  private Mono<Integer> allocationFailure(
      DatabaseClient db, UUID tenant, UUID project, Terms terms, Grant grant) {
    return db.sql(
            "SELECT retired_at,lifetime_created FROM taska.project_counters WHERE tenant_id=:t AND"
                + " project_id=:p")
        .bind("t", tenant)
        .bind("p", project)
        .fetch()
        .one()
        .flatMap(
            row -> {
              if (row.get("retired_at") != null)
                return Mono.error(fail(DomainStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
              if (!grant.bypassBusinessRules()
                  && !terms.limit().getUnlimited()
                  && ((Long) row.get("lifetime_created")) >= limit(terms))
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "QUOTA_EXCEEDED"));
              return Mono.error(fail(DomainStatus.RESOURCE_EXHAUSTED, "Issue numbering exhausted"));
            });
  }

  public Mono<QuotaUsage> project(GetProjectTaskQuotaRpcRequest request) {
    UUID tenant = uuid(request.getTenantId()), project = uuid(request.getProjectId());
    return scoped(tenant, "getProjectTaskQuota", Use.READ_PROJECT, project)
        .then(
            transactions.execute(
                db ->
                    lockAuthorized(db, tenant, Use.READ_PROJECT, project)
                        .flatMap(
                            state ->
                                terms(tenant, state.entitlement())
                                    .flatMap(
                                        terms ->
                                            db.sql(
                                                    "SELECT lifetime_created,retired_at FROM"
                                                        + " taska.project_counters WHERE"
                                                        + " tenant_id=:t AND project_id=:p")
                                                .bind("t", tenant)
                                                .bind("p", project)
                                                .fetch()
                                                .one()
                                                .flatMap(
                                                    row ->
                                                        row.get("retired_at") != null
                                                            ? Mono.<Long>error(
                                                                fail(
                                                                    DomainStatus.NOT_FOUND,
                                                                    "PROJECT_NOT_FOUND"))
                                                            : Mono.just(
                                                                (Long) row.get("lifetime_created")))
                                                // Authority already established a live accessible
                                                // project, including one with no issues yet.
                                                .defaultIfEmpty(0L)
                                                .flatMap(
                                                    used ->
                                                        observe(
                                                                db,
                                                                tenant,
                                                                state,
                                                                terms,
                                                                false,
                                                                requestId())
                                                            .map(
                                                                updated -> {
                                                                  var b =
                                                                      QuotaUsage.newBuilder()
                                                                          .setKind(
                                                                              QuotaUsage.KindValue
                                                                                  .KIND_TASK_LIFETIME)
                                                                          .setProjectId(
                                                                              project.toString())
                                                                          .setUsed(
                                                                              Long.toString(used))
                                                                          .setReserved("0")
                                                                          .setVersion(
                                                                              Long.toString(
                                                                                  updated
                                                                                      .version()))
                                                                          .setUnlimited(
                                                                              terms
                                                                                  .limit()
                                                                                  .getUnlimited());
                                                                  if (terms.limit().hasLimit())
                                                                    b.setLimit(
                                                                        terms.limit().getLimit());
                                                                  return b.build();
                                                                }))))));
  }

  public Mono<TenantTaskQuotaSummary> summary(GetTenantTaskQuotaSummaryRequest request) {
    UUID tenant = uuid(request.getTenantId());
    return scoped(tenant, "getTenantTaskQuotaSummary", Use.READ_TENANT, null)
        .then(
            transactions.execute(
                db ->
                    lockAuthorized(db, tenant, Use.READ_TENANT, null)
                        .flatMap(
                            state ->
                                terms(tenant, state.entitlement())
                                    .flatMap(
                                        terms ->
                                            observe(db, tenant, state, terms, false, requestId())
                                                .map(
                                                    updated ->
                                                        TenantTaskQuotaSummary.newBuilder()
                                                            .setTenantId(tenant.toString())
                                                            .setMaximumCreated(
                                                                Long.toString(updated.maximum()))
                                                            .setVersion(
                                                                Long.toString(updated.version()))
                                                            .setEntitlementVersion(
                                                                Long.toString(
                                                                    updated.entitlement()))
                                                            .setOverLimit(
                                                                !updated.unlimited()
                                                                    && updated.maximum()
                                                                        > updated.limit())
                                                            .build())))));
  }

  /**
   * TAS-459 joins its owner-confirmed project deletion transaction; archiving must never call this.
   */
  public Mono<Void> retire(DatabaseClient db, UUID project) {
    Objects.requireNonNull(project);
    return authorize(Use.RETIRE, project)
        .then(VerifiedTenantContext.current())
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return lockAuthorized(db, tenant, Use.RETIRE, project)
                  .flatMap(
                      state ->
                          db.sql(
                                  "INSERT INTO taska.project_counters(tenant_id,project_id)"
                                      + " VALUES(:t,:p) ON CONFLICT DO NOTHING")
                              .bind("t", tenant)
                              .bind("p", project)
                              .fetch()
                              .rowsUpdated()
                              .then(
                                  db.sql(
                                          "SELECT retired_at,version FROM taska.project_counters"
                                              + " WHERE tenant_id=:t AND project_id=:p")
                                      .bind("t", tenant)
                                      .bind("p", project)
                                      .fetch()
                                      .one())
                              .flatMap(
                                  row -> {
                                    if (row.get("retired_at") != null) return Mono.<Void>empty();
                                    if (((Long) row.get("version")) == Long.MAX_VALUE
                                        || state.version() == Long.MAX_VALUE)
                                      return Mono.error(
                                          fail(
                                              DomainStatus.RESOURCE_EXHAUSTED,
                                              "QUOTA_VERSION_EXHAUSTED"));
                                    return db.sql(
                                            "UPDATE taska.project_counters SET"
                                                + " retired_at=:now,version=version+1 WHERE"
                                                + " tenant_id=:t AND project_id=:p AND retired_at"
                                                + " IS NULL")
                                        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
                                        .bind("t", tenant)
                                        .bind("p", project)
                                        .fetch()
                                        .rowsUpdated()
                                        .then(maximum(db, tenant))
                                        .flatMap(
                                            max -> {
                                              long version = state.version() + 1;
                                              var event =
                                                  db.sql(
                                                          "INSERT INTO"
                                                              + " taska.task_quota_outbox(tenant_id,version,entitlement_version,maximum_created,over_limit,request_id)"
                                                              + " VALUES(:t,:v,:e,:max,:over,:request)")
                                                      .bind("t", tenant)
                                                      .bind("v", version)
                                                      .bind("e", state.entitlement())
                                                      .bind("max", max)
                                                      .bind("request", c.requestId());
                                              // Cleanup is allowed during billing outage. Unknown
                                              // commercial facts stay unknown.
                                              event =
                                                  state.entitlement() == 0
                                                      ? event.bindNull("over", Boolean.class)
                                                      : event.bind(
                                                          "over",
                                                          !state.unlimited()
                                                              && max > state.limit());
                                              return db.sql(
                                                      "UPDATE taska.task_lifetime_quota_state SET"
                                                          + " maximum_created=:max,version=:v WHERE"
                                                          + " tenant_id=:t")
                                                  .bind("t", tenant)
                                                  .bind("max", max)
                                                  .bind("v", version)
                                                  .fetch()
                                                  .rowsUpdated()
                                                  .then(event.fetch().rowsUpdated())
                                                  .then();
                                            });
                                  }));
            });
  }

  private Mono<State> lockAuthorized(DatabaseClient db, UUID tenant, Use use, UUID project) {
    return lock(db, tenant).flatMap(state -> authorize(use, project).thenReturn(state));
  }

  private Mono<Grant> scoped(UUID tenant, String action, Use use, UUID project) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                tenant.toString().equals(c.tenantId()) && action.equals(c.action())
                    ? authorize(use, project)
                    : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED")));
  }

  private String requestId() {
    return UUID.randomUUID().toString();
  }

  private Mono<State> lock(DatabaseClient db, UUID tenant) {
    return db.sql(
            "INSERT INTO taska.task_lifetime_quota_state(tenant_id) VALUES(:t) ON CONFLICT DO"
                + " NOTHING")
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql("SELECT * FROM taska.task_lifetime_quota_state WHERE tenant_id=:t FOR UPDATE")
                .bind("t", tenant)
                .map(
                    (r, m) ->
                        new State(
                            r.get("version", Long.class),
                            r.get("entitlement_version", Long.class),
                            r.get("maximum_created", Long.class),
                            r.get("task_limit", Long.class),
                            r.get("unlimited", Boolean.class)))
                .one());
  }

  private Mono<Long> maximum(DatabaseClient db, UUID tenant) {
    return db.sql(
            "SELECT COALESCE(max(lifetime_created),0) AS n FROM taska.project_counters WHERE"
                + " tenant_id=:t AND retired_at IS NULL")
        .bind("t", tenant)
        .map((r, m) -> r.get("n", Long.class))
        .one();
  }

  private Mono<State> observe(
      DatabaseClient db, UUID tenant, State old, Terms terms, boolean force, String request) {
    return maximum(db, tenant)
        .flatMap(
            max -> {
              Long value = terms.limit().hasLimit() ? Long.valueOf(terms.limit().getLimit()) : null;
              if (!force
                  && old.entitlement() == terms.version()
                  && old.maximum() == max
                  && Objects.equals(old.limit(), value)
                  && old.unlimited() == terms.limit().getUnlimited()) return Mono.just(old);
              if (old.version() == Long.MAX_VALUE)
                return Mono.error(fail(DomainStatus.RESOURCE_EXHAUSTED, "QUOTA_VERSION_EXHAUSTED"));
              var next =
                  new State(
                      old.version() + 1, terms.version(), max, value, terms.limit().getUnlimited());
              var update =
                  db.sql(
                          "UPDATE taska.task_lifetime_quota_state SET"
                              + " version=:v,entitlement_version=:e,maximum_created=:max,task_limit=:lim,unlimited=:u"
                              + " WHERE tenant_id=:t")
                      .bind("t", tenant)
                      .bind("v", next.version())
                      .bind("e", next.entitlement())
                      .bind("max", max)
                      .bind("u", next.unlimited());
              update =
                  value == null ? update.bindNull("lim", Long.class) : update.bind("lim", value);
              // Separate transactional outbox: issue outbox deliberately requires an existing ISSUE
              // FK.
              return update
                  .fetch()
                  .rowsUpdated()
                  .then(
                      db.sql(
                              """
                              INSERT INTO taska.task_quota_outbox(tenant_id,version,entitlement_version,maximum_created,over_limit,request_id)
                              VALUES(:t,:v,:e,:max,:over,:request)
                              """)
                          .bind("t", tenant)
                          .bind("v", next.version())
                          .bind("e", next.entitlement())
                          .bind("max", max)
                          .bind("over", !next.unlimited() && max > next.limit())
                          .bind("request", request)
                          .fetch()
                          .rowsUpdated())
                  .thenReturn(next);
            });
  }

  private Mono<Terms> terms(UUID tenant, long minimum) {
    return billing
        .current(tenant, minimum)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE")))
        .map(EntitlementProjection::validate)
        .map(
            e -> {
              if (!tenant.toString().equals(e.getTenantId()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              long version = Long.parseLong(e.getVersion());
              if (version < minimum) throw fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE");
              // Read endpoints may report restrictions, but never treat an expired snapshot as
              // current.
              Instant now = clock.instant();
              if (now.isBefore(Instant.parse(e.getValidFrom()))
                  || (e.hasValidUntil() && !now.isBefore(Instant.parse(e.getValidUntil())))
                  || (e.hasPaidThrough() && !now.isBefore(Instant.parse(e.getPaidThrough()))))
                throw fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_EXPIRED");
              return new Terms(
                  e,
                  e.getLimitsList().stream()
                      .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_TASK_LIFETIME)
                      .findFirst()
                      .orElseThrow(),
                  version);
            });
  }

  private static long limit(Terms t) {
    return t.limit().hasLimit() ? Long.parseLong(t.limit().getLimit()) : Long.MAX_VALUE;
  }

  private static void requireCurrent(Entitlement e) {
    if (!e.getRestrictionsList().isEmpty())
      throw fail(DomainStatus.FAILED_PRECONDITION, "ENTITLEMENT_RESTRICTED");
  }

  private static UUID uuid(String s) {
    try {
      UUID id = UUID.fromString(s);
      if (id.equals(new UUID(0, 0)) || !id.toString().equals(s))
        throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(DomainStatus.INVALID_ARGUMENT, "INVALID_ID");
    }
  }

  private static DomainException fail(DomainStatus status, String code) {
    return new DomainException(status, code);
  }
}
