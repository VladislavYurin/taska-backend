package ru.taska.security.pat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import ru.taska.capability.CapabilityEvaluator;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.security.tenant.RpcTrustBackend;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TenantPersonalTokenStartupTest {
  @Test void enabledModuleHasProductionSourceWithoutExternalSourceBean() {
    new ApplicationContextRunner().withUserConfiguration(TenantPersonalTokenConfiguration.class, TenantIntegrationFactsConfiguration.class)
        .withPropertyValues("taska.b2b.tenant-tokens.enabled=true",
            "taska.b2b.sessions.refresh-receipt-key=" + java.util.Base64.getEncoder().encodeToString(new byte[32]))
        .withBean(DatabaseClient.class, () -> mock(DatabaseClient.class))
        .withBean("transactionalOperator", TransactionalOperator.class, () -> mock(TransactionalOperator.class))
        .withBean(PersonalTokenProperties.class, PersonalTokenProperties::new)
        .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
        .withBean(ManagedDomainReads.class, () -> mock(ManagedDomainReads.class))
        .withBean(RpcTrustBackend.class, () -> mock(RpcTrustBackend.class))
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(CapabilityEvaluator.Source.class)
              .hasSingleBean(TenantPersonalTokenAdmission.class).hasSingleBean(TenantPersonalTokenStore.class);
          assertThat(context.getBean(CapabilityEvaluator.Source.class)).isInstanceOf(TenantPersonalTokenFacts.class);
        });
  }
}
