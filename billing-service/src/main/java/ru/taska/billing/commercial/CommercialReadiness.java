package ru.taska.billing.commercial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CommercialBuyerType;
import ru.taska.api.b2b.v1.CommercialCurrency;
import ru.taska.api.b2b.v1.CommercialEnvironment;
import ru.taska.api.b2b.v1.CommercialMarket;
import ru.taska.api.b2b.v1.CommercialMethod;
import ru.taska.api.b2b.v1.CommercialSeller;
import ru.taska.api.b2b.v1.MerchantConfiguration;
import ru.taska.api.b2b.v1.MerchantConfigurationSnapshot;
import ru.taska.api.b2b.v1.Money;
import ru.taska.api.b2b.v1.PlanVersion;
import ru.taska.billing.commercial.CommercialDependencies.ConfigurationSource;
import ru.taska.billing.commercial.CommercialDependencies.CredentialAuthority;
import ru.taska.billing.commercial.CommercialDependencies.CredentialBinding;
import ru.taska.billing.commercial.CommercialDependencies.FxObservation;
import ru.taska.billing.commercial.CommercialDependencies.FxQuoteProvider;
import ru.taska.commercial.CommercialConfigurationRules;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** No caller-supplied price/rate or fallback currency. All amounts are exact gross minor units. */
public final class CommercialReadiness {
  public record Selection(
      String country,
      CommercialBuyerType buyerType,
      CommercialCurrency currency,
      CommercialMethod method) {}

  public record FxSnapshot(UUID id, FxObservation observation) {}

  /**
   * Persist this entire immutable result in the invoice transaction, never resolve seller again.
   */
  public record InvoiceTerms(
      String configurationVersion,
      CommercialEnvironment environment,
      CommercialSeller seller,
      Selection selection,
      long baseGrossMinor,
      long grossMinor,
      long netMinor,
      long taxMinor,
      String taxRuleVersion,
      String taxLabel,
      String taxRate,
      String documentTemplateVersion,
      String refundPolicyVersion,
      FxSnapshot fx) {}

  public record PaymentAuthorization(
      String configurationVersion,
      CommercialEnvironment environment,
      String merchantAccount,
      String apiKeyReference,
      String webhookKeyReference,
      long amountMinor,
      CommercialCurrency currency) {
    @Override
    public String toString() {
      return "PaymentAuthorization[redacted]";
    }
  }

  private final ConfigurationSource configurations;
  private final CredentialAuthority credentials;
  private final FxQuoteProvider fx;
  private final Clock clock;

  public CommercialReadiness(
      ConfigurationSource configurations,
      CredentialAuthority credentials,
      FxQuoteProvider fx,
      Clock clock) {
    this.configurations = configurations;
    this.credentials = credentials;
    this.fx = fx;
    this.clock = clock;
  }

  private static DomainException unavailable(String reason) {
    return new DomainException(
        DomainStatus.UNAVAILABLE,
        "POLICY_NOT_CONFIGURED",
        Map.of("code", "POLICY_NOT_CONFIGURED", "reason", reason));
  }

  private static Throwable dependency(Throwable e) {
    return e instanceof DomainException
        ? e
        : new DomainException(
            DomainStatus.UNAVAILABLE,
            "DEPENDENCY_UNAVAILABLE",
            Map.of("code", "DEPENDENCY_UNAVAILABLE"));
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw unavailable(reason);
  }

  private MerchantConfiguration checked(MerchantConfigurationSnapshot s) {
    try {
      require(s.getVersion().matches("[1-9][0-9]{0,18}"), "CONFIGURATION_VERSION");
      Long.parseLong(s.getVersion());
      require(
          !Instant.parse(s.getEffectiveAt()).isAfter(clock.instant()),
          "CONFIGURATION_EFFECTIVE_AT");
      var c = s.getConfiguration();
      CommercialConfigurationRules.validate(c);
      require(
          c.getEnabled() && CommercialConfigurationRules.missing(c).isEmpty(),
          "COMMERCIAL_PROFILE");
      return c;
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw unavailable("COMMERCIAL_PROFILE");
    }
  }

  private Mono<MerchantConfigurationSnapshot> current() {
    return configurations
        .current()
        .switchIfEmpty(Mono.error(unavailable("COMMERCIAL_PROFILE")))
        .onErrorMap(CommercialReadiness::dependency);
  }

  private static CommercialMarket market(MerchantConfiguration c, Selection selection) {
    return c.getMarketsList().stream()
        .filter(
            m ->
                m.getCountry().equals(selection.country())
                    && m.getBuyerType() == selection.buyerType()
                    && m.getCurrency() == selection.currency()
                    && m.getMethod() == selection.method())
        .findFirst()
        .orElseThrow(
            () ->
                new DomainException(
                    DomainStatus.FAILED_PRECONDITION,
                    "CAPABILITY_DISABLED",
                    Map.of("code", "CAPABILITY_DISABLED", "reason", "UNSUPPORTED_MARKET")));
  }

