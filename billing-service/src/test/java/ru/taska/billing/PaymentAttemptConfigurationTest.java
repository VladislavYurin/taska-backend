package ru.taska.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import ru.taska.billing.ingress.GrpcProviderInboxWorker;
import ru.taska.billing.payment.*;

class PaymentAttemptConfigurationTest {
  final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(PaymentAttemptConfiguration.class);

  @Test
  void disabledByDefaultHasNoDependenciesOrCoordinator() {
    context.run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(PaymentAttemptCoordinator.class));
  }

  @Test
  void enabledWithoutProvisioningFailsClosed() {
    context
        .withPropertyValues("taska.billing.attempts.enabled=true")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void enabledResolvesRegistryAndWorkerRegisteredAfterConfiguration() {
    context
        .withPropertyValues("taska.billing.attempts.enabled=true")
        .withUserConfiguration(LaterDependencies.class)
        .run(c -> assertThat(c).hasNotFailed().hasSingleBean(PaymentAttemptCoordinator.class));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class LaterDependencies {
    @Bean
    PaymentAttemptStore attempts() {
      return mock(PaymentAttemptStore.class);
    }

    @Bean
    BillingOperationStore operations() {
      return mock(BillingOperationStore.class);
    }

    @Bean
    PaymentProviderRegistry providers() {
      return new PaymentProviderRegistry(Map.of());
    }

    @Bean
    GrpcProviderInboxWorker worker() {
      return mock(GrpcProviderInboxWorker.class);
    }
  }
}
