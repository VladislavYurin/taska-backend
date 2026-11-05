package ru.taska.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/** SEAT reservations and membership writes share the tenant database and company lock. */
public final class SeatQuotaStore {
  public enum Command {
    RESERVE,
    COMMIT,
    RELEASE
  }

  public record Grant(boolean bypassLimit) {}

  public interface Authority {
    /** Current command-specific identity, tenant/lifecycle and invitation/admin purpose checks. */
    Mono<Grant> authorize(VerifiedTenantContext context, Command command);
  }

  public record Reservation(
      UUID id,
      UUID operation,
      UUID tenant,
      UUID resource,
      String state,
      long fence,
      long version,
      Instant expiresAt) {}

  private record Counter(long used, long reserved, long version, long entitlement) {}

  private record Terms(long version, QuotaLimit limit, Entitlement snapshot) {}

  private final TenantTransactionExecutor transactions;
  private final Authority authority;
  private final EntitlementProjection.Source billing;
  private final Clock clock;
  private final ru.taska.entitlement.TenantTaskQuotaSource tasks;
  private final ru.taska.quota.ProjectQuotaProtocol.UsageSource projects;
  private final ru.taska.quota.StorageQuotaProtocol.UsageSource storage;

  public SeatQuotaStore(
      TenantTransactionExecutor transactions,
      Authority authority,
      EntitlementProjection.Source billing,
      Clock clock) {
    this(
        transactions,
        authority,
        billing,
        clock,
        t -> Mono.error(fail(DomainStatus.UNAVAILABLE, "TASK_QUOTA_UNAVAILABLE")));
  }

  public SeatQuotaStore(
      TenantTransactionExecutor transactions,
      Authority authority,
      EntitlementProjection.Source billing,
      Clock clock,
      ru.taska.entitlement.TenantTaskQuotaSource tasks) {
    this(
        transactions,
        authority,
        billing,
        clock,
        tasks,
        t -> Mono.error(fail(DomainStatus.UNAVAILABLE, "PROJECT_QUOTA_UNAVAILABLE")));
  }

  public SeatQuotaStore(
      TenantTransactionExecutor transactions,
      Authority authority,
      EntitlementProjection.Source billing,
      Clock clock,
      ru.taska.entitlement.TenantTaskQuotaSource tasks,
      ru.taska.quota.ProjectQuotaProtocol.UsageSource projects) {
    this(
        transactions,
        authority,
        billing,
        clock,
        tasks,
        projects,
        t -> Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_QUOTA_UNAVAILABLE")));
  }

  public SeatQuotaStore(
      TenantTransactionExecutor transactions,
      Authority authority,
      EntitlementProjection.Source billing,
      Clock clock,
      ru.taska.entitlement.TenantTaskQuotaSource tasks,
      ru.taska.quota.ProjectQuotaProtocol.UsageSource projects,
      ru.taska.quota.StorageQuotaProtocol.UsageSource storage) {
    this.storage = Objects.requireNonNull(storage);
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
    this.transactions = Objects.requireNonNull(transactions);
    this.authority = Objects.requireNonNull(authority);
    this.billing = Objects.requireNonNull(billing);
    this.clock = Objects.requireNonNull(clock);
  }

  public Mono<Reservation> reserve(UUID operation, UUID invitation, Instant expiresAt) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(invitation);
    Objects.requireNonNull(expiresAt);
    Instant expiry = expiresAt.truncatedTo(ChronoUnit.MICROS);
    return authorized(
        Command.RESERVE,
        (tenant, grant) ->
            terms(tenant)
                .flatMap(
                    terms ->
                        transactions.execute(
                            db ->
                                lock(db, tenant)
                                    .then(refresh(db, tenant))
                                    .flatMap(
                                        counter -> {
                                          if (terms.version() < counter.entitlement())
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"));
                                          return observe(db, tenant, terms.version())
                                              .then(
                                                  reservation(db, tenant, operation)
                                                      .flatMap(
                                                          previous ->
                                                              requestHash(db, tenant, operation)
                                                                  .flatMap(
                                                                      hash ->
                                                                          hash.equals(
                                                                                  hash(
                                                                                      tenant,
                                                                                      operation,
                                                                                      invitation,
                                                                                      expiry))
                                                                              ? Mono.just(previous)
                                                                              : Mono.error(
                                                                                  fail(
                                                                                      DomainStatus
                                                                                          .ALREADY_EXISTS,
                                                                                      "IDEMPOTENCY_CONFLICT"))))
                                                      .switchIfEmpty(
                                                          Mono.defer(
                                                              () ->
                                                                  insertReservation(
                                                                      db,
                                                                      tenant,
                                                                      grant,
                                                                      terms,
                                                                      counter,
                                                                      operation,
                                                                      invitation,
                                                                      expiry))));
                                        }))));
  }

