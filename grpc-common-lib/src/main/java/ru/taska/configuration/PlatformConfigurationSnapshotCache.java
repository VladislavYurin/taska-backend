package ru.taska.configuration;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/**
 * Only public source facts are cached. Actor permissions and effective decisions never enter it.
 */
public final class PlatformConfigurationSnapshotCache {
  public enum Use {
    OBSERVE,
    ADMISSION
  }

  public interface Source {
    Mono<PlatformConfigurationSnapshot> current();
  }

  public interface EventAuthority {
    /** Check authenticated broker/source identity, scope and purpose, not payload producer text. */
    Mono<Boolean> allowed(VerifiedTenantContext context, Change change);
  }

  public record Change(String key, long version) {}

  public record Lease(
      PlatformConfigurationSnapshot snapshot, Instant observedAt, Instant validUntil) {
    public Map<Flag, Boolean> flags() {
      var values = new EnumMap<Flag, Boolean>(Flag.class);
      snapshot.getItemsList().stream()
          .filter(PlatformFlag::hasBooleanValue)
          .forEach(flag -> values.put(Flag.valueOf(flag.getKey()), flag.getBooleanValue()));
      return Collections.unmodifiableMap(values);
    }

    public Duration duration(String key) {
      var flag =
          snapshot.getItemsList().stream()
              .filter(f -> f.getKey().equals(key))
              .findFirst()
              .orElseThrow(() -> unavailable());
      if (flag.getType() != PlatformFlag.TypeValue.TYPE_DURATION_SECONDS) throw unavailable();
      return Duration.ofSeconds(number(flag.getIntegerValue()));
    }
  }

  private static final Set<String> DURATIONS =
      Set.of("UNPAID_CREATION_WINDOW_SECONDS", "WIRE_RECONCILIATION_TARGET_SECONDS");
  private static final Set<String> KEYS;

  static {
    var keys = new HashSet<>(DURATIONS);
    for (Flag flag : Flag.values()) keys.add(flag.name());
    KEYS = Set.copyOf(keys);
  }

  private final Source source;
  private final EventAuthority events;
  private final Clock clock;
  private final Duration ttl;
  private final Map<String, Long> minimum = new HashMap<>();
  private final Map<String, PlatformFlag> seen = new HashMap<>();
  private Lease cached;
  private long epoch;
  private Instant lastClock = Instant.MIN;

  public PlatformConfigurationSnapshotCache(
      Source source, EventAuthority events, Clock clock, Duration ttl) {
    this.source = Objects.requireNonNull(source);
    this.events = Objects.requireNonNull(events);
    this.clock = Objects.requireNonNull(clock);
    this.ttl = Objects.requireNonNull(ttl);
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Hard TTL in (0,30s] required");
  }

  /** Every admission reads the primary source; an observation can reuse an absolute lease. */
  public Mono<Lease> resolve(Use use) {
    Objects.requireNonNull(use);
    return Mono.defer(
        () -> {
          Instant started;
          long generation;
          Lease baseline;
          synchronized (this) {
            started = now();
            if (use == Use.OBSERVE && cached != null && started.isBefore(cached.validUntil()))
              return Mono.just(cached);
            generation = epoch;
            baseline = cached;
          }
          return Mono.defer(source::current)
              .timeout(Duration.ofSeconds(5))
              .switchIfEmpty(Mono.error(unavailable()))
              .map(
                  snapshot -> {
                    var canonical = snapshot(snapshot.getItemsList());
                    if (!canonical.getVersion().equals(snapshot.getVersion())
                        || snapshot.getCacheMaxAgeSeconds() < 1
                        || snapshot.getCacheMaxAgeSeconds() > 30) throw unavailable();
                    Duration bound = Duration.ofSeconds(snapshot.getCacheMaxAgeSeconds());
                    Instant until = started.plus(ttl.compareTo(bound) < 0 ? ttl : bound);
                    synchronized (this) {
                      Instant current = now();
                      if (generation != epoch || !current.isBefore(until)) throw unavailable();
                      for (var flag : canonical.getItemsList()) {
                        long version = number(flag.getVersion());
                        var previous = seen.get(flag.getKey());
                        if (Instant.parse(flag.getEffectiveAt()).isAfter(current)
                            || version < minimum.getOrDefault(flag.getKey(), 0L)
                            || previous != null
                                && version == number(previous.getVersion())
                                && !previous.equals(flag)) throw unavailable();
                      }
                      for (var flag : canonical.getItemsList()) {
                        minimum.put(flag.getKey(), number(flag.getVersion()));
                        seen.put(flag.getKey(), flag);
                      }
                      cached = new Lease(snapshot, started, until);
                      return cached;
                    }
                  })
              .doOnError(error -> clearFailedRead(generation, baseline))
              .onErrorMap(error -> unavailable());
        });
  }

