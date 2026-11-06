package ru.taska.tenant;

import static ru.taska.quota.StorageQuotaProtocol.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol.State;
import ru.taska.quota.StorageQuotaProtocol;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;

/**
 * Authoritative byte ledger. A committed effect is measured by the primary resource owner, never by
 * client actual_size or a dashboard; unknown deletion/cancellation retains usage.
 */
public final class StorageQuotaStore implements StorageQuotaProtocol.Authority, UsageSource {
  public enum Command {
    MUTATION_CONTEXT,
    RESERVE,
    REPLAY,
    LOOKUP,
    COMMIT,
    RELEASE,
    READ,
    RECONCILE,
    DELETE_USAGE
  }

  public record Grant(
      boolean bypassLimit, Duration maximumTtl, long maximumAttachmentBytes, long policyVersion) {
    public Grant {
      if (maximumTtl == null
          || maximumTtl.isNegative()
          || maximumTtl.isZero()
          || maximumAttachmentBytes <= 0
          || policyVersion <= 0) throw new IllegalArgumentException();
    }
  }

  @FunctionalInterface
  public interface Policy {
    Mono<Grant> current(VerifiedTenantContext context, Command command, Request target);
  }

  private record Counter(long used, long reserved, long version, long entitlement) {}

  private record Terms(Entitlement snapshot, QuotaLimit limit, long version) {}

  private final TenantTransactionExecutor tx;
  private final Policy policy;
  private final Owner owner;
  private final EntitlementProjection.Source billing;
  private final Clock clock;

