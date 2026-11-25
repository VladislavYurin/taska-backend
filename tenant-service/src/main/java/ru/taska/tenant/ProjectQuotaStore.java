package ru.taska.tenant;

import static ru.taska.quota.ProjectQuotaProtocol.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Root authority. Company lock serializes admission, finalization and owner-confirmed release.
 * Expiry alone never frees a remote reservation: owner cancellation must durably fence late work.
 */
public final class ProjectQuotaStore implements ProjectQuotaProtocol.Authority, UsageSource {
  public enum Command {
    LOOKUP,
    MUTATION_CONTEXT,
    RESERVE_NEW,
    REPLAY,
    COMMIT,
    RELEASE,
    RECONCILE,
    READ,
    RELEASE_USAGE
  }

  public record Grant(boolean bypassLimit, long policyVersion, Duration maximumTtl) {
    public Grant {
      if (policyVersion <= 0
          || maximumTtl == null
          || maximumTtl.isZero()
          || maximumTtl.isNegative()) throw new IllegalArgumentException();
    }
  }

  @FunctionalInterface
  public interface Policy {
    Mono<Grant> current(VerifiedTenantContext context, Command command, UUID operation);
  }

  private record Terms(Entitlement snapshot, QuotaLimit limit, long version) {}

  private record Counter(long used, long reserved, long version, long entitlement) {}

  private final TenantTransactionExecutor tx;
  private final Policy policy;
  private final Owner owner;
  private final EntitlementProjection.Source billing;
  private final Clock clock;

