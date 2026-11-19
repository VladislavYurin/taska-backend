package ru.taska.billing;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.OwnershipTransferClient;
import ru.taska.security.tenant.RpcContextSecurity;

/** Финансовые эффекты закрыты без текущего Root receipt и service authority. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class OwnershipTransferConfiguration {
  @Bean
  OwnershipTransferBilling ownershipTransferBilling(
      io.r2dbc.spi.ConnectionFactory factory,
      SubscriptionStore subscriptions,
      EntitlementStore entitlements,
      ManagedDomainReads reads,
      ManagedServiceContexts contexts,
      RpcContextSecurity security) {
    var roots = new OwnershipTransferClient(reads, contexts, security);
    return new OwnershipTransferBilling(
        new TenantTransactionExecutor(factory),
        subscriptions,
        entitlements,
        roots::inspect,
        Clock.systemUTC());
  }
}
