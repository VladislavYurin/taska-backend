package ru.taska.billing;

import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.billing.payment.PaymentProvider;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Актуальные факты владельца и финансовые ограничения от сервисов-владельцев. */
public final class AutopayDependencies {
  private AutopayDependencies() {}

  public enum Use {
    REPLAY,
    CONSENT,
    REVOKE,
    DISPATCH
  }

  /**
   * Барьер отзыва меняется после спора или полного возврата и не возвращается к прежнему значению
   * после разрешения спора. Пауза запрещает новую попытку, но не восстанавливает согласие.
   */
  public record Grant(
      UUID owner,
      long ownerVersion,
      String revocationBarrier,
      boolean admissionAllowed,
      String termsVersion,
      String termsSha256,
      MethodBinding method,
      String termsText,
      String configurationVersion) {
    public Grant {
      Objects.requireNonNull(owner);
      if (ownerVersion <= 0 || revocationBarrier == null || revocationBarrier.isBlank())
        throw new IllegalArgumentException("Current owner and revocation barrier required");
    }

    @Override
    public String toString() {
      return "Grant[ownerVersion=" + ownerVersion + ", admissionAllowed=" + admissionAllowed + "]";
    }
  }

  public record MethodBinding(
      String provider,
      String merchantAccount,
      PaymentProvider.Environment environment,
      String profileVersion) {
    public MethodBinding {
      PaymentProvider.require(provider, "provider");
      PaymentProvider.require(merchantAccount, "merchantAccount");
      PaymentProvider.require(profileVersion, "profileVersion");
      Objects.requireNonNull(environment);
    }

    public PaymentProvider.Merchant merchant() {
      return new PaymentProvider.Merchant(merchantAccount, environment);
    }
  }

  @FunctionalInterface
  public interface Policy {
    /** Проверяет текущую identity/членство, владельца, lifecycle, PSP/autopay flags и спор. */
    Mono<Grant> require(VerifiedTenantContext context, Use use, UUID paymentMethod);
  }
}