  private Mono<Reservation> insertReservation(
      DatabaseClient db,
      UUID tenant,
      Grant grant,
      Terms terms,
      Counter counter,
      UUID operation,
      UUID invitation,
      Instant expiry) {
    if (!expiry.isAfter(clock.instant())) return Mono.error(invalid());
    requireFresh(terms.snapshot());
    long total = add(counter.used(), counter.reserved());
    if (!grant.bypassLimit()
        && !terms.limit().getUnlimited()
        && total >= Long.parseLong(terms.limit().getLimit()))
      return Mono.error(quota(total, terms.limit().getLimit()));
    add(total, 1);
    return db.sql(
            """
            INSERT INTO taska.quota_reservations(id,tenant_id,resource,scope_id,operation_id,
              resource_owner,resource_id,request_hash,amount,state,entitlement_version,expires_at,created_at)
            VALUES(:id,:t,'SEAT',:t,:op,'tenant-service',:resource,:hash,1,'ACTIVE',:entitlement,:expires,:now)
            """)
        .bind("id", UUID.randomUUID())
        .bind("t", tenant)
        .bind("op", operation)
        .bind("resource", invitation)
        .bind("hash", hash(tenant, operation, invitation, expiry))
        .bind("entitlement", terms.version())
        .bind("expires", offset(expiry))
        .bind("now", offset(clock.instant()))
        .fetch()
        .rowsUpdated()
        .then(refresh(db, tenant))
        .then(reservation(db, tenant, operation));
  }