  public ProjectQuotaStore(
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
  public Mono<Reservation> reserve(UUID operation, UUID resource, Instant expiry) {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(resource);
    Objects.requireNonNull(expiry);
    Instant expires = expiry.truncatedTo(ChronoUnit.MICROS);
    return context(Command.MUTATION_CONTEXT, operation)
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return tx.execute(
                  db ->
                      lock(db, tenant)
                          .then(
                              find(db, tenant, operation)
                                  .flatMap(
                                      previous -> {
                                        if (!previous.resource().equals(resource)
                                            || !previous.expiresAt().equals(expires))
                                          return Mono.error(
                                              fail(
                                                  DomainStatus.ALREADY_EXISTS,
                                                  "IDEMPOTENCY_CONFLICT"));
                                        return authorize(c, Command.REPLAY, operation)
                                            .thenReturn(previous);
                                      })
                                  .switchIfEmpty(
                                      Mono.defer(
                                          () ->
                                              admit(
                                                  db, c, tenant, operation, resource, expires)))));
            });
  }

  private Mono<Reservation> admit(
      DatabaseClient db,
      VerifiedTenantContext context,
      UUID tenant,
      UUID operation,
      UUID resource,
      Instant expires) {
    return authorize(context, Command.RESERVE_NEW, operation)
        .flatMap(
            grant ->
                terms(tenant)
                    .flatMap(
                        terms -> {
                          if (!expires.isAfter(clock.instant())
                              || expires.isAfter(clock.instant().plus(grant.maximumTtl())))
                            return Mono.error(
                                fail(
                                    DomainStatus.FAILED_PRECONDITION,
                                    "RESERVATION_EXPIRED_OR_TTL_INVALID"));
                          if (!grant.bypassLimit()) writable(terms.snapshot());
                          return refresh(db, tenant)
                              .flatMap(
                                  counter -> {
                                    if (terms.version() < counter.entitlement())
                                      return Mono.error(
                                          fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE"));
                                    if (!grant.bypassLimit()
                                        && !terms.limit().getUnlimited()
                                        && counter.used()
                                            > Long.parseLong(terms.limit().getLimit())
                                                - counter.reserved()
                                                - 1)
                                      return Mono.error(
                                          new DomainException(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "QUOTA_EXCEEDED",
                                              Map.of(
                                                  "code",
                                                  "QUOTA_EXCEEDED",
                                                  "quota.kind",
                                                  "PROJECT",
                                                  "quota.used",
                                                  Long.toString(counter.used()),
                                                  "quota.limit",
                                                  terms.limit().getLimit())));
                                    var claims =
                                        ru.taska.security.tenant.RpcContextBridge.details(context)
                                            .getContext();
                                    var insert =
                                        db.sql(
                                                """
                                                INSERT INTO taska.quota_reservations(id,tenant_id,resource,scope_id,operation_id,resource_owner,resource_id,request_hash,amount,state,entitlement_version,expires_at,created_at,project_policy_version,project_actor_id,project_credential_kind,project_grant_id,project_bypassed_limit)
                                                VALUES(:id,:t,'PROJECT',:t,:op,'project-service',:resource,:hash,1,'ACTIVE',:ent,:expires,:now,:policy,:actor,:credential,:grant,:bypass)
                                                """)
                                            .bind("id", UUID.randomUUID())
                                            .bind("t", tenant)
                                            .bind("op", operation)
                                            .bind("resource", resource)
                                            .bind("hash", hash(operation, resource, expires))
                                            .bind("ent", terms.version())
                                            .bind("expires", offset(expires))
                                            .bind("now", offset(clock.instant()))
                                            .bind("policy", grant.policyVersion())
                                            .bind("credential", claims.getCredentialKind().name())
                                            .bind("bypass", grant.bypassLimit());
                                    insert =
                                        context.actorId() == null
                                            ? insert.bindNull("actor", UUID.class)
                                            : insert.bind(
                                                "actor", UUID.fromString(context.actorId()));
                                    insert =
                                        claims.hasGrantId()
                                            ? insert.bind(
                                                "grant", UUID.fromString(claims.getGrantId()))
                                            : insert.bindNull("grant", UUID.class);
                                    return insert
                                        .fetch()
                                        .rowsUpdated()
                                        .then(
                                            db.sql(
                                                    "UPDATE taska.quota_counters SET"
                                                        + " entitlement_version=:ent WHERE"
                                                        + " tenant_id=:t AND resource='PROJECT'")
                                                .bind("t", tenant)
                                                .bind("ent", terms.version())
                                                .fetch()
                                                .rowsUpdated())
                                        .then(refresh(db, tenant))
                                        .then(find(db, tenant, operation));
                                  });
                        }));
  }

  @Override
  public Mono<Reservation> get(UUID operation) {
    return context(Command.LOOKUP, operation)
        .flatMap(
            c ->
                tx.execute(
                    db ->
                        find(db, UUID.fromString(c.tenantId()), operation)
                            .switchIfEmpty(
                                Mono.error(
                                    fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND")))));
  }

  @Override
  public Mono<Reservation> commit(Reservation expected) {
    return context(Command.COMMIT, expected.operation())
        .flatMap(
            c -> {
              scope(c, expected);
              UUID tenant = expected.tenant();
              return tx.execute(
                      db ->
                          locked(db, tenant, c, Command.COMMIT, expected.operation())
                              .then(required(db, expected))
                              .flatMap(
                                  r -> {
                                    if (r.state() == State.COMMITTED) return Mono.just(r);
                                    if (r.cancelRequested())
                                      return Mono.error(
                                          fail(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "RESERVATION_CANCELLING"));
                                    if (r.state() != State.ACTIVE
                                        || !clock.instant().isBefore(r.expiresAt()))
                                      return Mono.error(
                                          fail(
                                              DomainStatus.FAILED_PRECONDITION,
                                              "RESERVATION_EXPIRED_OR_CLOSED"));
                                    return remote(owner.inspect(r))
                                        .switchIfEmpty(
                                            Mono.error(
                                                fail(
                                                    DomainStatus.UNAVAILABLE,
                                                    "OWNER_OUTCOME_UNKNOWN")))
                                        .flatMap(
                                            receipt -> {
                                              matching(r, receipt);
                                              if (receipt.state() != OwnerState.PROVISIONING)
                                                return Mono.error(
                                                    fail(
                                                        DomainStatus.FAILED_PRECONDITION,
                                                        "OWNER_NOT_PROVISIONING"));
                                              return Mono.defer(
                                                  () -> {
                                                    if (!clock.instant().isBefore(r.expiresAt()))
                                                      return Mono.error(
                                                          fail(
                                                              DomainStatus.FAILED_PRECONDITION,
                                                              "RESERVATION_EXPIRED"));
                                                    return db.sql(
                                                            "UPDATE taska.quota_reservations SET"
                                                                + " state='COMMITTED',committed_at=:now,version=version+1"
                                                                + " WHERE tenant_id=:t AND"
                                                                + " operation_id=:op AND"
                                                                + " resource='PROJECT' AND"
                                                                + " state='ACTIVE' AND fence=:f AND"
                                                                + " expires_at>:now AND"
                                                                + " version<9223372036854775807")
                                                        .bind("now", offset(clock.instant()))
                                                        .bind("t", tenant)
                                                        .bind("op", r.operation())
                                                        .bind("f", r.fence())
                                                        .fetch()
                                                        .rowsUpdated()
                                                        .flatMap(
                                                            n ->
                                                                n == 1
                                                                    ? effect(db, r, "COMMIT")
                                                                    : Mono.error(
                                                                        fail(
                                                                            clock
                                                                                    .instant()
                                                                                    .isBefore(
                                                                                        r
                                                                                            .expiresAt())
                                                                                ? DomainStatus
                                                                                    .ABORTED
                                                                                : DomainStatus
                                                                                    .FAILED_PRECONDITION,
                                                                            clock
                                                                                    .instant()
                                                                                    .isBefore(
                                                                                        r
                                                                                            .expiresAt())
                                                                                ? "RESERVATION_CHANGED"
                                                                                : "RESERVATION_EXPIRED")))
                                                        .then(refresh(db, tenant, r))
                                                        .then(find(db, tenant, r.operation()));
                                                  });
                                            });
                                  }))
                  .flatMap(this::complete);
            });
  }

  @Override
  public Mono<Reservation> release(Reservation expected) {
    return context(Command.RELEASE, expected.operation())
        .flatMap(
            c -> {
              scope(c, expected);
              // Commit cancellation intent before any remote mutation can outlive this request.
              return tx.execute(
                      db ->
                          locked(db, expected.tenant(), c, Command.RELEASE, expected.operation())
                              .then(required(db, expected))
                              .flatMap(r -> cancelIntent(db, r)))
                  .flatMap(
                      r ->
                          r.state() == State.ACTIVE
                              ? finishCancellation(c, r, Command.RELEASE)
                              : Mono.just(r));
            });
  }

  private Mono<Reservation> cancelIntent(DatabaseClient db, Reservation r) {
    if (r.state() == State.RELEASED || r.state() == State.EXPIRED || r.cancelRequested())
      return Mono.just(r);
    if (r.state() != State.ACTIVE)
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_COMMITTED"));
    return db.sql(
            "UPDATE taska.quota_reservations SET project_cancel_requested_at=:now,version=version+1"
                + " WHERE tenant_id=:t AND operation_id=:op AND resource='PROJECT' AND"
                + " state='ACTIVE' AND project_cancel_requested_at IS NULL AND"
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

  private Mono<Reservation> finishCancellation(
      VerifiedTenantContext context, Reservation r, Command command) {
    return tx.execute(
        db ->
            locked(db, r.tenant(), context, command, r.operation())
                .then(required(db, r))
                .flatMap(
                    current -> {
                      if (current.state() == State.RELEASED || current.state() == State.EXPIRED)
                        return Mono.just(current);
                      if (!current.cancelRequested())
                        return Mono.error(
                            fail(DomainStatus.FAILED_PRECONDITION, "CANCEL_NOT_REQUESTED"));
                      return releaseLocked(db, current);
                    }));
  }

  /** A worker must install a fresh scoped service context; there is no unscoped tenant scanner. */
  public Mono<List<Reservation>> reconcile() {
    return context(Command.RECONCILE, null)
        .flatMap(
            c -> {
              UUID tenant = UUID.fromString(c.tenantId());
              return tx.execute(
                      db ->
                          locked(db, tenant, c, Command.RECONCILE, null)
                              .then(
                                  db.sql(
                                          "SELECT * FROM taska.quota_reservations WHERE"
                                              + " tenant_id=:t AND resource='PROJECT' AND"
                                              + " ((state='ACTIVE' AND (expires_at<=:now OR"
                                              + " project_cancel_requested_at IS NOT NULL)) OR"
                                              + " (state='COMMITTED' AND project_opened_at IS"
                                              + " NULL)) ORDER BY created_at,id LIMIT 100")
                                      .bind("t", tenant)
                                      .bind("now", offset(clock.instant()))
                                      .map((row, m) -> reservation(row))
                                      .all()
                                      .collectList())
                              .flatMap(
                                  rows ->
                                      Flux.fromIterable(rows)
                                          .concatMap(
                                              r ->
                                                  r.state() == State.ACTIVE
                                                      ? cancelIntent(db, r)
                                                      : Mono.just(r))
                                          .collectList()))
                  // Each cancellation intent is durable before owner.cancel; opening also runs
                  // after commit.
                  .flatMap(
                      rows ->
                          Flux.fromIterable(rows)
                              .concatMap(
                                  r ->
                                      r.state() == State.COMMITTED
                                          ? complete(r)
                                          : finishCancellation(c, r, Command.RECONCILE))
                              .collectList());
            });
  }

  private Mono<Reservation> complete(Reservation committed) {
    return remote(owner.open(committed))
        .flatMap(
            receipt -> {
              matching(committed, receipt);
              if (receipt.state() != OwnerState.OPEN && receipt.state() != OwnerState.DELETED)
                return Mono.error(fail(DomainStatus.UNAVAILABLE, "PROJECT_OPEN_PENDING"));
              return tx.execute(
                  db ->
                      db.sql(
                              "UPDATE taska.quota_reservations SET project_opened_at=:now WHERE"
                                  + " tenant_id=:t AND operation_id=:op AND resource='PROJECT' AND"
                                  + " state='COMMITTED' AND fence=:f AND project_opened_at IS NULL")
                          .bind("now", offset(clock.instant()))
                          .bind("t", committed.tenant())
                          .bind("op", committed.operation())
                          .bind("f", committed.fence())
                          .fetch()
                          .rowsUpdated()
                          .thenReturn(committed));
            });
  }

  private Mono<Reservation> releaseLocked(DatabaseClient db, Reservation r) {
    if (r.state() == State.RELEASED || r.state() == State.EXPIRED) return Mono.just(r);
    if (r.state() != State.ACTIVE)
      return Mono.error(fail(DomainStatus.FAILED_PRECONDITION, "RESERVATION_COMMITTED"));
    return remote(owner.cancel(r))
        .flatMap(
            receipt -> {
              matching(r, receipt);
              if (receipt.state() != OwnerState.CANCELLED)
                return Mono.error(fail(DomainStatus.UNAVAILABLE, "OWNER_CANCELLATION_PENDING"));
              return db.sql(
                      "UPDATE taska.quota_reservations SET"
                          + " state=:state,released_at=:now,version=version+1 WHERE tenant_id=:t"
                          + " AND operation_id=:op AND resource='PROJECT' AND state='ACTIVE' AND"
                          + " version<9223372036854775807")
                  .bind("state", clock.instant().isBefore(r.expiresAt()) ? "RELEASED" : "EXPIRED")
                  .bind("now", offset(clock.instant()))
                  .bind("t", r.tenant())
                  .bind("op", r.operation())
                  .fetch()
                  .rowsUpdated()
                  .flatMap(
                      n ->
                          n == 1
                              ? Mono.<Void>empty()
                              : Mono.error(fail(DomainStatus.ABORTED, "RESERVATION_CHANGED")))
                  .then(refresh(db, r.tenant(), r))
                  .then(find(db, r.tenant(), r.operation()));
            });
  }

  /** Only the durable owner DELETED receipt frees committed usage; archive keeps OPEN. */
  public Mono<Void> releaseUsage(Reservation expected) {
    return context(Command.RELEASE_USAGE, expected.operation())
        .flatMap(
            c -> {
              scope(c, expected);
              return tx.execute(
                  db ->
                      locked(db, expected.tenant(), c, Command.RELEASE_USAGE, expected.operation())
                          .then(required(db, expected))
                          .flatMap(
                              r -> {
                                if (r.state() != State.COMMITTED)
                                  return Mono.error(
                                      fail(
                                          DomainStatus.FAILED_PRECONDITION,
                                          "RESERVATION_NOT_COMMITTED"));
                                return remote(owner.inspect(r))
                                    .switchIfEmpty(
                                        Mono.error(
                                            fail(
                                                DomainStatus.UNAVAILABLE, "OWNER_OUTCOME_UNKNOWN")))
                                    .flatMap(
                                        receipt -> {
                                          matching(r, receipt);
                                          if (receipt.state() != OwnerState.DELETED)
                                            return Mono.error(
                                                fail(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "PHYSICAL_DELETION_REQUIRED"));
                                          return effect(db, r, "RELEASE_USAGE")
                                              .then(refresh(db, r.tenant(), r))
                                              .then();
                                        });
                              }));
            });
  }

  @Override
  public Mono<QuotaUsage> current(UUID tenant) {
    return context(Command.READ, null)
        .flatMap(
            c -> {
              if (!tenant.toString().equals(c.tenantId()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return terms(tenant)
                  .flatMap(
                      terms ->
                          tx.execute(
                              db ->
                                  locked(db, tenant, c, Command.READ, null)
                                      .then(refresh(db, tenant))
                                      .map(
                                          counter -> {
                                            if (terms.version() < counter.entitlement())
                                              throw fail(
                                                  DomainStatus.UNAVAILABLE, "ENTITLEMENT_STALE");
                                            var b =
                                                QuotaUsage.newBuilder()
                                                    .setKind(QuotaUsage.KindValue.KIND_PROJECT)
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

  private Mono<Counter> refresh(DatabaseClient db, UUID tenant) {
    return refresh(db, tenant, null);
  }

  private Mono<Counter> refresh(DatabaseClient db, UUID tenant, Reservation accepted) {
    return remote(accepted == null ? owner.inventory(tenant) : owner.inventory(tenant, accepted))
        .flatMap(
            inventory -> {
              if (!tenant.equals(inventory.tenant()))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH"));
              return db.sql(
                      "SELECT e.resource_id FROM taska.quota_effects e WHERE e.tenant_id=:t AND"
                          + " e.resource='PROJECT' AND e.effect_kind='COMMIT' AND NOT EXISTS(SELECT"
                          + " 1 FROM taska.quota_effects d WHERE d.tenant_id=e.tenant_id AND"
                          + " d.operation_id=e.operation_id AND d.resource=e.resource AND"
                          + " d.effect_kind='RELEASE_USAGE')")
                  .bind("t", tenant)
                  .map((r, m) -> r.get("resource_id", UUID.class))
                  .all()
                  .collectList()
                  .flatMap(
                      resources -> {
                        var all = new HashSet<>(inventory.resources());
                        all.addAll(resources);
                        long used = all.size();
                        return db.sql(
                                "INSERT INTO taska.quota_counters(tenant_id,resource,scope_id)"
                                    + " VALUES(:t,'PROJECT',:t) ON CONFLICT DO NOTHING")
                            .bind("t", tenant)
                            .fetch()
                            .rowsUpdated()
                            .then(
                                db.sql(
                                        """
                                        UPDATE taska.quota_counters SET used=:used,reserved=(SELECT count(*) FROM taska.quota_reservations WHERE tenant_id=:t AND resource='PROJECT' AND state='ACTIVE'),
                                        version=version+CASE WHEN used<>:used OR reserved<>(SELECT count(*) FROM taska.quota_reservations WHERE tenant_id=:t AND resource='PROJECT' AND state='ACTIVE') THEN 1 ELSE 0 END,reconciled_at=:now
                                        WHERE tenant_id=:t AND resource='PROJECT' AND version<9223372036854775807 RETURNING used,reserved,version,entitlement_version
                                        """)
                                    .bind("t", tenant)
                                    .bind("used", used)
                                    .bind("now", offset(clock.instant()))
                                    .map(
                                        (r, m) ->
                                            new Counter(
                                                r.get("used", Long.class),
                                                r.get("reserved", Long.class),
                                                r.get("version", Long.class),
                                                r.get("entitlement_version", Long.class)))
                                    .one())
                            .switchIfEmpty(
                                Mono.error(
                                    fail(
                                        DomainStatus.RESOURCE_EXHAUSTED,
                                        "QUOTA_VERSION_EXHAUSTED")));
                      });
            });
  }

  private Mono<Void> effect(DatabaseClient db, Reservation r, String kind) {
    return db.sql(
            "INSERT INTO"
                + " taska.quota_effects(tenant_id,operation_id,resource,scope_id,effect_kind,amount,resource_id,owner_version,applied_at)"
                + " VALUES(:t,:op,'PROJECT',:t,:kind,1,:resource,:version,:now) ON CONFLICT DO"
                + " NOTHING")
        .bind("t", r.tenant())
        .bind("op", r.operation())
        .bind("kind", kind)
        .bind("resource", r.resource())
        .bind("version", r.version())
        .bind("now", offset(clock.instant()))
        .fetch()
        .rowsUpdated()
        .then();
  }

  private Mono<Void> locked(
      DatabaseClient db,
      UUID tenant,
      VerifiedTenantContext context,
      Command command,
      UUID operation) {
    return lock(db, tenant).then(authorize(context, command, operation)).then();
  }

  private Mono<Void> lock(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT id FROM taska.tenants WHERE id=:t AND lifecycle<>'DELETED' FOR UPDATE")
        .bind("t", tenant)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .then();
  }

  private Mono<Reservation> find(DatabaseClient db, UUID tenant, UUID operation) {
    return db.sql(
            "SELECT * FROM taska.quota_reservations WHERE tenant_id=:t AND resource='PROJECT' AND"
                + " operation_id=:op")
        .bind("t", tenant)
        .bind("op", operation)
        .map((r, m) -> reservation(r))
        .one();
  }

  private static Reservation reservation(io.r2dbc.spi.Row r) {
    return new Reservation(
        r.get("tenant_id", UUID.class),
        r.get("operation_id", UUID.class),
        r.get("resource_id", UUID.class),
        r.get("id", UUID.class),
        r.get("fence", Long.class),
        r.get("expires_at", OffsetDateTime.class).toInstant(),
        State.valueOf(r.get("state", String.class)),
        r.get("version", Long.class),
        r.get("project_cancel_requested_at", OffsetDateTime.class) != null);
  }

  private Mono<Reservation> required(DatabaseClient db, Reservation expected) {
    return find(db, expected.tenant(), expected.operation())
        .switchIfEmpty(Mono.error(fail(DomainStatus.NOT_FOUND, "RESERVATION_NOT_FOUND")))
        .map(
            r -> {
              if (!r.sameIdentity(expected) || !r.expiresAt().equals(expected.expiresAt()))
                throw fail(DomainStatus.FAILED_PRECONDITION, "STALE_FENCE");
              return r;
            });
  }

  private Mono<VerifiedTenantContext> context(Command command, UUID operation) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              String scope =
                  command == Command.READ
                      ? "read"
                      : (command == Command.LOOKUP && c.scopes().contains("read"))
                          ? "read"
                          : "write";
              if (!c.scopes().contains(scope))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return authorize(c, command, operation).thenReturn(c);
            });
  }

  private Mono<Grant> authorize(VerifiedTenantContext c, Command command, UUID operation) {
    return policy
        .current(c, command, operation)
        .timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "POLICY_UNAVAILABLE")));
  }

  private static void scope(VerifiedTenantContext c, Reservation r) {
    if (!r.tenant().toString().equals(c.tenantId()))
      throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
  }

  private static void matching(Reservation r, Receipt receipt) {
    if (!r.sameIdentity(receipt.reservation())
        || !r.expiresAt().equals(receipt.reservation().expiresAt()))
      throw fail(DomainStatus.PERMISSION_DENIED, "OWNER_RECEIPT_MISMATCH");
  }

  private <T> Mono<T> remote(Mono<T> work) {
    return work.timeout(Duration.ofSeconds(5))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")));
  }

  private Mono<Terms> terms(UUID tenant) {
    return remote(billing.current(tenant, 0))
        .switchIfEmpty(Mono.error(fail(DomainStatus.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE")))
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
                      .filter(l -> l.getKind() == QuotaLimit.KindValue.KIND_PROJECT)
                      .findFirst()
                      .orElseThrow(),
                  Long.parseLong(e.getVersion()));
            });
  }

  private static void writable(Entitlement e) {
    if (!e.getRestrictionsList().isEmpty())
      throw fail(DomainStatus.FAILED_PRECONDITION, "READ_ONLY");
  }

  private static OffsetDateTime offset(Instant time) {
    return time.atOffset(ZoneOffset.UTC);
  }

  private static String hash(UUID op, UUID resource, Instant expiry) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      (op + "/" + resource + "/" + expiry)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static DomainException fail(DomainStatus status, String message) {
    return new DomainException(status, message);
  }
}
