package ru.taska.security.pat;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.security.tenant.RpcTrustBackend;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${taska.b2b.tenant-tokens.enabled:false} || ${taska.b2b.tenant-oauth.enabled:false}")
public class TenantIntegrationFactsConfiguration {
  @Bean
  TenantPersonalTokenFacts tenantIntegrationFacts(ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantPersonalTokenFacts(reads, identity, Clock.systemUTC());
  }
}