  /** Callback must compose the actual membership mutation/outbox on this exact connection. */
  public Mono<Reservation> commit(
      UUID operation,
      UUID reservation,
      long fence,
      UUID member,
      Function<DatabaseClient, Mono<Void>> membershipMutation) {
    Objects.requireNonNull(member);
    Objects.requireNonNull(membershipMutation);
    return authorized(
        Command.COMMIT,
        (tenant, grant) ->
            transactions.execute(
                db ->
                    lock(db, tenant)
                        .then(refresh(db, tenant))
                        .flatMap(
                            before ->
                                required(db, tenant, operation, reservation, fence)
                                    .flatMap(
                                        current -> {
                                          if (current.state().equals("COMMITTED"))
                                            return member.equals(current.resource())
                                                ? Mono.just(current)
                                                : Mono.error(
                                                    fail(
                                                        DomainStatus.ALREADY_EXISTS,
                                                        "IDEMPOTENCY_CONFLICT"));
                                          if (!current.state().equals("ACTIVE")
                                              || !clock.instant().isBefore(current.expiresAt()))
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "RESERVATION_EXPIRED"));
                                          // An old valid invitation can be accepted during
                                          // financial READ_ONLY
                                          // (L03).
                                          // Current pause/block/purpose authority is still
                                          // mandatory,
                                          // independent of billing.
                                          return Mono.defer(() -> membershipMutation.apply(db))
                                              .then(
                                                  db.sql(
                                                          "SELECT version FROM"
                                                              + " taska.tenant_memberships WHERE"
                                                              + " tenant_id=:t AND user_id=:member"
                                                              + " AND status IN"
                                                              + " ('ACTIVE','SUSPENDED')")
                                                      .bind("t", tenant)
                                                      .bind("member", member)
                                                      .map((r, m) -> r.get("version", Long.class))
                                                      .one()
                                                      .switchIfEmpty(
                                                          Mono.error(
                                                              fail(
                                                                  DomainStatus.FAILED_PRECONDITION,
                                                                  "MEMBERSHIP_REQUIRED"))))
                                              .flatMap(
                                                  ownerVersion ->
                                                      db.sql(
                                                              """
                                                              INSERT INTO taska.quota_effects(tenant_id,operation_id,resource,scope_id,effect_kind,
                                                                amount,resource_id,owner_version,applied_at) VALUES(:t,:op,'SEAT',:t,'COMMIT',1,:member,:version,:now)
                                                              """)
                                                          .bind("t", tenant)
                                                          .bind("op", operation)
                                                          .bind("member", member)
                                                          .bind("version", ownerVersion)
                                                          .bind("now", offset(clock.instant()))
                                                          .fetch()
                                                          .rowsUpdated())
                                              .then(
                                                  Mono.defer(
                                                      () ->
                                                          db.sql(
                                                                  "UPDATE taska.quota_reservations"
                                                                      + " SET state='COMMITTED',resource_id=:member,committed_at=:now,version=version+1"
                                                                      + " WHERE tenant_id=:t AND"
                                                                      + " id=:id AND state='ACTIVE'"
                                                                      + " AND fence=:fence AND"
                                                                      + " expires_at>:now")
                                                              .bind("member", member)
                                                              .bind("now", offset(clock.instant()))
                                                              .bind("t", tenant)
                                                              .bind("id", reservation)
                                                              .bind("fence", fence)
                                                              .fetch()
                                                              .rowsUpdated()
                                                              .flatMap(
                                                                  rows ->
                                                                      rows == 1
                                                                          ? Mono.<Void>empty()
                                                                          : Mono.error(
                                                                              fail(
                                                                                  DomainStatus
                                                                                      .FAILED_PRECONDITION,
                                                                                  "RESERVATION_EXPIRED")))))
                                              .then(refresh(db, tenant))
                                              .flatMap(
                                                  after ->
                                                      after.used() > add(before.used(), 1)
                                                              || add(after.used(), after.reserved())
                                                                  > add(
                                                                      before.used(),
                                                                      before.reserved())
                                                          ? Mono.error(
                                                              fail(
                                                                  DomainStatus.FAILED_PRECONDITION,
                                                                  "MEMBERSHIP_EXCEEDS_RESERVATION"))
                                                          : reservation(db, tenant, operation));
                                        }))));
  }

  /** Releases only a pending reservation, never usage belonging to an existing membership. */
  public Mono<Reservation> release(UUID operation, UUID reservation, long fence) {
    return authorized(
        Command.RELEASE,
        (tenant, grant) ->
            transactions.execute(
                db ->
                    lock(db, tenant)
                        .then(refresh(db, tenant))
                        .then(
                            reservation(db, tenant, operation)
                                .switchIfEmpty(
                                    Mono.error(
                                        fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"))))
                        .flatMap(
                            current -> {
                              if (!current.id().equals(reservation)) return Mono.error(invalid());
                              if (current.state().equals("RELEASED")
                                  || current.state().equals("EXPIRED"))
                                return fence == current.fence() - 1
                                    ? Mono.just(current)
                                    : Mono.error(
                                        fail(DomainStatus.FAILED_PRECONDITION, "STALE_FENCE"));
                              if (current.fence() != fence)
                                return Mono.error(
                                    fail(DomainStatus.FAILED_PRECONDITION, "STALE_FENCE"));
                              if (!current.state().equals("ACTIVE"))
                                return Mono.error(
                                    fail(
                                        DomainStatus.FAILED_PRECONDITION, "RESERVATION_COMMITTED"));
                              return db.sql(
                                      "UPDATE taska.quota_reservations SET"
                                          + " state='RELEASED',fence=fence+1,released_at=:now,version=version+1"
                                          + " WHERE tenant_id=:t AND id=:id")
                                  .bind("now", offset(clock.instant()))
                                  .bind("t", tenant)
                                  .bind("id", reservation)
                                  .fetch()
                                  .rowsUpdated()
                                  .then(refresh(db, tenant))
                                  .then(reservation(db, tenant, operation));
                            })));
  }

  public Mono<QuotaSnapshot> get(GetTenantQuotasRpcRequest request) {
    UUID tenant = TenantRegistryService.uuid(request.getTenantId());
    return VerifiedTenantContext.current()
        .flatMap(
            context -> {
              if (!tenant.toString().equals(context.tenantId())
                  || context.actorId() == null
                  || !"getTenantQuotas".equals(context.action())
                  || !context.scopes().contains("read"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return transactions
                  .execute(db -> reader(db, tenant, context.actorId()).thenReturn(true))
                  .then(
                      Mono.zip(
                          terms(tenant),
                          taskSummary(tenant),
                          projectUsage(tenant),
                          storageUsage(tenant)))
                  .flatMap(
                      pair -> {
                        var terms = pair.getT1();
                        var task = pair.getT2();
                        var project = pair.getT3();
                        var storageBytes = pair.getT4();
                        if (terms.version() < Long.parseLong(task.getEntitlementVersion()))
                          return Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"));
                        return transactions.execute(
                            db ->
                                lock(db, tenant)
                                    .then(reader(db, tenant, context.actorId()))
                                    .then(refresh(db, tenant))
                                    .flatMap(
                                        counter -> {
                                          if (terms.version() < counter.entitlement())
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"));
                                          return observe(db, tenant, terms.version())
                                              .then(
                                                  observeTasks(
                                                      db,
                                                      tenant,
                                                      Long.parseLong(task.getVersion())))
                                              .then(
                                                  observeProjects(
                                                      db,
                                                      tenant,
                                                      Long.parseLong(project.getVersion())))
                                              .then(
                                                  observeStorage(
                                                      db,
                                                      tenant,
                                                      Long.parseLong(storageBytes.getVersion())))
                                              .then(refresh(db, tenant));
                                        })
                                    .map(
                                        counter -> {
                                          var usage =
                                              QuotaUsage.newBuilder()
                                                  .setKind(QuotaUsage.KindValue.KIND_SEAT)
                                                  .setUsed(Long.toString(counter.used()))
                                                  .setReserved(Long.toString(counter.reserved()))
                                                  .setVersion(Long.toString(counter.version()))
                                                  .setUnlimited(terms.limit().getUnlimited());
                                          if (terms.limit().hasLimit())
                                            usage.setLimit(terms.limit().getLimit());
                                          return QuotaSnapshot.newBuilder()
                                              .setTenantId(tenant.toString())
                                              .setVersion(Long.toString(counter.version()))
                                              .addQuotas(usage)
                                              .addQuotas(taskUsage(terms.snapshot(), task))
                                              .addQuotas(
                                                  projectUsageWithTerms(project, terms.snapshot()))
                                              .addQuotas(
                                                  storageUsageWithTerms(
                                                      storageBytes, terms.snapshot()))
                                              .setAsOf(clock.instant().toString())
                                              .build();
                                        }));
                      });
            });
  }

  private Mono<QuotaUsage> projectUsage(UUID tenant) {
    return projects
        .current(tenant)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "PROJECT_QUOTA_UNAVAILABLE")))
        .map(
            usage -> {
              try {
                if (usage.getKind() != QuotaUsage.KindValue.KIND_PROJECT
                    || usage.hasProjectId()
                    || !usage.getUsed().matches("0|[1-9][0-9]{0,18}")
                    || !usage.getReserved().matches("0|[1-9][0-9]{0,18}")
                    || !usage.getVersion().matches("[1-9][0-9]{0,18}"))
                  throw new IllegalArgumentException();
                Long.parseLong(usage.getUsed());
                Long.parseLong(usage.getReserved());
                Long.parseLong(usage.getVersion());
              } catch (RuntimeException e) {
                throw fail(DomainStatus.UNAVAILABLE, "INVALID_PROJECT_QUOTA");
              }
              return usage;
            });
  }

  private static QuotaUsage projectUsageWithTerms(QuotaUsage usage, Entitlement terms) {
    var limit =
        terms.getLimitsList().stream()
            .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_PROJECT)
            .findFirst()
            .orElseThrow();
    var b = usage.toBuilder().clearLimit().setUnlimited(limit.getUnlimited());
    if (limit.hasLimit()) b.setLimit(limit.getLimit());
    return b.build();
  }

  private Mono<Void> observeProjects(DatabaseClient db, UUID tenant, long version) {
    return db.sql(
            "UPDATE taska.quota_counters SET project_quota_version=:v,version=version+CASE WHEN"
                + " project_quota_version<>:v THEN 1 ELSE 0 END WHERE tenant_id=:t AND"
                + " resource='SEAT' AND project_quota_version<=:v AND version<9223372036854775807")
        .bind("t", tenant)
        .bind("v", version)
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 1
                    ? Mono.<Void>empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "PROJECT_QUOTA_STALE")));
  }

  private Mono<QuotaUsage> storageUsage(UUID tenant) {
    return storage
        .current(tenant)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_QUOTA_UNAVAILABLE")))
        .map(
            usage -> {
              try {
                if (usage.getKind() != QuotaUsage.KindValue.KIND_STORAGE_BYTES
                    || usage.hasProjectId()
                    || !usage.getUsed().matches("0|[1-9][0-9]{0,18}")
                    || !usage.getReserved().matches("0|[1-9][0-9]{0,18}")
                    || !usage.getVersion().matches("[1-9][0-9]{0,18}"))
                  throw new IllegalArgumentException();
                Long.parseLong(usage.getUsed());
                Long.parseLong(usage.getReserved());
                Long.parseLong(usage.getVersion());
              } catch (RuntimeException e) {
                throw fail(DomainStatus.UNAVAILABLE, "INVALID_STORAGE_QUOTA");
              }
              return usage;
            });
  }

  private static QuotaUsage storageUsageWithTerms(QuotaUsage usage, Entitlement terms) {
    var limit =
        terms.getLimitsList().stream()
            .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_STORAGE_BYTES)
            .findFirst()
            .orElseThrow();
    var b = usage.toBuilder().clearLimit().setUnlimited(limit.getUnlimited());
    if (limit.hasLimit()) b.setLimit(limit.getLimit());
    return b.build();
  }

  private Mono<Void> observeStorage(DatabaseClient db, UUID tenant, long version) {
    return db.sql(
            "UPDATE taska.quota_counters SET storage_quota_version=:v,version=version+CASE WHEN"
                + " storage_quota_version<>:v THEN 1 ELSE 0 END WHERE tenant_id=:t AND"
                + " resource='SEAT' AND storage_quota_version<=:v AND version<9223372036854775807")
        .bind("t", tenant)
        .bind("v", version)
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 1
                    ? Mono.<Void>empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_QUOTA_STALE")));
  }

  private Mono<TenantTaskQuotaSummary> taskSummary(UUID tenant) {
    return tasks
        .current(tenant)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "TASK_QUOTA_UNAVAILABLE")))
        .map(
            summary -> {
              if (!tenant.toString().equals(summary.getTenantId()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              try {
                if (!summary.getMaximumCreated().matches("0|[1-9][0-9]{0,18}")
                    || !summary.getVersion().matches("[1-9][0-9]{0,18}")
                    || !summary.getEntitlementVersion().matches("[1-9][0-9]{0,18}"))
                  throw new IllegalArgumentException();
                Long.parseLong(summary.getMaximumCreated());
                Long.parseLong(summary.getVersion());
                Long.parseLong(summary.getEntitlementVersion());
              } catch (RuntimeException e) {
                throw fail(DomainStatus.UNAVAILABLE, "INVALID_TASK_QUOTA");
              }
              return summary;
            });
  }

  private Mono<Void> observeTasks(DatabaseClient db, UUID tenant, long version) {
    return db.sql(
            "UPDATE taska.quota_counters SET task_lifetime_version=:v,version=version+CASE WHEN"
                + " task_lifetime_version<>:v THEN 1 ELSE 0 END WHERE tenant_id=:t AND"
                + " resource='SEAT' AND task_lifetime_version<=:v AND version<9223372036854775807")
        .bind("t", tenant)
        .bind("v", version)
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 1
                    ? Mono.<Void>empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "TASK_QUOTA_STALE")));
  }

  private static QuotaUsage taskUsage(Entitlement terms, TenantTaskQuotaSummary summary) {
    var limit =
        terms.getLimitsList().stream()
            .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_TASK_LIFETIME)
            .findFirst()
            .orElseThrow();
    var b =
        QuotaUsage.newBuilder()
            .setKind(QuotaUsage.KindValue.KIND_TASK_LIFETIME)
            .setUsed(summary.getMaximumCreated())
            .setReserved("0")
            .setVersion(summary.getVersion())
            .setUnlimited(limit.getUnlimited());
    if (limit.hasLimit()) b.setLimit(limit.getLimit());
    return b.build();
  }

  /** Join an owning membership removal transaction, preserving actual usage and lock order. */
  Mono<Void> reconcileMemberships(DatabaseClient db, UUID tenant) {
    return lock(db, tenant).then(refresh(db, tenant)).then();
  }

  private Mono<Counter> refresh(DatabaseClient db, UUID tenant) {
    return Mono.defer(
        () ->
            db.sql(
                    "INSERT INTO taska.quota_counters(tenant_id,resource,scope_id)"
                        + " VALUES(:t,'SEAT',:t) ON CONFLICT DO NOTHING")
                .bind("t", tenant)
                .fetch()
                .rowsUpdated()
                .then(
                    db.sql(
                            "UPDATE taska.quota_reservations SET"
                                + " state='EXPIRED',released_at=:now,fence=fence+1,version=version+1"
                                + " WHERE tenant_id=:t AND resource='SEAT' AND state='ACTIVE' AND"
                                + " expires_at<=:now")
                        .bind("t", tenant)
                        .bind("now", offset(clock.instant()))
                        .fetch()
                        .rowsUpdated())
                .then(
                    db.sql(
                            """
                            WITH actual AS (
                              SELECT (SELECT count(*) FROM taska.tenant_memberships WHERE tenant_id=:t AND status IN ('ACTIVE','SUSPENDED')) used,
                                (SELECT coalesce(sum(amount),0)::bigint FROM taska.quota_reservations WHERE tenant_id=:t AND resource='SEAT' AND state='ACTIVE') reserved
                            ) UPDATE taska.quota_counters c SET used=a.used,reserved=a.reserved,reconciled_at=:now,
                              version=c.version+CASE WHEN c.used<>a.used OR c.reserved<>a.reserved THEN 1 ELSE 0 END
                              FROM actual a WHERE c.tenant_id=:t AND c.resource='SEAT'
                              RETURNING c.used,c.reserved,c.version,c.entitlement_version
                            """)
                        .bind("t", tenant)
                        .bind("now", offset(clock.instant()))
                        .map(
                            (r, m) ->
                                new Counter(
                                    r.get("used", Long.class),
                                    r.get("reserved", Long.class),
                                    r.get("version", Long.class),
                                    r.get("entitlement_version", Long.class)))
                        .one()));
  }

  private Mono<Void> reader(DatabaseClient db, UUID tenant, String actor) {
    return db.sql(
            "SELECT user_id FROM taska.tenant_memberships WHERE tenant_id=:t AND user_id=:actor AND"
                + " status='ACTIVE'")
        .bind("t", tenant)
        .bind("actor", UUID.fromString(actor))
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_REQUIRED")))
        .then();
  }

  private Mono<Void> lock(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT id FROM taska.tenants WHERE id=:t AND lifecycle<>'DELETED' FOR UPDATE")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .then();
  }

  private Mono<Void> observe(DatabaseClient db, UUID tenant, long version) {
    return db.sql(
            "UPDATE taska.quota_counters SET entitlement_version=:v,version=version+CASE WHEN"
                + " entitlement_version<>:v THEN 1 ELSE 0 END WHERE tenant_id=:t AND"
                + " resource='SEAT'")
        .bind("v", version)
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Reservation> reservation(DatabaseClient db, UUID tenant, UUID operation) {
    return db.sql(
            "SELECT * FROM taska.quota_reservations WHERE tenant_id=:t AND operation_id=:op AND"
                + " resource='SEAT'")
        .bind("t", tenant)
        .bind("op", operation)
        .map(
            (r, m) ->
                new Reservation(
                    r.get("id", UUID.class),
                    operation,
                    tenant,
                    r.get("resource_id", UUID.class),
                    r.get("state", String.class),
                    r.get("fence", Long.class),
                    r.get("version", Long.class),
                    r.get("expires_at", OffsetDateTime.class).toInstant()))
        .one();
  }

  private Mono<String> requestHash(DatabaseClient db, UUID tenant, UUID operation) {
    return db.sql(
            "SELECT request_hash FROM taska.quota_reservations WHERE tenant_id=:t AND"
                + " operation_id=:op AND resource='SEAT'")
        .bind("t", tenant)
        .bind("op", operation)
        .map((r, m) -> r.get("request_hash", String.class))
        .one();
  }

  private Mono<Reservation> required(
      DatabaseClient db, UUID tenant, UUID operation, UUID id, long fence) {
    return reservation(db, tenant, operation)
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND")))
        .flatMap(
            r ->
                r.id().equals(id) && r.fence() == fence
                    ? Mono.just(r)
                    : Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "STALE_FENCE")));
  }

  private interface Work<T> {
    Mono<T> apply(UUID tenant, Grant grant);
  }

  private <T> Mono<T> authorized(Command command, Work<T> work) {
    return VerifiedTenantContext.current()
        .flatMap(
            context -> {
              UUID tenant = TenantRegistryService.uuid(context.tenantId());
              if (!context.scopes().contains("write"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return authority
                  .authorize(context, command)
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "AUTHORITY_UNAVAILABLE")))
                  .flatMap(grant -> work.apply(tenant, grant));
            });
  }

  private Mono<Terms> terms(UUID tenant) {
    // Zero requests the primary current snapshot; the locked local entitlement_version fence
    // in reserve/get rejects older replies, including those racing another observed version.
    return billing
        .current(tenant, 0)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE")))
        .map(EntitlementProjection::validate)
        .map(
            snapshot -> {
              if (!tenant.toString().equals(snapshot.getTenantId()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              return new Terms(
                  Long.parseLong(snapshot.getVersion()),
                  snapshot.getLimitsList().stream()
                      .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_SEAT)
                      .findFirst()
                      .orElseThrow(),
                  snapshot);
            });
  }

  private void requireFresh(Entitlement e) {
    Instant now = clock.instant();
    if (!e.getRestrictionsList().isEmpty()
        || now.isBefore(Instant.parse(e.getValidFrom()))
        || (e.hasValidUntil() && !now.isBefore(Instant.parse(e.getValidUntil())))
        || (e.hasPaidThrough() && !now.isBefore(Instant.parse(e.getPaidThrough()))))
      throw fail(DomainStatus.FAILED_PRECONDITION, "ENTITLEMENT_RESTRICTED");
  }

  private static String hash(UUID tenant, UUID operation, UUID invitation, Instant expires) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (tenant + "|" + operation + "|" + invitation + "|" + expires)
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw fail(DomainStatus.RESOURCE_EXHAUSTED, "QUOTA_OVERFLOW");
    }
  }

  private static DomainException quota(long current, String limit) {
    return new DomainException(
        DomainStatus.RESOURCE_EXHAUSTED,
        "Seat quota exceeded",
        Map.of(
            "reason",
            "QUOTA_EXCEEDED",
            "resource",
            "SEAT",
            "current",
            Long.toString(current),
            "limit",
            limit));
  }

  private static DomainException invalid() {
    return fail(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
  }

  private static DomainException fail(DomainStatus status, String reason) {
    return new DomainException(status, "Seat quota operation failed", Map.of("reason", reason));
  }
}
