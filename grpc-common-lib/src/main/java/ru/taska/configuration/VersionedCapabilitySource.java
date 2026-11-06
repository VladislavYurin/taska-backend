package ru.taska.configuration;

import java.time.*;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.capability.*;
import ru.taska.capability.CapabilityFacts.*;
import ru.taska.entitlement.EntitlementProjection;
import ru.taska.exception.*;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Joins versioned source facts with freshly authorized actor/target facts on every subscription.
 */
public final class VersionedCapabilitySource implements CapabilityEvaluator.Source {
  @FunctionalInterface
  public interface Authority {
    /**
     * Primary identity, credential, membership/grant and target ACL checks precede source reads.
     */
    Mono<Assembler> authorize(VerifiedTenantContext context, Target target, Set<Action> actions);
  }

  @FunctionalInterface
  public interface Assembler {
    /**
     * Request-local only. Resolve plan kind against inputs' immutable planVersionId, not a role
     * cache.
     */
    Mono<CapabilityFacts> current(Inputs inputs);
  }

  /** No actor permission is stored under this source identity. */
  public record Key(UUID tenant, String flagsVersion, String entitlementVersion) {}

  /** A global target has no entitlement or tariff: entitlement is null and its version is empty. */
  public record Inputs(
      Key key,
      PlatformConfigurationSnapshotCache.Lease configuration,
      EntitlementProjection.Lease entitlement,
      Instant validUntil) {}

  private final Authority authority;
  private final PlatformConfigurationSnapshotCache configuration;
  private final EntitlementProjection entitlements;
  private final Clock clock;

  public VersionedCapabilitySource(
      Authority authority,
      PlatformConfigurationSnapshotCache configuration,
      EntitlementProjection entitlements,
      Clock clock) {
    this.authority = Objects.requireNonNull(authority);
    this.configuration = Objects.requireNonNull(configuration);
    this.entitlements = Objects.requireNonNull(entitlements);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Commands always reconcile both primary sources. Never use observations() for admission. */
  @Override
  public Mono<CapabilityFacts> current(
      VerifiedTenantContext context, Target target, Set<Action> actions) {
    return resolve(context, target, actions, PlatformConfigurationSnapshotCache.Use.ADMISSION);
  }

  /** For capability display/reads only: hard source TTLs still bound the resulting facts. */
  public CapabilityEvaluator.Source observations() {
    return (context, target, actions) ->
        resolve(context, target, actions, PlatformConfigurationSnapshotCache.Use.OBSERVE);
  }

  private Mono<CapabilityFacts> resolve(
      VerifiedTenantContext context,
      Target target,
      Set<Action> actions,
      PlatformConfigurationSnapshotCache.Use use) {
    Set<Action> requested = Set.copyOf(actions);
    return VerifiedTenantContext.current()
        .flatMap(
            actual -> {
              if (actual != context
                  || !Objects.equals(
                      context.tenantId(),
                      target.tenant() == null ? null : target.tenant().toString()))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "Tenant mismatch"));
              return Mono.defer(() -> authority.authorize(context, target, requested))
                  .timeout(Duration.ofSeconds(5))
                  .switchIfEmpty(Mono.error(unavailable()))
                  .flatMap(
                      assembler -> {
                        Mono<Inputs> inputs;
                        if (target.tenant() == null) {
                          inputs =
                              configuration
                                  .resolve(use)
                                  .map(
                                      flags ->
                                          new Inputs(
                                              new Key(null, flags.snapshot().getVersion(), ""),
                                              flags,
                                              null,
                                              flags.validUntil()));
                        } else {
                          var commercialUse =
                              use == PlatformConfigurationSnapshotCache.Use.ADMISSION
                                  ? EntitlementProjection.Use.NEW_RESOURCE
                                  : EntitlementProjection.Use.READ;
                          inputs =
                              Mono.zip(
                                      configuration.resolve(use),
                                      entitlements.resolveLease(commercialUse))
                                  .map(
                                      values -> {
                                        var flags = values.getT1();
                                        var commercial = values.getT2();
                                        Instant until =
                                            flags.validUntil().isBefore(commercial.validUntil())
                                                ? flags.validUntil()
                                                : commercial.validUntil();
                                        return new Inputs(
                                            new Key(
                                                target.tenant(),
                                                flags.snapshot().getVersion(),
                                                commercial.view().snapshot().getVersion()),
                                            flags,
                                            commercial,
                                            until);
                                      });
                        }
                        return inputs.flatMap(
                            value ->
                                Mono.defer(() -> assembler.current(value))
                                    .timeout(Duration.ofSeconds(5))
                                    .switchIfEmpty(Mono.error(unavailable()))
                                    .map(facts -> bind(context, target, value, facts)));
                      });
            });
  }

  private CapabilityFacts bind(
      VerifiedTenantContext context, Target target, Inputs inputs, CapabilityFacts facts) {
    Instant now = clock.instant();
    Instant credentialUntil;
    try {
      credentialUntil =
          Instant.parse(RpcContextBridge.details(context).getContext().getExpiresAt());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
    if (!Objects.equals(context.actorId(), facts.actor() == null ? null : facts.actor().toString())
        || !context.requestId().equals(facts.requestId())
        || !target.equals(facts.target())
        || !now.isBefore(inputs.validUntil())
        || !now.isBefore(facts.validUntil())
        || !now.isBefore(credentialUntil)
        || facts.validUntil().isAfter(now.plusSeconds(30))) throw unavailable();
    Instant until =
        facts.validUntil().isBefore(inputs.validUntil()) ? facts.validUntil() : inputs.validUntil();
    if (credentialUntil.isBefore(until)) until = credentialUntil;
    var commercial = inputs.entitlement();
    var restrictions = new HashSet<>(facts.independentRestrictions());
    if (commercial != null) restrictions.addAll(commercial.view().restrictions());
    return new CapabilityFacts(
        facts.actor(),
        target,
        facts.requestId(),
        until,
        facts.identity(),
        facts.membership(),
        facts.projectRole(),
        facts.lifecycle(),
        facts.resourceResolved(),
        facts.ownsResource(),
        facts.adminGrant(),
        commercial == null ? null : commercial.view().snapshot(),
        commercial == null ? PlanKind.UNKNOWN : facts.planKind(),
        restrictions,
        inputs.configuration().flags(),
        inputs.key().flagsVersion(),
        facts.channel(),
        facts.paymentChannel(),
        facts.controlNotification(),
        facts.integrity(),
        facts.businessDenials(),
        facts.accepted(),
        facts.worker());
  }

  private static DomainException unavailable() {
    return new DomainException(DomainStatus.UNAVAILABLE, "Current capability sources unavailable");
  }
}
