package ru.taska.billing.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.api.b2b.v1.ReactorPaymentReconciliationServiceGrpc;
import ru.taska.billing.ingress.*;
import ru.taska.billing.payment.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "taska.billing.reconciliation.enabled", havingValue = "true")
public class PaymentReconciliationConfiguration {
  @Bean
  @DependsOn("safeRuntimeRole")
  PaymentReconciliationStore reconciliationStore(
      ConnectionFactory factory, @Value("${taska.billing.reconciliation.max-attempts:8}") int max) {
    return new PaymentReconciliationStore(DatabaseClient.create(factory), Clock.systemUTC(), max);
  }

  @Bean
  PaymentReconciliation paymentReconciliation(
      PaymentReconciliationStore jobs,
      PaymentAttemptStore attempts,
      BillingOperationStore operations,
      PaymentProviderRegistry providers,
      ProviderWebhookRegistry profiles,
      ProviderInboxStore inbox,
      GrpcProviderInboxWorker worker) {
    return new PaymentReconciliation(
        jobs, attempts, operations, providers, profiles, inbox, worker);
  }

  @Bean
  PaymentReconciliationPump reconciliationPump(
      PaymentReconciliationStore jobs,
      GrpcProviderInboxWorker worker,
      ReactorPaymentReconciliationServiceGrpc.ReactorPaymentReconciliationServiceStub stub,
      MeterRegistry metrics,
      @Value("${taska.billing.reconciliation.batch-size:10}") int size) {
    return new PaymentReconciliationPump(jobs, j -> worker.reconcile(stub, j), metrics, size);
  }
}
