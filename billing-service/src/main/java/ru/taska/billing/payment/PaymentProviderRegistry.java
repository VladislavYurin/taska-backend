package ru.taska.billing.payment;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit provisioned adapters; no fallback to the currently preferred PSP. */
public final class PaymentProviderRegistry {
  public record Identity(String provider, PaymentProvider.Merchant merchant) {
    public Identity {
      PaymentProvider.require(provider, "provider");
      Objects.requireNonNull(merchant);
    }
  }

  public record Registration(PaymentProvider adapter, Set<String> profileVersions) {
    public Registration {
      Objects.requireNonNull(adapter);
      profileVersions = Set.copyOf(profileVersions);
      if (profileVersions.isEmpty())
        throw new IllegalArgumentException("Frozen profile versions required");
    }
  }

  private final Map<Identity, Registration> adapters;

  public PaymentProviderRegistry(Map<Identity, Registration> adapters) {
    this.adapters = Map.copyOf(adapters);
  }

  public PaymentProvider require(
      String provider, PaymentProvider.Merchant merchant, String profileVersion) {
    var adapter = adapters.get(new Identity(provider, merchant));
    if (adapter == null || !adapter.profileVersions().contains(profileVersion))
      throw new ProviderException(ProviderException.Reason.UNSUPPORTED_CAPABILITY);
    return adapter.adapter();
  }
}
