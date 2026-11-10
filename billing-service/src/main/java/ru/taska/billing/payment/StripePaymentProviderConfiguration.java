package ru.taska.billing.payment;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/** Opt-in sandbox wiring; absent configuration leaves payment attempts unavailable. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.billing.stripe.enabled", havingValue = "true")
public class StripePaymentProviderConfiguration {
  @Bean
  StripePaymentProvider stripePaymentProvider(
      WebClient.Builder webClient,
      ObjectMapper json,
      @Value("${TASKA_BILLING_STRIPE_API_KEY}") String apiKey,
      @Value("${TASKA_BILLING_STRIPE_WEBHOOK_SECRET}") String webhookSecret,
      @Value("${TASKA_BILLING_STRIPE_SUCCESS_URL}") URI successUrl,
      @Value("${TASKA_BILLING_STRIPE_CANCEL_URL}") URI cancelUrl,
      @Value("${TASKA_BILLING_STRIPE_ENDPOINT:https://api.stripe.com}") URI endpoint,
      @Value("${TASKA_BILLING_STRIPE_WEBHOOK_TOLERANCE_SECONDS:300}") long tolerance) {
    return new StripePaymentProvider(
        webClient,
        new StripePaymentProvider.Configuration(
            endpoint, apiKey, webhookSecret, successUrl, cancelUrl, tolerance),
        json,
        Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean(PaymentProviderRegistry.class)
  PaymentProviderRegistry stripePaymentProviderRegistry(
      StripePaymentProvider provider,
      @Value("${TASKA_BILLING_STRIPE_MERCHANT_ACCOUNT}") String merchantAccount,
      @Value("${TASKA_BILLING_STRIPE_PROFILE_VERSION}") String profileVersion) {
    var merchant = new PaymentProvider.Merchant(merchantAccount, PaymentProvider.Environment.TEST);
    return new PaymentProviderRegistry(
        Map.of(
            new PaymentProviderRegistry.Identity("stripe", merchant),
            new PaymentProviderRegistry.Registration(provider, Set.of(profileVersion))));
  }
}
