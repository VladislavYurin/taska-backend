package ru.taska.entitlement;

import com.google.protobuf.util.JsonFormat;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.event.*;
import ru.taska.exception.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Local commercial facts. Actor authority and independent lifecycle restrictions are never cached.
 */
public final class EntitlementProjection {
  public enum Use {
    READ,
    NEW_RESOURCE
  }

  public interface Source {
    Mono<Entitlement> current(UUID tenant, long minimumVersion);
  }

  public interface Access {
    /** Must check current membership/role/project ACL and return current pause/block reasons. */
    Mono<Set<RestrictionCode>> current(VerifiedTenantContext context, Use use);
  }

  public interface EventAuthority {
    /** Check authenticated broker/source identity and purpose, not just envelope strings. */
    Mono<Boolean> allowed(VerifiedTenantContext context, TaskaEvent event);
  }

  public record View(Entitlement snapshot, Set<RestrictionCode> restrictions, boolean validNow) {
    public View {
      restrictions = Set.copyOf(restrictions);
    }

    /** Conservative admission guard; the capability evaluator may impose further restrictions. */
    public void requireNewResource() {
      if (!validNow || !restrictions.isEmpty()) throw failure(DomainStatus.FAILED_PRECONDITION);
    }
  }

  /** Absolute source deadline, never renewed by a read of the memory or durable cache. */
  public record Lease(View view, Instant validUntil) {}

  private record Stored(Entitlement snapshot, Instant observed) {}
  private record CacheKey(UUID tenant, long version) {}

  private final TenantTransactionExecutor transactions;
  private final Source source;
  private final Access access;
  private final EventAuthority events;
  private final Clock clock;
  private final Duration ttl;
  private final int capacity;
  private final Map<CacheKey, Stored> cache = new ConcurrentHashMap<>();
  private final Map<UUID, CacheKey> currentKeys = new ConcurrentHashMap<>();
  private final AtomicLong epoch = new AtomicLong();

