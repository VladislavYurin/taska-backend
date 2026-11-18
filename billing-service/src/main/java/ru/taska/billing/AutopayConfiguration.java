package ru.taska.billing;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import ru.taska.billing.payment.PaymentProviderRegistry;
import ru.taska.exception.DomainStatus;

/** Без актуальных сервисов-владельцев и одобренного PSP согласие и новые попытки закрыты. */
@Configuration
public class AutopayConfiguration {
  @Bean
  @ConditionalOnMissingBean
  AutopayDependencies.Policy autopayPolicy() {
    return new ClosedPolicy();
  }

  static final class ClosedPolicy implements AutopayDependencies.Policy {
    @Override
    public Mono<AutopayDependencies.Grant> require(
        ru.taska.security.tenant.VerifiedTenantContext context,
        AutopayDependencies.Use use,
        java.util.UUID method) {
      return Mono.error(
          CatalogErrors.fail(DomainStatus.UNAVAILABLE, "AUTOPAY_POLICY_NOT_CONFIGURED"));
    }
  }

  @Bean
  AutopayStore autopayStore(
      SubscriptionStore subscriptions,
      AutopayDependencies.Policy policy,
      ObjectProvider<PaymentProviderRegistry> providers,
      ObjectProvider<PaymentSettlementDependencies.TenantAuthority> roots) {
    var root = roots.getIfAvailable();
    if (root == null)
      root =
          tenant ->
              Mono.error(CatalogErrors.fail(DomainStatus.UNAVAILABLE, "AUTOPAY_ROOT_UNAVAILABLE"));
    return new AutopayStore(
        subscriptions, policy, providers.getIfAvailable(), Clock.systemUTC(), root);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "taska.billing.autopay.enabled",
      havingValue = "true")
  AutopayCoordinator autopayCoordinator(
      AutopayStore store,
      ru.taska.billing.payment.PaymentAttemptStore attempts,
      ru.taska.billing.payment.BillingOperationStore operations,
      PaymentProviderRegistry providers,
      ru.taska.billing.ingress.GrpcProviderInboxWorker worker) {
    if (store.policy instanceof ClosedPolicy)
      throw new IllegalStateException("AUTOPAY_POLICY_NOT_CONFIGURED");
    return new AutopayCoordinator(store, attempts, operations, worker::register);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "taska.billing.autopay.scheduler.enabled",
      havingValue = "true")
  AutopayScheduler autopayScheduler(
      AutopayScheduler.WorkSource jobs,
      ru.taska.api.b2b.v1.ReactorAutopayWorkerServiceGrpc.ReactorAutopayWorkerServiceStub stub,
      ru.taska.security.tenant.RpcContextSecurity security,
      @org.springframework.beans.factory.annotation.Value("${taska.billing.worker.generation:1}")
          String generation,
      @org.springframework.beans.factory.annotation.Value("${taska.billing.autopay.batch-size:10}")
          int size,
      org.springframework.core.env.Environment environment) {
    if (!environment.getProperty("taska.billing.autopay.enabled", Boolean.class, false))
      throw new IllegalStateException("Autopay dispatcher must be enabled before scheduler");
    var clock = Clock.systemUTC();
    return new AutopayScheduler(
        jobs, new GrpcAutopayWorker(stub, security, clock, generation), clock, size);
  }
}
