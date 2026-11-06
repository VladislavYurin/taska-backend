package ru.taska.billing.ingress;

import io.r2dbc.spi.ConnectionFactory;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import ru.taska.api.b2b.v1.ReactorPaymentSettlementServiceGrpc;
import ru.taska.api.b2b.v1.ReactorProviderInboxServiceGrpc;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnProperty(name = "taska.billing.ingress.enabled", havingValue = "true")
public class ProviderInboxConfiguration {
  @Bean
  InboxCipher inboxCipher(
      @Value("${taska.billing.ingress.payload-key-id:}") String active,
      @Value("${taska.billing.ingress.payload-keys:}") String encoded) {
    try {
      Map<?, ?> values = JsonMapper.builder().build().readValue(encoded, Map.class);
      if (values.isEmpty() || values.size() > 32) throw new IllegalArgumentException();
      var keys = new HashMap<String, byte[]>();
      values.forEach((id, key) -> keys.put((String) id, Base64.getDecoder().decode((String) key)));
      return new InboxCipher(active, keys, new SecureRandom());
    } catch (Exception e) {
      throw new IllegalStateException(
          "TASKA_BILLING_INBOX_PAYLOAD_KEYS and active key id must be provisioned");
    }
  }

  @Bean
  @DependsOn("safeRuntimeRole")
  ProviderInboxStore providerInboxStore(ConnectionFactory factory, InboxCipher cipher) {
    return new ProviderInboxStore(
        DatabaseClient.create(factory),
        TransactionalOperator.create(new R2dbcTransactionManager(factory)),
        new TenantTransactionExecutor(factory),
        cipher,
        Clock.systemUTC());
  }

  @Bean
  org.springframework.beans.factory.InitializingBean retainedInboxPayloadKeys(
      ProviderInboxStore store) {
    return () -> store.verifyPayloadKeys().block(java.time.Duration.ofSeconds(10));
  }

  @Bean
  GrpcProviderInboxWorker providerInboxWorker(
      ReactorProviderInboxServiceGrpc.ReactorProviderInboxServiceStub inbox,
      ReactorPaymentSettlementServiceGrpc.ReactorPaymentSettlementServiceStub settlement,
      RpcContextSecurity trust,
      @Value("${taska.billing.ingress.service-generation:}") String generation) {
    if (generation == null
        || generation.isBlank()
        || generation.length() > 100
        || generation.chars().anyMatch(Character::isISOControl))
      throw new IllegalStateException("TASKA_BILLING_INBOX_SERVICE_GENERATION must be provisioned");
    return new GrpcProviderInboxWorker(inbox, settlement, trust, Clock.systemUTC(), generation);
  }

  @Bean
  ProviderInbox providerInbox(
      ProviderInboxStore store,
      ProviderWebhookRegistry registry,
      GrpcProviderInboxWorker worker,
      @Value("${taska.billing.ingress.max-body-bytes:65536}") int maxBody,
      @Value("${taska.billing.ingress.max-deliveries-per-minute:120}") int perMinute) {
    return new ProviderInbox(store, registry, worker, maxBody, perMinute);
  }
}
