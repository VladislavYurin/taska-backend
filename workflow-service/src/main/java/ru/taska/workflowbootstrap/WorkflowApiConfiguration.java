package ru.taska.workflowbootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ListProjectIssueTypesResponse;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.ProjectAccessClient;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;
import ru.taska.workflowstorage.TenantWorkflowApi;
import ru.taska.workflowstorage.TenantWorkflowGrpc;
import ru.taska.workflowstorage.TenantWorkflowStore;
import ru.taska.workflowstorage.WorkflowProjectAccess;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import({TenantWorkflowGrpc.class, ru.taska.workflowstorage.WorkflowProjectCleanup.class})
public class WorkflowApiConfiguration {
  @Bean
  TenantDecisionFacts workflowFacts(
      ConnectionFactory factory, ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantDecisionFacts(
        new TenantTransactionExecutor(factory), reads, identity, Map.of(), Clock.systemUTC());
  }

  @Bean
  WorkflowProjectAccess workflowProjectAccess(
      ManagedDomainReads reads, RpcContextSecurity security) {
    var client = new ProjectAccessClient(reads, security, "workflow-service");
    return new WorkflowProjectAccess() {
      public reactor.core.publisher.Mono<CheckProjectMemberRoleResponse> check(java.util.UUID id) {
        return client.check(id);
      }

      public reactor.core.publisher.Mono<ListProjectIssueTypesResponse> types(java.util.UUID id) {
        return client.types(id);
      }
    };
  }

  @Bean
  TenantWorkflowApi tenantWorkflowApi(
      TenantWorkflowStore store,
      TenantDecisionFacts facts,
      WorkflowProjectAccess projects,
      ObjectMapper json) {
    return new TenantWorkflowApi(store, facts, projects, json);
  }
}