  private Mono<CredentialBinding> credential(MerchantConfiguration c) {
    return credentials
        .verify(c.getStripe())
        .onErrorMap(CommercialReadiness::dependency)
        .switchIfEmpty(Mono.error(unavailable("STRIPE_CREDENTIALS")))
        .map(
            b -> {
              var s = c.getStripe();
              require(
                  b.environment() == c.getEnvironment()
                      && b.environment() == s.getEnvironment()
                      && java.util.Objects.equals(b.merchantAccount(), s.getMerchantAccount())
                      && java.util.Objects.equals(b.apiKeyReference(), s.getApiKeyReference())
                      && java.util.Objects.equals(
                          b.webhookKeyReference(), s.getWebhookKeyReference()),
                  "CREDENTIAL_ENVIRONMENT");
              return b;
            });
  }

  public Mono<Boolean> livePaymentEnabled(Selection selection) {
    return current()
        .flatMap(
            s -> {
              var c = checked(s);
              market(c, selection);
              if (c.getEnvironment() != CommercialEnvironment.COMMERCIAL_ENVIRONMENT_LIVE)
                return Mono.just(false);
              Mono<Void> ready =
                  selection.method() == CommercialMethod.COMMERCIAL_METHOD_PSP
                      ? credential(c).then()
                      : Mono.empty();
              return ready.then(
                  selection.currency() == CommercialCurrency.COMMERCIAL_CURRENCY_USD
                      ? observation(c).thenReturn(true)
                      : Mono.just(true));
            })
        .onErrorReturn(false);
  }

  public Mono<InvoiceTerms> invoice(
      DatabaseClient transaction, PlanVersion serverPlan, Selection selection) {
    return current()
        .flatMap(
            s -> {
              var c = checked(s);
              var m = market(c, selection);
              require(
                  serverPlan.getKind() == PlanVersion.KindValue.KIND_PAID
                      && serverPlan.getState() == PlanVersion.StateValue.STATE_PUBLISHED
                      && serverPlan.getPrice().getCurrency() == Money.CurrencyValue.CURRENCY_EUR
                      && serverPlan.getPrice().getExponent() == 2,
                  "CATALOG_PRICE");
              long base;
              try {
                require(
                    serverPlan.getPrice().getAmountMinor().matches("[1-9][0-9]{0,18}"),
                    "CATALOG_PRICE");
                base = Long.parseLong(serverPlan.getPrice().getAmountMinor());
              } catch (IllegalArgumentException e) {
                throw unavailable("CATALOG_PRICE");
              }
              Mono<Void> ready =
                  selection.method() == CommercialMethod.COMMERCIAL_METHOD_PSP
                      ? credential(c).then()
                      : Mono.empty();
              Mono<FxSnapshot> observation =
                  selection.currency() == CommercialCurrency.COMMERCIAL_CURRENCY_USD
                      ? quote(transaction, s, c, selection, base)
                      : Mono.empty();
              return ready.then(
                  observation
                      .map(q -> terms(s, c, m, selection, base, q))
                      .switchIfEmpty(
                          Mono.fromSupplier(() -> terms(s, c, m, selection, base, null))));
            });
  }

  private void validateQuote(MerchantConfiguration c, FxObservation q) {
    try {
      var now = clock.instant();
      var p = c.getFx();
      require(
          q.environment() == c.getEnvironment()
              && p.getSource().equals(q.source())
              && p.getSourceVersion().equals(q.sourceVersion()),
          "FX_SOURCE");
      require(
          q.rate() != null
              && q.rate().signum() > 0
              && q.rate().scale() <= 10
              && q.rate().precision() - q.rate().scale() <= 10,
          "FX_RATE");
      require(
          q.quotedAt() != null
              && q.validUntil() != null
              && !q.quotedAt().isAfter(now)
              && q.validUntil().isAfter(now)
              && q.validUntil().isAfter(q.quotedAt())
              && now.isBefore(q.quotedAt().plusSeconds(p.getMaxAgeSeconds())),
          "FX_EXPIRED");
      require(
          q.quotedAt().getNano() % 1000 == 0 && q.validUntil().getNano() % 1000 == 0,
          "FX_TIME_PRECISION");
    } catch (NullPointerException | IllegalArgumentException e) {
      throw unavailable("FX_SOURCE");
    }
  }

  private Mono<FxObservation> observation(MerchantConfiguration c) {
    return fx.quote(c.getEnvironment(), c.getFx())
        .onErrorMap(CommercialReadiness::dependency)
        .switchIfEmpty(Mono.error(unavailable("FX_SOURCE")))
        .map(
            q -> {
              validateQuote(c, q);
              return q;
            });
  }

