package ru.taska.issuebootstrap;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.GlobalServerInterceptor;
import ru.taska.api.b2b.v1.IssueRecipientServiceGrpc;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.config.props.IssueProperties;
import ru.taska.issuestorage.IssueRecipientRpc;
import ru.taska.issuestorage.ProjectRecipientClient;
import ru.taska.issuestorage.TenantIssueAccess;
import ru.taska.issuestorage.TenantIssueMetadataApi;
import ru.taska.issuestorage.TenantIssueMetadataStore;
import ru.taska.r2dbc.TenantTransactionExecutor;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;
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

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor issueRecipientGuard(
      RpcContextSecurity security, @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    String rpc = IssueRecipientServiceGrpc.getResolveMethod().getFullMethodName();
    var guarded =
        new TenantServerInterceptor(
            security, Map.of(rpc, new RpcContextSecurity.Policy(true, false)), environment);
    return new ServerInterceptor() {
      public <Q, S> ServerCall.Listener<Q> interceptCall(
          ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
        return call.getMethodDescriptor().getFullMethodName().equals(rpc)
            ? guarded.interceptCall(call, headers, next)
            : next.startCall(call, headers);
      }
    };
  }
}
