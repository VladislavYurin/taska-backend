package ru.taska.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import ru.taska.billing.ingress.GrpcProviderInboxWorker;
import ru.taska.billing.payment.BillingOperationStore;
import ru.taska.billing.payment.PaymentAttemptStore;
import ru.taska.billing.payment.PaymentProviderRegistry;

class AutopayConfigurationTest {
  ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(AutopayConfiguration.class)
        .withBean(SubscriptionStore.class, () -> mock(SubscriptionStore.class));
  }

  ApplicationContextRunner provisioned() {
    return context()
        .withBean(PaymentAttemptStore.class, () -> mock(PaymentAttemptStore.class))
        .withBean(BillingOperationStore.class, () -> mock(BillingOperationStore.class))
        .withBean(PaymentProviderRegistry.class, () -> new PaymentProviderRegistry(Map.of()))
        .withBean(GrpcProviderInboxWorker.class, () -> mock(GrpcProviderInboxWorker.class));
  }

  @Test
  void defaultDoesNotEnableSchedulerOrProviderEffects() {
    context()
        .run(
            c ->
                assertThat(c)
                    .hasNotFailed()
                    .hasSingleBean(AutopayStore.class)
                    .doesNotHaveBean(AutopayCoordinator.class)
                    .doesNotHaveBean(AutopayScheduler.class));
  }

  @Test
  void enabledWithoutProvisioningFailsStartup() {
    context()
        .withPropertyValues("taska.billing.autopay.enabled=true")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void enabledWithoutCurrentAuthorityFailsEvenWhenProviderExists() {
    provisioned()
        .withPropertyValues("taska.billing.autopay.enabled=true")
        .run(
            c ->
                assertThat(c)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("AUTOPAY_POLICY_NOT_CONFIGURED"));
  }

  @Test
  void explicitAuthorityAndProviderActivateOnlyTheDispatcher() {
    provisioned()
        .withPropertyValues("taska.billing.autopay.enabled=true")
        .withBean(
            AutopayDependencies.Policy.class,
            () -> (context, use, method) -> Mono.error(new IllegalStateException("fixture")))
        .run(
            c ->
                assertThat(c)
                    .hasNotFailed()
                    .hasSingleBean(AutopayCoordinator.class)
                    .doesNotHaveBean(AutopayScheduler.class));
  }
}