  /** The event is a version fence, never a replacement value or a new observation timestamp. */
  public Mono<Void> invalidate(Change change) {
    return Mono.defer(
        () -> {
          if (change == null || !KEYS.contains(change.key()) || change.version() < 1)
            return Mono.error(
                new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid configuration event"));
          return VerifiedTenantContext.current()
              .onErrorMap(
                  IllegalStateException.class,
                  error ->
                      new DomainException(
                          DomainStatus.UNAUTHENTICATED, "Verified event context required"))
              .flatMap(
                  context -> {
                    var claims = RpcContextBridge.details(context).getContext();
                    if (claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                        || context.actorId() != null
                        || context.tenantId() != null
                        || !"applyPlatformConfiguration".equals(context.action())
                        || !context.scopes().contains("configuration:invalidate"))
                      return Mono.error(
                          new DomainException(
                              DomainStatus.PERMISSION_DENIED, "Configuration event denied"));
                    return events
                        .allowed(context, change)
                        .defaultIfEmpty(false)
                        .flatMap(
                            allowed -> {
                              if (!allowed)
                                return Mono.error(
                                    new DomainException(
                                        DomainStatus.PERMISSION_DENIED,
                                        "Configuration event denied"));
                              synchronized (this) {
                                if (change.version() > minimum.getOrDefault(change.key(), 0L)) {
                                  minimum.put(change.key(), change.version());
                                  cached = null;
                                  epoch++;
                                }
                              }
                              return Mono.empty();
                            });
                  });
        });
  }

  /** Memory reset preserves bounded version fences; unavailable sources never restore defaults. */
  public synchronized void clear() {
    cached = null;
    epoch++;
  }

  private synchronized void clearFailedRead(long generation, Lease baseline) {
    // Failed reconciliation invalidates its old warm entry, including invalid primary data.
    // A late failure must not erase a newer successful read or fence a newer event generation.
    if (generation == epoch && cached == baseline) cached = null;
  }

  public static Set<String> keys() {
    return KEYS;
  }

  private Instant now() {
    Instant now = clock.instant();
    if (now.isBefore(lastClock)) throw unavailable();
    lastClock = now;
    return now;
  }

  /** Shared canonical encoding for the owning RPC and the consumer's version validation. */
  public static PlatformConfigurationSnapshot snapshot(List<PlatformFlag> values) {
    try {
      var keys = new HashSet<String>();
      for (var flag : values) {
        if (!KEYS.contains(flag.getKey())
            || !keys.add(flag.getKey())
            || !flag.getOwner().matches("[a-z][a-z0-9-]{1,63}")) throw unavailable();
        number(flag.getVersion());
        Instant.parse(flag.getEffectiveAt());
        if (DURATIONS.contains(flag.getKey())) {
          long min = number(flag.getMinimumValue()), max = number(flag.getMaximumValue());
          long value = number(flag.getIntegerValue()),
              fallback = number(flag.getDefaultIntegerValue());
          if (flag.getType() != PlatformFlag.TypeValue.TYPE_DURATION_SECONDS
              || flag.hasBooleanValue()
              || flag.hasDefaultBooleanValue()
              || min > max
              || value < min
              || value > max
              || fallback < min
              || fallback > max) throw unavailable();
        } else if (flag.getType() != PlatformFlag.TypeValue.TYPE_BOOLEAN
            || !flag.hasBooleanValue()
            || !flag.hasDefaultBooleanValue()
            || flag.hasIntegerValue()
            || flag.hasDefaultIntegerValue()
            || flag.hasMinimumValue()
            || flag.hasMaximumValue()) throw unavailable();
      }
      if (!keys.equals(KEYS)) throw unavailable();
      var body =
          PlatformConfigurationSnapshot.newBuilder()
              .addAllItems(
                  values.stream().sorted(Comparator.comparing(PlatformFlag::getKey)).toList());
      String version =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256").digest(body.build().toByteArray()));
      return body.setVersion(version).setCacheMaxAgeSeconds(30).build();
    } catch (Exception invalid) {
      throw unavailable();
    }
  }

  private static long number(String value) {
    if (!value.matches("[1-9][0-9]{0,18}")) throw unavailable();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw unavailable();
    }
  }

  private static DomainException unavailable() {
    return new DomainException(
        DomainStatus.UNAVAILABLE,
        "Configuration source unavailable",
        Map.of("reason", "CONFIGURATION_UNTRUSTED"));
  }
}
