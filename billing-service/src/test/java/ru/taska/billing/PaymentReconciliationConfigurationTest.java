package ru.taska.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import ru.taska.api.b2b.v1.ReactorPaymentReconciliationServiceGrpc;
import ru.taska.billing.ingress.*;
import ru.taska.billing.reconciliation.*;

class PaymentReconciliationConfigurationTest {
  final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(PaymentReconciliationConfiguration.class);

  @Test
  void disabledByDefaultDoesNotRequireAdaptersOrStartPolling() {
    context.run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(PaymentReconciliationPump.class));
  }

  @Test
  void enabledWithoutRealDependenciesFailsClosed() {
    context
        .withPropertyValues("taska.billing.reconciliation.enabled=true")
        .run(c -> assertThat(c).hasFailed());
  }

  @Test
  void explicitActivationResolvesLaterProvisionedDependencies() {
    context
        .withPropertyValues("taska.billing.reconciliation.enabled=true")
        .withUserConfiguration(
            PaymentAttemptConfigurationTest.LaterDependencies.class, Fixtures.class)
        .run(
            c ->
                assertThat(c)
                    .hasNotFailed()
                    .hasSingleBean(PaymentReconciliation.class)
                    .hasSingleBean(PaymentReconciliationPump.class));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    ConnectionFactory connectionFactory() {
      var factory = mock(ConnectionFactory.class);
      org.mockito.Mockito.when(factory.getMetadata()).thenReturn(() -> "PostgreSQL");
      return factory;
    }

    @Bean
    org.springframework.beans.factory.InitializingBean safeRuntimeRole() {
      return () -> {};
    }

    @Bean
    ProviderInboxStore inbox() {
      return mock(ProviderInboxStore.class);
    }

    @Bean
    ProviderWebhookRegistry profiles() {
      return mock(ProviderWebhookRegistry.class);
    }

    @Bean
    SimpleMeterRegistry metrics() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ReactorPaymentReconciliationServiceGrpc.ReactorPaymentReconciliationServiceStub
        reconciliationStub() {
      return mock(
          ReactorPaymentReconciliationServiceGrpc.ReactorPaymentReconciliationServiceStub.class);
    }
  }
}
