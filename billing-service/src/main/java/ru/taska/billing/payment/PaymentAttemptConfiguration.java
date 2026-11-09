package ru.taska.billing.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.billing.ingress.GrpcProviderInboxWorker;

/**
 * Explicit activation; mandatory dependencies fail startup without a fake/current-provider
 * fallback.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.billing.attempts.enabled", havingValue = "true")
public class PaymentAttemptConfiguration {
  @Bean
  PaymentAttemptCoordinator paymentAttemptCoordinator(
      PaymentAttemptStore attempts,
      BillingOperationStore operations,
      PaymentProviderRegistry providers,
      GrpcProviderInboxWorker worker) {
    return new PaymentAttemptCoordinator(attempts, providers, operations, worker::register);
  }
}
