package ru.taska.billing.commercial;

import java.math.BigDecimal;
import java.time.Instant;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CommercialFxPolicy;
import ru.taska.api.b2b.v1.CommercialStripe;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;

public final class CommercialDependencies {
  private CommercialDependencies() {}

  public interface ConfigurationSource {
    Mono<MerchantConfigurationSnapshot> current();

    Mono<MerchantConfigurationSnapshot> version(String version);
  }

  /** Resolves secret references privately and attests the actual provider client's identity. */
  public interface CredentialAuthority {
    Mono<CredentialBinding> verify(CommercialStripe stripe);
  }

  public record CredentialBinding(
      CommercialEnvironment environment,
      String merchantAccount,
      String apiKeyReference,
      String webhookKeyReference) {
    @Override
    public String toString() {
      return "CredentialBinding[redacted]";
    }
  }

  public record FxObservation(
      BigDecimal rate,
      String source,
      String sourceVersion,
      CommercialEnvironment environment,
      Instant quotedAt,
      Instant validUntil) {}

  public interface FxQuoteProvider {
    Mono<FxObservation> quote(CommercialEnvironment environment, CommercialFxPolicy policy);
  }
}
