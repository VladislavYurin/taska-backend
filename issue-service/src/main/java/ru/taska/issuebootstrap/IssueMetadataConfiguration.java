package ru.taska.issuebootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.config.props.IssueProperties;
import ru.taska.issuestorage.IssueRecipientRpc;
import ru.taska.issuestorage.ProjectRecipientClient;
import ru.taska.issuestorage.TenantIssueAccess;
import ru.taska.issuestorage.TenantIssueMetadataApi;
import ru.taska.issuestorage.TenantIssueMetadataStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import tools.jackson.databind.ObjectMapper;

/** Нативные метки/подписки подключаются только с настоящими primary clients. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(IssueRecipientRpc.class)
public class IssueMetadataConfiguration {
  @Bean
  TenantIssueMetadataStore issueMetadataStore(ConnectionFactory factory, ObjectMapper json) {
    return new TenantIssueMetadataStore(
        new TenantTransactionExecutor(factory), json, Clock.systemUTC());
  }

  @Bean
  TenantIssueMetadataApi issueMetadataApi(
      TenantIssueMetadataStore store,
      TenantIssueAccess access,
      ManagedDomainReads reads,
      IssueProperties properties) {
    return new TenantIssueMetadataApi(
        store,
        access,
        new ProjectRecipientClient(reads),
        properties.pagination().defaultPageSize(),
        properties.pagination().maxPageSize());
  }
}
