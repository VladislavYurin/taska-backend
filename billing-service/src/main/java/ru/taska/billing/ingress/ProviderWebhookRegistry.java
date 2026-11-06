package ru.taska.billing.ingress;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.billing.payment.ProviderException;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Explicitly provisioned verification adapters; no fake or permissive production default. */
public final class ProviderWebhookRegistry {
  public record Identity(String provider, String account, PaymentProvider.Environment environment) {
    public Identity {
      text(provider, 80);
      text(account, 200);
      Objects.requireNonNull(environment);
    }

    public PaymentProvider.Merchant merchant() {
      return new PaymentProvider.Merchant(account, environment);
    }
  }

  /** Null payment represents an authenticated unsupported event, never unverified input. */
  public record Observation(
      String eventId, String eventType, PaymentProvider.VerifiedEvent payment) {
    public Observation {
      text(eventId, 200);
      text(eventType, 100);
    }

    public static Observation known(PaymentProvider.VerifiedEvent event) {
      return new Observation(event.providerEventId(), event.kind().name(), event);
    }
  }

  public interface Verifier {
    /**
     * Authenticate exact raw bytes and provider delivery expiry, including unsupported types.
     * Economic occurrence is not a delivery TTL.
     */
    Mono<Observation> verify(PaymentProvider.Merchant merchant, byte[] body, String signature);
  }

  public record Registration(
      Identity identity, Set<String> profileVersions, List<Verifier> verifiers) {
    public Registration {
      Objects.requireNonNull(identity);
      profileVersions = Set.copyOf(profileVersions);
      verifiers = List.copyOf(verifiers);
      if (profileVersions.isEmpty()
          || profileVersions.size() > 100
          || verifiers.isEmpty()
          || verifiers.size() > 4)
        throw new IllegalArgumentException(
            "Bounded merchant profiles and verification keys required");
      profileVersions.forEach(v -> text(v, 100));
    }
  }

  private final Map<Identity, Registration> registrations;

  public ProviderWebhookRegistry(List<Registration> registrations) {
    if (registrations.isEmpty() || registrations.size() > 128)
      throw new IllegalArgumentException("Merchant registrations required");
    this.registrations =
        registrations.stream()
            .collect(Collectors.toUnmodifiableMap(Registration::identity, Function.identity()));
  }

  public static Verifier adapter(PaymentProvider provider) {
    return (merchant, body, signature) ->
        provider.verifyWebhook(merchant, body, signature).map(Observation::known);
  }

  public Registration require(Identity identity) {
    var value = registrations.get(identity);
    if (value == null) throw failure(DomainStatus.PERMISSION_DENIED, "PROVIDER_NOT_CONFIGURED");
    return value;
  }

  public Mono<Observation> verify(Identity identity, byte[] body, String signature) {
    return Mono.defer(
        () ->
            Flux.fromIterable(require(identity).verifiers())
                .concatMap(
                    v ->
                        Mono.defer(() -> v.verify(identity.merchant(), body.clone(), signature))
                            .onErrorResume(
                                ProviderException.class,
                                e ->
                                    e.reason() == ProviderException.Reason.INVALID_WEBHOOK
                                        ? Mono.empty()
                                        : Mono.error(e)))
                .next()
                .switchIfEmpty(Mono.error(failure(DomainStatus.UNAUTHENTICATED, "INVALID_WEBHOOK")))
                .map(
                    o -> {
                      if (o.payment() != null
                          && (!identity.merchant().equals(o.payment().merchant())
                              || !o.eventId().equals(o.payment().providerEventId())))
                        throw failure(DomainStatus.UNAUTHENTICATED, "INVALID_WEBHOOK");
                      if (o.payment() != null) text(o.payment().providerPaymentId(), 200);
                      return o;
                    }));
  }

  static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw failure(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
  }

  static DomainException failure(DomainStatus status, String code) {
    return new DomainException(status, code, Map.of("code", code));
  }
}