  private Mono<FxSnapshot> quote(
      DatabaseClient transaction,
      MerchantConfigurationSnapshot profile,
      MerchantConfiguration c,
      Selection selection,
      long base) {
    return observation(c)
        .flatMap(
            q -> {
              validateQuote(c, q);
              var id = UUID.randomUUID();
              // All arithmetic and tax checks precede the immutable insert, even if caller fails to
              // wrap a transaction.
              terms(profile, c, market(c, selection), selection, base, new FxSnapshot(id, q));
              return transaction
                  .sql(
                      "INSERT INTO"
                          + " taska.fx_quotes(id,base_currency,quote_currency,rate,source,quoted_at,valid_until,version,environment,configuration_version)"
                          + " VALUES(:id,'EUR','USD',:rate,:source,:quoted,:until,:version,:environment,:configuration)")
                  .bind("id", id)
                  .bind("rate", q.rate())
                  .bind("source", q.source())
                  .bind("quoted", q.quotedAt().atOffset(ZoneOffset.UTC))
                  .bind("until", q.validUntil().atOffset(ZoneOffset.UTC))
                  .bind("version", q.sourceVersion())
                  .bind("environment", c.getEnvironment().name())
                  .bind("configuration", Long.parseLong(profile.getVersion()))
                  .fetch()
                  .rowsUpdated()
                  .thenReturn(new FxSnapshot(id, q));
            })
        .onErrorMap(
            e -> {
              if (e instanceof DomainException d
                  && d.getMessage().equals("POLICY_NOT_CONFIGURED")) {
                boolean eur =
                    c.getMarketsList().stream()
                        .anyMatch(
                            m ->
                                m.getCountry().equals(selection.country())
                                    && m.getBuyerType() == selection.buyerType()
                                    && m.getMethod() == selection.method()
                                    && m.getCurrency()
                                        == CommercialCurrency.COMMERCIAL_CURRENCY_EUR);
                var details = new java.util.HashMap<>(d.getDetails());
                if (eur) details.put("offeredCurrency", "EUR");
                return new DomainException(d.getStatus(), d.getMessage(), details);
              }
              return e;
            });
  }

  private static InvoiceTerms terms(
      MerchantConfigurationSnapshot s,
      MerchantConfiguration c,
      CommercialMarket m,
      Selection selection,
      long base,
      FxSnapshot fx) {
    require(
        selection.currency() != CommercialCurrency.COMMERCIAL_CURRENCY_USD || fx != null,
        "FX_SOURCE");
    long gross;
    try {
      gross =
          fx == null
              ? base
              : new BigDecimal(base)
                  .multiply(fx.observation().rate())
                  .setScale(0, RoundingMode.HALF_UP)
                  .longValueExact();
      require(gross > 0, "FX_RATE");
    } catch (ArithmeticException e) {
      throw unavailable("FX_RATE");
    }
    var tax =
        c.getTaxRulesList().stream()
            .filter(t -> t.getVersion().equals(m.getTaxRuleVersion()))
            .findFirst()
            .orElseThrow(() -> unavailable("TAX_RULE"));
    long net =
        new BigDecimal(gross)
            .divide(BigDecimal.ONE.add(new BigDecimal(tax.getRate())), 0, RoundingMode.HALF_UP)
            .longValueExact();
    return new InvoiceTerms(
        s.getVersion(),
        c.getEnvironment(),
        c.getSeller(),
        selection,
        base,
        gross,
        net,
        gross - net,
        tax.getVersion(),
        tax.getLabel(),
        tax.getRate(),
        m.getDocumentTemplateVersion(),
        m.getRefundPolicyVersion(),
        fx);
  }

  /** Validates the exact stored profile plus current enablement before invoking the actual PSP. */
  public <T> Mono<T> checkout(
      InvoiceTerms invoice,
      CommercialEnvironment actualEnvironment,
      Function<PaymentAuthorization, Mono<T>> provider) {
    return current()
        .flatMap(
            current -> {
              var latest = checked(current);
              require(
                  latest.getEnvironment() == actualEnvironment
                      && invoice.environment() == actualEnvironment,
                  "CREDENTIAL_ENVIRONMENT");
              market(latest, invoice.selection());
              return configurations
                  .version(invoice.configurationVersion())
                  .switchIfEmpty(Mono.error(unavailable("COMMERCIAL_PROFILE")))
                  .flatMap(
                      stored -> {
                        require(
                            stored.getVersion().equals(invoice.configurationVersion()),
                            "CONFIGURATION_VERSION");
                        var c = checked(stored);
                        market(c, invoice.selection());
                        require(
                            c.getEnvironment() == actualEnvironment
                                && invoice.selection().method()
                                    == CommercialMethod.COMMERCIAL_METHOD_PSP,
                            "CREDENTIAL_ENVIRONMENT");
                        require(
                            c.getStripe()
                                .getMerchantAccount()
                                .equals(latest.getStripe().getMerchantAccount()),
                            "MERCHANT_CHANGED");
                        return credential(c)
                            .flatMap(
                                b ->
                                    Mono.defer(
                                        () ->
                                            provider.apply(
                                                new PaymentAuthorization(
                                                    invoice.configurationVersion(),
                                                    b.environment(),
                                                    b.merchantAccount(),
                                                    b.apiKeyReference(),
                                                    b.webhookKeyReference(),
                                                    invoice.grossMinor(),
                                                    invoice.selection().currency()))));
                      });
            });
  }
}
