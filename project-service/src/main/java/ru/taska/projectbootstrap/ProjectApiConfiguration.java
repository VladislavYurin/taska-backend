package ru.taska.projectbootstrap;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import ru.taska.api.b2b.v1.ProjectInitializationServiceGrpc;
import ru.taska.api.b2b.v1.ProjectQuotaOwnerServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.mapper.ProjectMapper;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.projectstorage.ProjectCreation;
import ru.taska.projectstorage.ProjectInitializationGrpc;
import ru.taska.projectstorage.ProjectOwnerPolicy;
import ru.taska.projectstorage.ProjectPrimaryClients;
import ru.taska.projectstorage.ProjectQuotaOwner;
import ru.taska.projectstorage.ProjectQuotaOwnerGrpcService;
import ru.taska.projectstorage.TenantProjectApi;
import ru.taska.projectstorage.TenantProjectGrpc;
import ru.taska.projectstorage.TenantProjectStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.RpcTrustBackend;
import tools.jackson.databind.ObjectMapper;

@org.springframework.scheduling.annotation.EnableScheduling
@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import({
  TenantProjectGrpc.class,
  ru.taska.projectstorage.ProjectDeletionGrpc.class,
  ProjectInitializationGrpc.class,
  ProjectQuotaOwnerGrpcService.class
})
public class ProjectApiConfiguration {
  public static Map<String, TenantDecisionFacts.Binding> bindings() {
    var b = new HashMap<String, TenantDecisionFacts.Binding>();
    b.put(
        "projectQuotaInspect",
        new TenantDecisionFacts.Binding(
            ProjectQuotaOwnerServiceGrpc.getInspectMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "projectQuotaCancel",
        new TenantDecisionFacts.Binding(
            ProjectQuotaOwnerServiceGrpc.getCancelMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "projectQuotaOpen",
        new TenantDecisionFacts.Binding(
            ProjectQuotaOwnerServiceGrpc.getOpenMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "projectQuotaInventory",
        new TenantDecisionFacts.Binding(
            ProjectQuotaOwnerServiceGrpc.getInventoryMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "describeProjectInitialization",
        new TenantDecisionFacts.Binding(
            ProjectInitializationServiceGrpc.getDescribeMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, false)));
    b.put(
        "recoverProjectCreation",
        new TenantDecisionFacts.Binding(
            ProjectInitializationServiceGrpc.getRecoverMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(true, true)));
    return Map.copyOf(b);
  }

  @Bean
  TenantDecisionFacts projectFacts(
      ConnectionFactory factory, ManagedDomainReads reads, RpcTrustBackend identity) {
    return new TenantDecisionFacts(
        new TenantTransactionExecutor(factory), reads, identity, bindings(), Clock.systemUTC());
  }

  @Bean
  ProjectPrimaryClients projectPrimaryClients(
      ManagedDomainReads reads, RpcContextSecurity security) {
    return new ProjectPrimaryClients(reads, security);
  }

  @Bean
  ProjectMemberMapper projectMemberMapper() {
    return org.mapstruct.factory.Mappers.getMapper(ProjectMemberMapper.class);
  }

  @Bean
  ProjectMapper projectMapper() {
    return org.mapstruct.factory.Mappers.getMapper(ProjectMapper.class);
  }

  @Bean
  ProjectCreation projectCreation(
      TenantProjectStore store,
      TenantDecisionFacts facts,
      ru.taska.quota.ProjectQuotaProtocol.Authority root,
      ProjectPrimaryClients clients,
      ProjectMapper mapper,
      ObjectMapper json,
      RpcContextSecurity security) {
    return new ProjectCreation(
        store, facts, root, clients, mapper, json, Clock.systemUTC(), security);
  }

  @Bean
  TenantProjectApi tenantProjectApi(
      TenantProjectStore store,
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      ProjectMapper mapper,
      ProjectPrimaryClients clients,
      ObjectMapper json) {
    return new TenantProjectApi(store, facts, reads, mapper, clients, json);
  }

  @Bean
  ru.taska.projectstorage.ProjectDeletions projectDeletions(
      TenantProjectStore store,
      TenantProjectApi api,
      ru.taska.quota.ProjectQuotaProtocol.Authority root,
      ProjectQuotaOwner owner,
      ManagedDomainReads reads,
      RpcContextSecurity security) {
    return new ru.taska.projectstorage.ProjectDeletions(
        store,
        api,
        new ru.taska.projectstorage.ProjectDeletionOwners(store, root, owner, reads, security));
  }

  @Bean
  @Primary
  ProjectOwnerPolicy nativeProjectOwnerPolicy(
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      RpcContextSecurity security,
      TenantProjectStore store) {
    return new ProjectOwnerPolicy(facts, reads, security, store);
  }
}
