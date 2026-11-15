package ru.taska.issuebootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.issuestorage.PrimaryIssueAccess;
import ru.taska.issuestorage.TenantIssueAccess;
import ru.taska.issuestorage.TenantIssueApi;
import ru.taska.issuestorage.TenantIssueGrpc;
import ru.taska.issuestorage.TenantIssueStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(TenantIssueGrpc.class)
public class IssueApiConfiguration {
  @Bean
  TenantDecisionFacts issueDecisionFacts(
      ConnectionFactory factory, ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantDecisionFacts(
        new TenantTransactionExecutor(factory), reads, identity, Map.of(), Clock.systemUTC());
  }

  @Bean
  PrimaryIssueAccess primaryIssueAccess(
      TenantDecisionFacts facts, ManagedDomainReads reads, RpcContextSecurity security) {
    return new PrimaryIssueAccess(facts, reads, security);
  }

  @Bean
  ru.taska.issuestorage.TenantIssueQueries tenantIssueQueries(
      TenantIssueStore store, TenantIssueAccess access) {
    return new ru.taska.issuestorage.TenantIssueQueries(store, access);
  }

  @Bean
  TenantIssueApi tenantIssueApi(TenantIssueStore store, TenantIssueAccess access) {
    return new TenantIssueApi(store, access);
  }
}
