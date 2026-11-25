package ru.taska.workflowbootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import ru.taska.api.project.v1.*;
import ru.taska.capability.*;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.*;
import ru.taska.workflowstorage.*;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(TenantWorkflowGrpc.class)
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
