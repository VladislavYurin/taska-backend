package ru.taska.tenant;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.ManagedServiceContexts;
import ru.taska.security.tenant.OwnershipTransferClient;
import ru.taska.security.tenant.OwnershipTransferWorker;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;

/** Передача включается только вместе с проверяемыми первичными источниками. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class OwnershipTransferConfiguration {
  @Bean
  OwnershipTransferClient ownershipTransferClient(
      ManagedDomainReads reads, ManagedServiceContexts contexts, RpcContextSecurity security) {
    return new OwnershipTransferClient(reads, contexts, security);
  }

  @Bean
  OwnershipTransferAuthority ownershipTransferAuthority(
      RpcTrustBackend identities, ManagedDomainReads reads) {
    return new OwnershipTransferAuthority(identities, reads, Clock.systemUTC());
  }

  @Bean
  TenantOwnershipTransfers tenantOwnershipTransfers(
      TenantTransactionExecutor tx,
      OwnershipTransferAuthority authority,
      OwnershipTransferClient client) {
    return new TenantOwnershipTransfers(tx, authority, client, Clock.systemUTC());
  }

  @Bean
  OwnershipTransferWorker ownershipTransferWorker(
      org.springframework.r2dbc.core.DatabaseClient db,
      ManagedServiceContexts contexts,
      TenantOwnershipTransfers transfers) {
    return new OwnershipTransferWorker(db, contexts, transfers, Clock.systemUTC());
  }
}