  public EntitlementProjection(
      TenantTransactionExecutor transactions,
      Source source,
      Access access,
      EventAuthority events,
      Clock clock,
      Duration ttl,
      int capacity) {
    this.transactions = Objects.requireNonNull(transactions);
    this.source = Objects.requireNonNull(source);
    this.access = Objects.requireNonNull(access);
    this.events = Objects.requireNonNull(events);
    this.clock = Objects.requireNonNull(clock);
    if (ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofSeconds(30)) > 0
        || capacity < 1) throw new IllegalArgumentException("Bounded cache required");
    this.ttl = ttl;
    this.capacity = capacity;
  }

  /** READ can reuse a bounded, trusted snapshot. Every admission reconciles with billing. */
  public Mono<View> resolve(Use use) {
    return resolveLease(use).map(Lease::view);
  }

  public Mono<Lease> resolveLease(Use use) {
    return VerifiedTenantContext.current()
        .flatMap(
            context -> {
              UUID tenant = uuid(context.tenantId());
              return access
                  .current(context, use)
                  .switchIfEmpty(Mono.error(failure(DomainStatus.UNAVAILABLE)))
                  .flatMap(
                      reasons ->
                          current(tenant, use == Use.NEW_RESOURCE)
                              .map(stored -> {
                                Instant now = clock.instant();
                                Instant until = stored.observed().plus(ttl);
                                var snapshot = stored.snapshot();
                                // Already-active expiry is a denial reason, not an unavailable
                                // source. Future period boundaries shorten the observation lease.
                                for (String boundary : List.of(snapshot.getValidFrom(),
                                    snapshot.hasValidUntil() ? snapshot.getValidUntil() : "",
                                    snapshot.hasPaidThrough() ? snapshot.getPaidThrough() : "")) {
                                  if (!boundary.isEmpty()) {
                                    Instant at = Instant.parse(boundary);
                                    if (at.isAfter(now) && at.isBefore(until)) until = at;
                                  }
                                }
                                return new Lease(view(snapshot, reasons), until);
                              }));
            });
  }

  /** Reconciliation is also usable by an explicitly authorized tenant worklist job. */
  private Mono<Stored> current(UUID tenant, boolean reconcile) {
    return Mono.defer(
        () -> {
          long generation;
          synchronized (cache) {
            generation = epoch.get();
            var key = currentKeys.get(tenant);
            Stored cached = key == null ? null : cache.get(key);
            if (!reconcile && fresh(cached)) return Mono.just(cached);
          }
          return transactions
              .execute(db -> load(db, tenant))
              .defaultIfEmpty(new Stored(null, Instant.EPOCH))
              .flatMap(
                  stored ->
                      !reconcile && fresh(stored)
                          ? Mono.just(stored)
                          : source
                              .current(
                                  tenant,
                                  stored.snapshot() == null ? 0 : version(stored.snapshot()))
                              .timeout(Duration.ofSeconds(5))
                              .switchIfEmpty(Mono.error(failure(DomainStatus.UNAVAILABLE)))
                              .map(EntitlementProjection::validate)
                              .flatMap(
                                  snapshot -> {
                                    if (!tenant.toString().equals(snapshot.getTenantId())
                                        || (stored.snapshot() != null
                                            && version(snapshot) < version(stored.snapshot())))
                                      return Mono.error(failure(DomainStatus.UNAVAILABLE));
                                    return transactions
                                        .execute(db -> save(db, tenant, snapshot, true))
                                        .doOnNext(
                                            ignored -> {
                                              synchronized (cache) {
                                                epoch.incrementAndGet();
                                                evict(tenant);
                                              }
                                            });
                                  }))
              .doOnNext(
                  stored -> {
                    synchronized (cache) {
                      if (generation != epoch.get()) return;
                      evict(tenant);
                      if (cache.size() >= capacity) {
                        cache.clear();
                        currentKeys.clear();
                      }
                      var key = new CacheKey(tenant, version(stored.snapshot()));
                      cache.put(key, stored);
                      currentKeys.put(tenant, key);
                    }
                  });
        });
  }

  /** Durable deduplication and version monotonicity share one local tenant transaction. */
  public Mono<Void> apply(TaskaEvent event) {
    return Mono.defer(
        () -> {
          TaskaEventContract.requireValid(event);
          if (event.scopeKind() != EventScopeKind.TENANT
              || !"billing-service".equals(event.producerService())
              || !"Entitlement".equals(event.aggregateType())
              || !"EntitlementSnapshotChanged".equals(event.eventType())
              || !event.tenantId().equals(event.aggregateId())
              || event.payload() == null) throw failure(DomainStatus.INVALID_ARGUMENT);
          Entitlement snapshot;
          try {
            var builder = Entitlement.newBuilder();
            JsonFormat.parser().merge(event.payload().toString(), builder);
            snapshot = validate(builder.build());
          } catch (Exception invalid) {
            throw failure(DomainStatus.INVALID_ARGUMENT);
          }
          if (!event.tenantId().toString().equals(snapshot.getTenantId()))
            throw failure(DomainStatus.INVALID_ARGUMENT);
          return VerifiedTenantContext.current()
              .flatMap(
                  context -> {
                    if (!event.tenantId().toString().equals(context.tenantId()))
                      return Mono.error(failure(DomainStatus.PERMISSION_DENIED));
                    return events
                        .allowed(context, event)
                        .defaultIfEmpty(false)
                        .flatMap(
                            ok -> {
                              if (!ok) return Mono.error(failure(DomainStatus.PERMISSION_DENIED));
                              return transactions
                                  .execute(
                                      db ->
                                          receipt(db, event, snapshot)
                                              .then(save(db, event.tenantId(), snapshot, false)))
                                  .doOnNext(
                                      ignored -> {
                                        synchronized (cache) {
                                          epoch.incrementAndGet();
                                          evict(event.tenantId());
                                        }
                                      })
                                  .then();
                            });
                  });
        });
  }

  private Mono<Void> receipt(DatabaseClient db, TaskaEvent event, Entitlement snapshot) {
    String body = encode(snapshot);
    return db.sql(
            "INSERT INTO taska.entitlement_projection_events(tenant_id,event_id,body)"
                + " VALUES(:t,:id,:b) ON CONFLICT DO NOTHING")
        .bind("t", event.tenantId())
        .bind("id", event.id())
        .bind("b", body)
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "SELECT body FROM taska.entitlement_projection_events WHERE tenant_id=:t AND"
                        + " event_id=:id")
                .bind("t", event.tenantId())
                .bind("id", event.id())
                .map((r, m) -> r.get("body", String.class))
                .one())
        .flatMap(
            previous ->
                previous.equals(body)
                    ? Mono.<Void>empty()
                    : Mono.error(failure(DomainStatus.FAILED_PRECONDITION)));
  }

  private Mono<Stored> load(DatabaseClient db, UUID tenant) {
    return db.sql("SELECT body,observed_at FROM taska.entitlement_projections WHERE tenant_id=:t")
        .bind("t", tenant)
        .map(
            (r, m) ->
                new Stored(
                    decode(r.get("body", String.class)),
                    r.get("observed_at", OffsetDateTime.class).toInstant()))
        .one();
  }

  private Mono<Stored> save(
      DatabaseClient db, UUID tenant, Entitlement snapshot, boolean authoritative) {
    // INSERT/UPDATE locks the single tenant row. Conflicting equal versions cannot refresh trust.
    String body = encode(snapshot);
    return db.sql(
            """
            INSERT INTO taska.entitlement_projections(tenant_id,version,body,observed_at)
            VALUES(:t,:v,:b,:now) ON CONFLICT(tenant_id) DO UPDATE SET
              version=EXCLUDED.version,body=EXCLUDED.body,observed_at=EXCLUDED.observed_at
            WHERE taska.entitlement_projections.version<EXCLUDED.version OR
              (:authoritative AND taska.entitlement_projections.version=EXCLUDED.version
               AND taska.entitlement_projections.body=EXCLUDED.body)
            """)
        .bind("t", tenant)
        .bind("v", version(snapshot))
        .bind("b", body)
        .bind("now", clock.instant().atOffset(ZoneOffset.UTC))
        .bind("authoritative", authoritative)
        .fetch()
        .rowsUpdated()
        .then(load(db, tenant))
        .flatMap(
            current -> {
              if (version(current.snapshot()) == version(snapshot)
                  && !current.snapshot().equals(snapshot))
                return Mono.error(failure(DomainStatus.FAILED_PRECONDITION));
              // An RPC that lost a race to a newer event may use that newer committed value.
              return Mono.just(current);
            });
  }

  private boolean fresh(Stored value) {
    Instant now = clock.instant();
    return value != null
        && value.snapshot() != null
        && !now.isBefore(value.observed())
        && now.isBefore(value.observed().plus(ttl))
        && temporal(value.snapshot(), now);
  }

  private void evict(UUID tenant) {
    var key = currentKeys.remove(tenant);
    if (key != null) cache.remove(key);
  }

  private View view(Entitlement snapshot, Set<RestrictionCode> independent) {
    var reasons = new HashSet<>(independent);
    reasons.addAll(snapshot.getRestrictionsList());
    boolean valid = temporal(snapshot, clock.instant());
    if (!valid) reasons.add(RestrictionCode.RESTRICTION_CODE_PAYMENT_EXPIRED);
    return new View(snapshot, reasons, valid);
  }

  private static boolean temporal(Entitlement e, Instant now) {
    return !now.isBefore(Instant.parse(e.getValidFrom()))
        && (!e.hasValidUntil() || now.isBefore(Instant.parse(e.getValidUntil())))
        && (!e.hasPaidThrough() || now.isBefore(Instant.parse(e.getPaidThrough())));
  }

  public static Entitlement validate(Entitlement e) {
    try {
      uuid(e.getTenantId());
      uuid(e.getPlanVersionId());
      version(e);
      Instant from = Instant.parse(e.getValidFrom());
      Instant.parse(e.getIssuedAt());
      if (e.hasCoveragePeriodId()) uuid(e.getCoveragePeriodId());
      if (e.hasValidUntil() && !Instant.parse(e.getValidUntil()).isAfter(from))
        throw new IllegalArgumentException();
      if (e.hasPaidThrough()) Instant.parse(e.getPaidThrough());
      if (e.hasCoveragePeriodId() && !e.hasPaidThrough()) throw new IllegalArgumentException();
      var kinds = EnumSet.noneOf(QuotaLimit.KindValue.class);
      for (var limit : e.getLimitsList()) {
        if (limit.getKind() == QuotaLimit.KindValue.KIND_UNSPECIFIED
            || limit.getKind() == QuotaLimit.KindValue.UNRECOGNIZED
            || !kinds.add(limit.getKind())
            || limit.getUnlimited() == limit.hasLimit()) throw new IllegalArgumentException();
        if (limit.hasLimit()
            && (!limit.getLimit().matches("0|[1-9][0-9]{0,18}")
                || Long.parseLong(limit.getLimit()) < 0)) throw new IllegalArgumentException();
      }
      if (!kinds.equals(
              EnumSet.of(
                  QuotaLimit.KindValue.KIND_PROJECT,
                  QuotaLimit.KindValue.KIND_SEAT,
                  QuotaLimit.KindValue.KIND_STORAGE_BYTES,
                  QuotaLimit.KindValue.KIND_TASK_LIFETIME))
          || e.getRestrictionsList().stream()
              .anyMatch(
                  r ->
                      r == RestrictionCode.UNRECOGNIZED
                          || r == RestrictionCode.RESTRICTION_CODE_UNSPECIFIED)
          || e.getFeaturesList().stream().anyMatch(f -> f.isBlank() || f.length() > 128)
          || e.getFeaturesCount() > 256) throw new IllegalArgumentException();
      return e.toBuilder()
          .clearRestrictions()
          .addAllRestrictions(e.getRestrictionsList().stream().distinct().sorted().toList())
          .clearLimits()
          .addAllLimits(
              e.getLimitsList().stream()
                  .sorted(Comparator.comparingInt(QuotaLimit::getKindValue))
                  .toList())
          .clearFeatures()
          .addAllFeatures(e.getFeaturesList().stream().distinct().sorted().toList())
          .build();
    } catch (RuntimeException invalid) {
      throw failure(DomainStatus.INVALID_ARGUMENT);
    }
  }

  private static long version(Entitlement e) {
    if (!e.getVersion().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
    return Long.parseLong(e.getVersion());
  }

  private static UUID uuid(String value) {
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException();
    return id;
  }

  private static String encode(Entitlement e) {
    return Base64.getEncoder().encodeToString(e.toByteArray());
  }

  private static Entitlement decode(String body) {
    try {
      return validate(Entitlement.parseFrom(Base64.getDecoder().decode(body)));
    } catch (Exception error) {
      throw failure(DomainStatus.UNAVAILABLE);
    }
  }

  private static DomainException failure(DomainStatus status) {
    return new DomainException(
        status, "Entitlement projection unavailable", Map.of("reason", "ENTITLEMENT_UNTRUSTED"));
  }
}
