package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.api.b2b.v1.*;
import ru.taska.capability.CapabilityFacts.Flag;
import ru.taska.configuration.PlatformConfigurationSnapshotCache;
import ru.taska.configuration.PlatformConfigurationSnapshotCache.*;
import ru.taska.exception.*;

@Timeout(10)
class PlatformConfigurationSnapshotCacheTest {
  static final Instant START = Instant.parse("2026-11-06T00:00:00Z");
  final AtomicReference<Instant> time = new AtomicReference<>(START);
  final Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return this;
        }

        public Instant instant() {
          return time.get();
        }
      };
  final AtomicReference<PlatformConfigurationSnapshot> primary = new AtomicReference<>(initial());
  final AtomicBoolean online = new AtomicBoolean(true), broker = new AtomicBoolean(true);
  final AtomicInteger calls = new AtomicInteger();
  final AtomicReference<Sinks.One<PlatformConfigurationSnapshot>> delayed = new AtomicReference<>();
  PlatformConfigurationSnapshotCache cache =
      new PlatformConfigurationSnapshotCache(
          () ->
              Mono.defer(
                  () -> {
                    calls.incrementAndGet();
                    if (!online.get())
                      return Mono.error(new IllegalStateException("private source error"));
                    return delayed.get() == null
                        ? Mono.just(primary.get())
                        : delayed.get().asMono();
                  }),
          (context, change) ->
              Mono.just(broker.get() && "applyPlatformConfiguration".equals(context.action())),
          clock,
          Duration.ofSeconds(30));

  static PlatformConfigurationSnapshot initial() {
    var values = new ArrayList<PlatformFlag>();
    for (var flag : Flag.values()) {
      boolean value = flag != Flag.FREE_MCP && flag != Flag.FREE_PERSONAL_API_TOKENS;
      values.add(
          PlatformFlag.newBuilder()
              .setKey(flag.name())
              .setType(PlatformFlag.TypeValue.TYPE_BOOLEAN)
              .setBooleanValue(value)
              .setDefaultBooleanValue(value)
              .setOwner("tenant-service")
              .setVersion("1")
              .setEffectiveAt(START.minusSeconds(60).toString())
              .build());
    }
    for (String key :
        List.of("UNPAID_CREATION_WINDOW_SECONDS", "WIRE_RECONCILIATION_TARGET_SECONDS"))
      values.add(
          PlatformFlag.newBuilder()
              .setKey(key)
              .setType(PlatformFlag.TypeValue.TYPE_DURATION_SECONDS)
              .setIntegerValue("86400")
              .setDefaultIntegerValue("86400")
              .setMinimumValue("1")
              .setMaximumValue("31536000")
              .setOwner("tenant-service")
              .setVersion("1")
              .setEffectiveAt(START.minusSeconds(60).toString())
              .build());
    return PlatformConfigurationSnapshotCache.snapshot(values);
  }

  static PlatformConfigurationSnapshot change(
      PlatformConfigurationSnapshot snapshot, String key, String version, boolean value) {
    return PlatformConfigurationSnapshotCache.snapshot(
        snapshot.getItemsList().stream()
            .map(
                f ->
                    f.getKey().equals(key)
                        ? f.toBuilder().setVersion(version).setBooleanValue(value).build()
                        : f)
            .toList());
  }

  Lease read() {
    return cache.resolve(Use.OBSERVE).block();
  }

  void invalidate(String key, long version) {
    var claims =
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .addScopes("configuration:invalidate")
            .setAction("applyPlatformConfiguration")
            .build();
    CapabilityEvaluatorTest.as(claims, cache.invalidate(new Change(key, version))).block();
  }

  void unavailable(Mono<?> operation) {
    var error = assertThrows(DomainException.class, operation::block);
    assertEquals(DomainStatus.UNAVAILABLE, error.getStatus());
    assertFalse(error.getMessage().contains("private"));
  }

  @Test
  void observationsHaveHardDeadlineAndTypedImmutableValues() {
    var first = read();
    assertEquals(START.plusSeconds(30), first.validUntil());
    assertEquals(Duration.ofDays(1), first.duration("WIRE_RECONCILIATION_TARGET_SECONDS"));
    assertFalse(first.flags().get(Flag.FREE_MCP));
    assertThrows(UnsupportedOperationException.class, () -> first.flags().put(Flag.MCP, false));
    time.set(START.plusSeconds(29));
    assertSame(first, read());
    assertEquals(1, calls.get());
    time.set(START.plusSeconds(30));
    var next = read();
    assertEquals(2, calls.get());
    assertEquals(START.plusSeconds(60), next.validUntil());
    assertEquals(first.snapshot().getVersion(), next.snapshot().getVersion());
  }

  @Test
  void admissionReconcilesAndSourceFailureNeverFallsBackToWarmAllowOrDefaults() {
    read();
    assertNotNull(cache.resolve(Use.ADMISSION).block());
    assertEquals(2, calls.get());
    online.set(false);
    unavailable(cache.resolve(Use.ADMISSION));
    unavailable(cache.resolve(Use.OBSERVE));
    cache.clear();
    unavailable(cache.resolve(Use.OBSERVE));
    online.set(true);
    primary.set(change(primary.get(), "MCP", "2", false));
    assertFalse(read().flags().get(Flag.MCP));
  }

  @Test
  void authenticatedEventsFenceVersionsAndOldOrDuplicateEventsNeverRenewTheLease() {
    read();
    invalidate("MCP", 2);
    unavailable(cache.resolve(Use.OBSERVE));
    primary.set(change(primary.get(), "MCP", "2", false));
    var current = read();
    time.set(START.plusSeconds(29));
    int before = calls.get();
    invalidate("MCP", 1);
    invalidate("MCP", 2);
    assertSame(current, read());
    assertEquals(before, calls.get());
    assertFalse(read().flags().get(Flag.MCP));
    assertEquals(START.plusSeconds(30), read().validUntil());
    broker.set(false);
    assertThrows(DomainException.class, () -> invalidate("MCP", 3));
    assertSame(current, read());
  }

  @Test
  void anInflightOldReplyCannotCrossAnEventFence() {
    var sink = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(sink);
    var waiting = cache.resolve(Use.OBSERVE).toFuture();
    invalidate("MCP", 2);
    sink.tryEmitValue(initial());
    var error = assertThrows(CompletionException.class, waiting::join);
    assertInstanceOf(DomainException.class, error.getCause());
    delayed.set(null);
    primary.set(change(initial(), "MCP", "2", false));
    assertFalse(read().flags().get(Flag.MCP));
  }

  @Test
  void sameVersionWithDifferentValueAndFutureEffectiveAtFailClosed() {
    read();
    primary.set(change(initial(), "MCP", "1", false));
    unavailable(cache.resolve(Use.ADMISSION));
    var future =
        initial().getItemsList().stream()
            .map(
                f ->
                    f.getKey().equals("MCP")
                        ? f.toBuilder()
                            .setVersion("2")
                            .setEffectiveAt(START.plusSeconds(1).toString())
                            .build()
                        : f)
            .toList();
    primary.set(PlatformConfigurationSnapshotCache.snapshot(future));
    unavailable(cache.resolve(Use.OBSERVE));
    time.set(START.plusSeconds(1));
    assertTrue(read().flags().get(Flag.MCP));
  }

  @Test
  void invalidSnapshotHashTtlMissingAndPrivateKeysCannotBecomeConfiguration() {
    for (var value :
        List.of(
            initial().toBuilder().setVersion("invalid").build(),
            initial().toBuilder().setCacheMaxAgeSeconds(31).build(),
            initial().toBuilder().setCacheMaxAgeSeconds(0).build(),
            initial().toBuilder().removeItems(0).build(),
            initial().toBuilder().addItems(initial().getItems(0)).build(),
            initial().toBuilder()
                .addItems(initial().getItems(0).toBuilder().setKey("MERCHANT_CONFIGURATION"))
                .build())) {
      primary.set(value);
      unavailable(cache.resolve(Use.OBSERVE));
    }
    primary.set(initial());
    assertNotNull(read());
    assertThrows(DomainException.class, () -> invalidate("MERCHANT_CONFIGURATION", 2));
  }

  @Test
  void wrongTypesAndDurationBoundsAreRejectedBeforeAnyDefaultCanBeUsed() {
    var values = new ArrayList<>(initial().getItemsList());
    int index = 0;
    while (!values.get(index).getKey().equals("WIRE_RECONCILIATION_TARGET_SECONDS")) index++;
    for (String value : List.of("0", "01", "31536001", "9223372036854775808")) {
      var copy = new ArrayList<>(values);
      copy.set(index, values.get(index).toBuilder().setIntegerValue(value).build());
      assertThrows(DomainException.class, () -> PlatformConfigurationSnapshotCache.snapshot(copy));
    }
  }

  @Test
  void backwardClockAndExcessiveTtlCannotExtendAnObservation() {
    read();
    time.set(START.minusSeconds(1));
    unavailable(cache.resolve(Use.OBSERVE));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PlatformConfigurationSnapshotCache(
                () -> Mono.just(initial()),
                (v, e) -> Mono.just(true),
                clock,
                Duration.ofSeconds(31)));
  }

  @Test
  void wrongEventPurposeScopeAndUserCredentialsCannotInvalidateTrustedFacts() {
    var before = read();
    var service =
        TrustedTenantContext.newBuilder()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setAction("applyPlatformConfiguration")
            .addScopes("configuration:invalidate")
            .build();
    for (var claims :
        List.of(
            service.toBuilder().setAction("getPlatformConfigurationSnapshot").build(),
            service.toBuilder().clearScopes().build(),
            service.toBuilder()
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL)
                .setActorUserId(UUID.randomUUID().toString())
                .build()))
      assertThrows(
          DomainException.class,
          () -> CapabilityEvaluatorTest.as(claims, cache.invalidate(new Change("MCP", 2))).block());
    assertSame(before, read());
    assertEquals(1, calls.get());
  }

  @Test
  void lateOldValidationFailureDoesNotEraseANewerPrimaryConfirmation() {
    read();
    var older = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(older);
    var waiting = cache.resolve(Use.ADMISSION).toFuture();
    delayed.set(null);
    primary.set(change(initial(), "MCP", "2", false));
    var confirmed = cache.resolve(Use.ADMISSION).block();
    older.tryEmitValue(initial());
    assertThrows(CompletionException.class, waiting::join);
    int before = calls.get();
    assertSame(confirmed, read());
    assertEquals(before, calls.get());
    assertFalse(read().flags().get(Flag.MCP));
  }

  @Test
  void lateTransportFailureCannotInvalidateReadsStartedAfterAnEvent() {
    read();
    var older = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(older);
    var first = cache.resolve(Use.ADMISSION).toFuture();
    invalidate("MCP", 2);
    var newer = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(newer);
    var second = cache.resolve(Use.OBSERVE).toFuture();
    older.tryEmitError(new IllegalStateException("private failure"));
    assertThrows(CompletionException.class, first::join);
    newer.tryEmitValue(change(initial(), "MCP", "2", false));
    var confirmed = second.join();
    assertSame(confirmed, read());
    assertFalse(confirmed.flags().get(Flag.MCP));
  }

  @Test
  void effectiveAtBelongsToTheImmutableVersionAndCannotChangeWithoutAVersionBump() {
    read();
    var metadata =
        initial().getItemsList().stream()
            .map(
                flag ->
                    flag.getKey().equals("MCP")
                        ? flag.toBuilder().setEffectiveAt(START.minusSeconds(1).toString()).build()
                        : flag)
            .toList();
    primary.set(PlatformConfigurationSnapshotCache.snapshot(metadata));
    unavailable(cache.resolve(Use.ADMISSION));
    primary.set(change(primary.get(), "MCP", "2", true));
    assertTrue(read().flags().get(Flag.MCP));
  }

  @Test
  void aFailedColdReadDoesNotFenceAHealthyConcurrentReconciliation() {
    var failing = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(failing);
    var first = cache.resolve(Use.ADMISSION).toFuture();
    var healthy = Sinks.<PlatformConfigurationSnapshot>one();
    delayed.set(healthy);
    var second = cache.resolve(Use.ADMISSION).toFuture();
    failing.tryEmitError(new IllegalStateException("private failure"));
    assertThrows(CompletionException.class, first::join);
    healthy.tryEmitValue(initial());
    assertSame(second.join(), read());
  }
}