  public StorageQuotaStore(
      TenantTransactionExecutor tx,
      Policy policy,
      Owner owner,
      EntitlementProjection.Source billing,
      Clock clock) {
    this.tx = Objects.requireNonNull(tx);
    this.policy = Objects.requireNonNull(policy);
    this.owner = Objects.requireNonNull(owner);
    this.billing = Objects.requireNonNull(billing);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Reservation> reserve(Request input) {
    var request =
        new Request(
            input.operation(),
            input.resource(),
            input.project(),
            input.issue(),
            input.bytes(),
            input.expiresAt().truncatedTo(ChronoUnit.MICROS));
    return context(true)
        .flatMap(
            c -> {
              if (c.actorId() == null)
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "UPLOAD_ACTOR_REQUIRED"));
              UUID tenant = UUID.fromString(c.tenantId());
              return authorize(c, Command.MUTATION_CONTEXT, request)
                  .then(
                      tx.execute(
                          db ->
                              lock(db, tenant)
                                  .then(
                                      find(db, tenant, request.operation())
                                          .flatMap(
                                              previous -> {
                                                if (!previous.request().equals(request))
                                                  return Mono.error(
                                                      fail(
                                                          DomainStatus.ALREADY_EXISTS,
                                                          "IDEMPOTENCY_CONFLICT"));
                                                return authorize(c, Command.REPLAY, request)
                                                    .thenReturn(previous);
                                              })
                                          .switchIfEmpty(
                                              Mono.defer(
                                                  () ->
                                                      authorize(c, Command.RESERVE, request)
                                                          .flatMap(
                                                              grant ->
                                                                  admit(
                                                                      db, c, tenant, request,
                                                                      grant)))))));
            });
  }

  private Mono<Reservation> admit(
      DatabaseClient db, VerifiedTenantContext c, UUID tenant, Request request, Grant grant) {
    if (!request.expiresAt().isAfter(clock.instant())
        || request.expiresAt().isAfter(clock.instant().plus(grant.maximumTtl()))
        || request.bytes() > grant.maximumAttachmentBytes())
      return Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "STORAGE_RESERVATION_BOUNDS"));
    return terms(tenant)
        .flatMap(
            terms -> {
              if (!grant.bypassLimit() && !terms.snapshot().getRestrictionsList().isEmpty())
                return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "READ_ONLY"));
              return refresh(db, tenant)
                  .flatMap(
                      counter -> {
                        if (terms.version() < counter.entitlement())
                          return Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"));
                        long total = add(add(counter.used(), counter.reserved()), request.bytes());
                        if (!grant.bypassLimit()
                            && !terms.limit().getUnlimited()
                            && total > Long.parseLong(terms.limit().getLimit()))
                          return Mono.error(
                              new DomainException(
                                  DomainStatus.FAILED_PRECONDITION,
                                  "QUOTA_EXCEEDED",
                                  Map.of(
                                      "quota.kind",
                                      "STORAGE_BYTES",
                                      "quota.used",
                                      Long.toString(counter.used()),
                                      "quota.reserved",
                                      Long.toString(counter.reserved()),
                                      "quota.limit",
                                      terms.limit().getLimit())));
                        var claims = RpcContextBridge.details(c).getContext();
                        var insert =
                            db.sql(
                                    """
                                    INSERT INTO taska.quota_reservations(id,tenant_id,resource,scope_id,operation_id,resource_owner,resource_id,request_hash,amount,state,entitlement_version,expires_at,created_at,storage_project_id,storage_issue_id,storage_policy_version,storage_max_attachment_bytes,storage_actor_id,storage_credential_kind,storage_grant_id,storage_bypassed_limit)
                                    VALUES(:id,:t,'STORAGE_BYTES',:t,:op,'issue-service',:resource,:hash,:amount,'ACTIVE',:ent,:expires,:now,:project,:issue,:policy,:maximum,:actor,:credential,:grant,:bypass)
                                    """)
                                .bind("id", UUID.randomUUID())
                                .bind("t", tenant)
                                .bind("op", request.operation())
                                .bind("resource", request.resource())
                                .bind("hash", hash(request))
                                .bind("amount", request.bytes())
                                .bind("ent", terms.version())
                                .bind("expires", offset(request.expiresAt()))
                                .bind("now", offset(clock.instant()))
                                .bind("project", request.project())
                                .bind("issue", request.issue())
                                .bind("policy", grant.policyVersion())
                                .bind("maximum", grant.maximumAttachmentBytes())
                                .bind("credential", claims.getCredentialKind().name())
                                .bind("bypass", grant.bypassLimit());
                        insert =
                            c.actorId() == null
                                ? insert.bindNull("actor", UUID.class)
                                : insert.bind("actor", UUID.fromString(c.actorId()));
                        insert =
                            claims.hasGrantId()
                                ? insert.bind("grant", UUID.fromString(claims.getGrantId()))
                                : insert.bindNull("grant", UUID.class);
                        return insert
                            .fetch()
                            .rowsUpdated()
                            .then(observe(db, tenant, terms.version()))
                            .then(refresh(db, tenant))
                            .then(find(db, tenant, request.operation()));
                      });
            });
  }

  @Override
  public Mono<Reservation> get(UUID operation) {
    return context(false)
        .flatMap(
            c ->
                tx.execute(
                    db ->
                        find(db, UUID.fromString(c.tenantId()), operation)
                            .switchIfEmpty(
                                Mono.error(fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND")))
                            .flatMap(
                                r -> authorize(c, Command.LOOKUP, r.request()).thenReturn(r))));
  }

  @Override
  public Mono<Reservation> commit(Reservation expected) {
    return context(true)
        .flatMap(
            c -> {
              scope(c, expected);
              return tx.execute(
                      db ->
                          locked(db, c, Command.COMMIT, expected.request())
                              .then(required(db, expected))
                              .flatMap(
                                  r -> {
                                    if (r.state() == State.COMMITTED) return Mono.just(r);
                                    if (r.state() != State.ACTIVE
                                        || r.cancelRequested()
                                        || !clock.instant().isBefore(r.expiresAt()))
                                      return Mono.error(
                                          fail(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "STORAGE_RESERVATION_CLOSED"));
                                    return remote(owner.inspect(r))
                                        .flatMap(
                                            receipt -> {
                                              matching(r, receipt);
                                              if (receipt.state() != OwnerState.MATERIALIZED
                                                  || receipt.actualBytes() == null
                                                  || receipt.actualBytes() > r.bytes())
                                                return Mono.error(
                                                    fail(
                                                        DomainStatus.FAILED_PRECONDITION,
                                                        "RESOURCE_NOT_MATERIALIZED"));
                                              return Mono.defer(
                                                  () -> {
                                                    if (!clock.instant().isBefore(r.expiresAt()))
                                                      return Mono.error(
                                                          fail(
                                                              DomainStatus.FAILED_PRECONDITION,
                                                              "RESERVATION_EXPIRED"));
                                                    return db.sql(
                                                            "UPDATE taska.quota_reservations SET"
                                                                + " state='COMMITTED',storage_actual_bytes=:actual,committed_at=:now,version=version+1"
                                                                + " WHERE tenant_id=:t AND"
                                                                + " operation_id=:op AND"
                                                                + " resource='STORAGE_BYTES' AND"
                                                                + " state='ACTIVE' AND"
                                                                + " storage_cancel_requested_at IS"
                                                                + " NULL AND expires_at>:now AND"
                                                                + " version<9223372036854775807")
                                                        .bind("actual", receipt.actualBytes())
                                                        .bind("now", offset(clock.instant()))
                                                        .bind("t", r.tenant())
                                                        .bind("op", r.operation())
                                                        .fetch()
                                                        .rowsUpdated()
                                                        .flatMap(
                                                            n ->
                                                                n == 1
                                                                    ? effect(
                                                                        db,
                                                                        r,
                                                                        receipt.actualBytes(),
                                                                        "COMMIT")
                                                                    : Mono.error(
                                                                        fail(
                                                                            DomainStatus
                                                                                .FAILED_PRECONDITION,
                                                                            "RESERVATION_CHANGED_OR_EXPIRED")))
                                                        .then(refresh(db, r.tenant()))
                                                        .then(find(db, r.tenant(), r.operation()));
                                                  });
                                            });
                                  }))
                  .flatMap(this::activate);
            });
  }

  private Mono<Reservation> activate(Reservation r) {
    return remote(owner.activate(r))
        .flatMap(
            receipt -> {
              matching(r, receipt);
              if ((receipt.state() != OwnerState.CONFIRMED && receipt.state() != OwnerState.DELETED)
                  || !Objects.equals(r.actualBytes(), receipt.actualBytes()))
                return Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_ACTIVATION_PENDING"));
              return tx.execute(
                  db ->
                      db.sql(
                              "UPDATE taska.quota_reservations SET storage_activated_at=:now WHERE"
                                  + " tenant_id=:t AND operation_id=:op AND"
                                  + " resource='STORAGE_BYTES' AND state='COMMITTED' AND"
                                  + " storage_activated_at IS NULL")
                          .bind("t", r.tenant())
                          .bind("op", r.operation())
                          .bind("now", offset(clock.instant()))
                          .fetch()
                          .rowsUpdated()
                          .thenReturn(r));
            });
  }

  @Override
  public Mono<Reservation> release(Reservation expected) {
    return context(true)
        .flatMap(
            c -> {
              scope(c, expected);
              return tx.execute(
                      db ->
                          locked(db, c, Command.RELEASE, expected.request())
                              .then(required(db, expected))
                              .flatMap(r -> intent(db, r)))
                  .flatMap(r -> r.state() == State.ACTIVE ? cancel(c, r) : Mono.just(r));
            });
  }

  private Mono<Reservation> intent(DatabaseClient db, Reservation r) {
    if (r.state() == State.RELEASED || r.state() == State.EXPIRED || r.cancelRequested())
      return Mono.just(r);
    if (r.state() != State.ACTIVE)
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_COMMITTED"));
    return db.sql(
            "UPDATE taska.quota_reservations SET storage_cancel_requested_at=:now,version=version+1"
                + " WHERE tenant_id=:t AND operation_id=:op AND resource='STORAGE_BYTES' AND"
                + " state='ACTIVE' AND storage_cancel_requested_at IS NULL AND"
                + " version<9223372036854775807")
        .bind("now", offset(clock.instant()))
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 1
                    ? find(db, r.tenant(), r.operation())
                    : Mono.error(
                        fail(DomainStatus.RESOURCE_EXHAUSTED, "CANCEL_INTENT_UNAVAILABLE")));
  }

  private Mono<Reservation> cancel(VerifiedTenantContext c, Reservation expected) {
    return tx.execute(
        db ->
            locked(db, c, Command.RELEASE, expected.request())
                .then(required(db, expected))
                .flatMap(
                    r -> {
                      if (r.state() == State.RELEASED || r.state() == State.EXPIRED)
                        return Mono.just(r);
                      if (r.state() != State.ACTIVE || !r.cancelRequested())
                        return Mono.error(
                            fail(DomainStatus.FAILED_PRECONDITION, "CANCEL_NOT_REQUESTED"));
                      return remote(owner.cancel(r))
                          .flatMap(
                              receipt -> {
                                matching(r, receipt);
                                if (receipt.state() != OwnerState.CANCELLED)
                                  return Mono.error(
                                      fail(DomainStatus.UNAVAILABLE, "OBJECT_CLEANUP_PENDING"));
                                return db.sql(
                                        "UPDATE taska.quota_reservations SET"
                                            + " state=:state,released_at=:now,version=version+1"
                                            + " WHERE tenant_id=:t AND operation_id=:op AND"
                                            + " resource='STORAGE_BYTES' AND state='ACTIVE' AND"
                                            + " storage_cancel_requested_at IS NOT NULL AND"
                                            + " version<9223372036854775807")
                                    .bind(
                                        "state",
                                        clock.instant().isBefore(r.expiresAt())
                                            ? "RELEASED"
                                            : "EXPIRED")
                                    .bind("now", offset(clock.instant()))
                                    .bind("t", r.tenant())
                                    .bind("op", r.operation())
                                    .fetch()
                                    .rowsUpdated()
                                    .flatMap(
                                        n ->
                                            n == 1
                                                ? refresh(db, r.tenant())
                                                    .then(find(db, r.tenant(), r.operation()))
                                                : Mono.error(
                                                    fail(
                                                        DomainStatus.ABORTED,
                                                        "RESERVATION_CHANGED")));
                              });
                    }));
  }

  public Mono<Void> releaseUsage(Reservation expected) {
    return context(true)
        .flatMap(
            c -> {
              scope(c, expected);
              return tx.execute(
                  db ->
                      locked(db, c, Command.DELETE_USAGE, expected.request())
                          .then(required(db, expected))
                          .flatMap(
                              r -> {
                                if (r.state() != State.COMMITTED)
                                  return Mono.error(
                                      fail(
                                          DomainStatus.FAILED_PRECONDITION,
                                          "RESERVATION_NOT_COMMITTED"));
                                return remote(owner.inspect(r))
                                    .flatMap(
                                        receipt -> {
                                          matching(r, receipt);
                                          if (receipt.state() != OwnerState.DELETED
                                              || !Objects.equals(
                                                  r.actualBytes(), receipt.actualBytes()))
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "CONFIRMED_OBJECT_DELETION_REQUIRED"));
                                          return effect(db, r, r.actualBytes(), "RELEASE_USAGE")
                                              .then(refresh(db, r.tenant()))
                                              .then();
                                        });
                              }));
            });
  }

  public Mono<List<Reservation>> reconcile() {
    return context(true)
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return authorize(c, Command.RECONCILE, null)
                  .then(
                      tx.execute(
                          db ->
                              lock(db, tenant)
                                  .then(
                                      db.sql(
                                              "SELECT * FROM taska.quota_reservations WHERE"
                                                  + " tenant_id=:t AND resource='STORAGE_BYTES' AND"
                                                  + " ((state='ACTIVE' AND (expires_at<=:now OR"
                                                  + " storage_cancel_requested_at IS NOT NULL)) OR"
                                                  + " (state='COMMITTED' AND storage_activated_at"
                                                  + " IS NULL)) ORDER BY created_at,id LIMIT 100")
                                          .bind("t", tenant)
                                          .bind("now", offset(clock.instant()))
                                          .map((r, m) -> reservation(r))
                                          .all()
                                          .collectList())
                                  .flatMap(
                                      rows ->
                                          Flux.fromIterable(rows)
                                              .concatMap(
                                                  r ->
                                                      r.state() == State.ACTIVE
                                                          ? intent(db, r)
                                                          : Mono.just(r))
                                              .collectList())))
                  .flatMap(
                      rows ->
                          Flux.fromIterable(rows)
                              .concatMap(
                                  r -> r.state() == State.ACTIVE ? cancel(c, r) : activate(r))
                              .collectList());
            });
  }

  /**
   * One authoritative manifest page per call. Cursor/version belong to the caller's scoped worker.
   * Reconstruct effects only for matching durable COMMITTED root admission; never invent a slot.
   */
  public Mono<ManifestPage> reconcileManifests(UUID after, long version) {
    return context(true)
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return authorize(c, Command.RECONCILE, null)
                  .then(remote(owner.manifests(tenant, after, version)))
                  .flatMap(
                      page -> {
                        if (!tenant.equals(page.tenant()))
                          return Mono.error(
                              fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
                        return tx.execute(
                            db ->
                                lock(db, tenant)
                                    .then(
                                        Flux.fromIterable(page.items())
                                            .concatMap(
                                                receipt ->
                                                    find(
                                                            db,
                                                            tenant,
                                                            receipt.reservation().operation())
                                                        .switchIfEmpty(
                                                            Mono.error(
                                                                fail(
                                                                    DomainStatus.DATA_LOSS,
                                                                    "UNADMITTED_MANIFEST")))
                                                        .flatMap(
                                                            r -> {
                                                              matching(r, receipt);
                                                              if (receipt.state()
                                                                      == OwnerState.CONFIRMED
                                                                  || receipt.state()
                                                                      == OwnerState.DELETED) {
                                                                if (r.state() != State.COMMITTED
                                                                    || !Objects.equals(
                                                                        r.actualBytes(),
                                                                        receipt.actualBytes()))
                                                                  return Mono.error(
                                                                      fail(
                                                                          DomainStatus.DATA_LOSS,
                                                                          "MANIFEST_ADMISSION_MISMATCH"));
                                                                return effect(
                                                                        db,
                                                                        r,
                                                                        r.actualBytes(),
                                                                        "COMMIT")
                                                                    .then(
                                                                        receipt.state()
                                                                                == OwnerState
                                                                                    .DELETED
                                                                            ? effect(
                                                                                db,
                                                                                r,
                                                                                r.actualBytes(),
                                                                                "RELEASE_USAGE")
                                                                            : Mono.empty());
                                                              }
                                                              return Mono.empty();
                                                            }))
                                            .then())
                                    .then(refresh(db, tenant))
                                    .thenReturn(page));
                      });
            });
  }

  @Override
  public Mono<QuotaUsage> current(UUID tenant) {
    return context(false)
        .flatMap(
            c -> {
              if (!tenant.toString().equals(c.tenantId()) || !c.scopes().contains("read"))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return authorize(c, Command.READ, null)
                  .then(terms(tenant))
                  .flatMap(
                      terms ->
                          tx.execute(
                              db ->
                                  lock(db, tenant)
                                      .then(refresh(db, tenant))
                                      .flatMap(
                                          counter -> {
                                            if (terms.version() < counter.entitlement())
                                              return Mono.error(
                                                  fail(
                                                      DomainStatus.UNAVAILABLE,
                                                      "ENTITLEMENT_STALE"));
                                            return observe(db, tenant, terms.version())
                                                .then(refresh(db, tenant));
                                          })
                                      .map(
                                          counter -> {
                                            var b =
                                                QuotaUsage.newBuilder()
                                                    .setKind(
                                                        QuotaUsage.KindValue.KIND_STORAGE_BYTES)
                                                    .setUsed(Long.toString(counter.used()))
                                                    .setReserved(Long.toString(counter.reserved()))
                                                    .setVersion(Long.toString(counter.version()))
                                                    .setUnlimited(terms.limit().getUnlimited());
                                            if (terms.limit().hasLimit())
                                              b.setLimit(terms.limit().getLimit());
                                            return b.build();
                                          })));
            });
  }

  /** Independent storage cause for the lifecycle aggregator; never replaces other restrictions. */
  public Mono<Set<RestrictionCode>> restrictions(UUID tenant) {
    return current(tenant)
        .map(
            usage ->
                !usage.getUnlimited()
                        && add(Long.parseLong(usage.getUsed()), Long.parseLong(usage.getReserved()))
                            > Long.parseLong(usage.getLimit())
                    ? Set.of(RestrictionCode.RESTRICTION_CODE_OVER_QUOTA)
                    : Set.of());
  }

  private Mono<Counter> refresh(DatabaseClient db, UUID tenant) {
    return db.sql(
            "INSERT INTO taska.quota_counters(tenant_id,resource,scope_id)"
                + " VALUES(:t,'STORAGE_BYTES',:t) ON CONFLICT DO NOTHING")
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    """
                    SELECT COALESCE((SELECT sum(CASE WHEN effect_kind='COMMIT' THEN amount ELSE -amount END) FROM taska.quota_effects WHERE tenant_id=:t AND resource='STORAGE_BYTES'),0) used,
                    COALESCE((SELECT sum(amount) FROM taska.quota_reservations WHERE tenant_id=:t AND resource='STORAGE_BYTES' AND state='ACTIVE'),0) reserved
                    """)
                .bind("t", tenant)
                .map(
                    (r, m) ->
                        new long[] {
                          exact(r.get("used", BigDecimal.class)),
                          exact(r.get("reserved", BigDecimal.class))
                        })
                .one())
        .flatMap(
            actual -> {
              add(actual[0], actual[1]);
              return db.sql(
                      "UPDATE taska.quota_counters SET"
                          + " used=:used,reserved=:reserved,reconciled_at=:now,version=version+CASE"
                          + " WHEN used<>:used OR reserved<>:reserved THEN 1 ELSE 0 END WHERE"
                          + " tenant_id=:t AND resource='STORAGE_BYTES' AND"
                          + " version<9223372036854775807 RETURNING"
                          + " used,reserved,version,entitlement_version")
                  .bind("used", actual[0])
                  .bind("reserved", actual[1])
                  .bind("now", offset(clock.instant()))
                  .bind("t", tenant)
                  .map(
                      (r, m) ->
                          new Counter(
                              r.get("used", Long.class),
                              r.get("reserved", Long.class),
                              r.get("version", Long.class),
                              r.get("entitlement_version", Long.class)))
                  .one()
                  .switchIfEmpty(
                      Mono.error(
                          fail(
                              DomainStatus.RESOURCE_EXHAUSTED, "STORAGE_QUOTA_VERSION_EXHAUSTED")));
            });
  }

  private Mono<Void> effect(DatabaseClient db, Reservation r, long actual, String kind) {
    if (actual <= 0 || actual > r.bytes())
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "INVALID_ACTUAL_BYTES"));
    return db.sql(
            "INSERT INTO"
                + " taska.quota_effects(tenant_id,operation_id,resource,scope_id,effect_kind,amount,resource_id,owner_version,applied_at)"
                + " VALUES(:t,:op,'STORAGE_BYTES',:t,:kind,:amount,:resource,:version,:now) ON"
                + " CONFLICT DO NOTHING")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .bind("kind", kind)
        .bind("amount", actual)
        .bind("resource", r.resource())
        .bind("version", r.version())
        .bind("now", offset(clock.instant()))
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "SELECT amount,resource_id,scope_id FROM taska.quota_effects WHERE tenant_id=:t"
                        + " AND operation_id=:op AND resource='STORAGE_BYTES' AND"
                        + " effect_kind=:kind")
                .bind("t", r.tenant())
                .bind("op", r.operation())
                .bind("kind", kind)
                .fetch()
                .one()
                .switchIfEmpty(Mono.error(fail(DomainStatus.DATA_LOSS, "MISSING_STORAGE_EFFECT")))
                .flatMap(
                    row ->
                        Long.valueOf(actual).equals(row.get("amount"))
                                && r.resource().equals(row.get("resource_id"))
                                && r.tenant().equals(row.get("scope_id"))
                            ? Mono.<Void>empty()
                            : Mono.error(fail(DomainStatus.DATA_LOSS, "STORAGE_EFFECT_CONFLICT"))));
  }

  private Mono<Void> observe(DatabaseClient db, UUID tenant, long version) {
    return db.sql(
            "UPDATE taska.quota_counters SET entitlement_version=:v,version=version+CASE WHEN"
                + " entitlement_version<>:v THEN 1 ELSE 0 END WHERE tenant_id=:t AND"
                + " resource='STORAGE_BYTES' AND entitlement_version<=:v AND"
                + " version<9223372036854775807")
        .bind("v", version)
        .bind("t", tenant)
        .fetch()
        .rowsUpdated()
        .flatMap(
            n ->
                n == 1
                    ? Mono.<Void>empty()
                    : Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE")));
  }

  private Mono<Void> lock(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT id FROM taska.tenants WHERE id=:t AND lifecycle<>'DELETED' FOR UPDATE")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .then();
  }

  private Mono<Void> locked(
      DatabaseClient db, VerifiedTenantContext c, Command command, Request request) {
    return lock(db, UUID.fromString(c.tenantId())).then(authorize(c, command, request)).then();
  }

  private Mono<Reservation> find(DatabaseClient db, UUID tenant, UUID operation) {
    return db.sql(
            "SELECT * FROM taska.quota_reservations WHERE tenant_id=:t AND resource='STORAGE_BYTES'"
                + " AND operation_id=:op")
        .bind("t", tenant)
        .bind("op", operation)
        .map((r, m) -> reservation(r))
        .one();
  }

  private static Reservation reservation(io.r2dbc.spi.Row r) {
    return new Reservation(
        r.get("tenant_id", UUID.class),
        new Request(
            r.get("operation_id", UUID.class),
            r.get("resource_id", UUID.class),
            r.get("storage_project_id", UUID.class),
            r.get("storage_issue_id", UUID.class),
            r.get("amount", Long.class),
            r.get("expires_at", OffsetDateTime.class).toInstant()),
        r.get("id", UUID.class),
        r.get("fence", Long.class),
        State.valueOf(r.get("state", String.class)),
        r.get("version", Long.class),
        r.get("storage_actual_bytes", Long.class),
        r.get("storage_cancel_requested_at", OffsetDateTime.class) != null,
        r.get("storage_max_attachment_bytes", Long.class),
        r.get("storage_policy_version", Long.class),
        r.get("storage_actor_id", UUID.class));
  }

  private Mono<Reservation> required(DatabaseClient db, Reservation expected) {
    return find(db, expected.tenant(), expected.operation())
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND")))
        .map(
            r -> {
              if (!r.sameIdentity(expected))
                throw fail(DomainStatus.FAILED_PRECONDITION, "STALE_RESERVATION");
              return r;
            });
  }

  private Mono<VerifiedTenantContext> context(boolean write) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              if (write && !c.scopes().contains("write")
                  || !write && Collections.disjoint(c.scopes(), Set.of("read", "write")))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return Mono.just(c);
            });
  }

  private Mono<Grant> authorize(VerifiedTenantContext c, Command command, Request request) {
    return policy
        .current(c, command, request)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_POLICY_UNAVAILABLE")));
  }

  private <T> Mono<T> remote(Mono<T> result) {
    return result
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "STORAGE_OWNER_UNAVAILABLE")));
  }

  private Mono<Terms> terms(UUID tenant) {
    return remote(billing.current(tenant, 0))
        .map(EntitlementProjection::validate)
        .map(
            e -> {
              if (!tenant.toString().equals(e.getTenantId()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              Instant now = clock.instant();
              if (now.isBefore(Instant.parse(e.getValidFrom()))
                  || (e.hasValidUntil() && !now.isBefore(Instant.parse(e.getValidUntil())))
                  || (e.hasPaidThrough() && !now.isBefore(Instant.parse(e.getPaidThrough()))))
                throw fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_EXPIRED");
              return new Terms(
                  e,
                  e.getLimitsList().stream()
                      .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_STORAGE_BYTES)
                      .findFirst()
                      .orElseThrow(),
                  Long.parseLong(e.getVersion()));
            });
  }

  private static void scope(VerifiedTenantContext c, Reservation r) {
    if (!r.tenant().toString().equals(c.tenantId()))
      throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
  }

  private static void matching(Reservation r, Receipt receipt) {
    if (!r.sameIdentity(receipt.reservation()))
      throw fail(DomainStatus.PERMISSION_DENIED, "OWNER_RECEIPT_MISMATCH");
  }

  private static long exact(BigDecimal value) {
    try {
      long n = value.longValueExact();
      if (n < 0) throw new ArithmeticException();
      return n;
    } catch (ArithmeticException e) {
      throw fail(DomainStatus.DATA_LOSS, "STORAGE_LEDGER_OVERFLOW");
    }
  }

  private static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw fail(DomainStatus.RESOURCE_EXHAUSTED, "STORAGE_BYTES_EXHAUSTED");
    }
  }

  private static OffsetDateTime offset(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static String hash(Request r) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static DomainException fail(DomainStatus status, String message) {
    return new DomainException(status, message);
  }
}
