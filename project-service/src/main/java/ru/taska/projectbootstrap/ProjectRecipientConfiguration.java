package ru.taska.projectbootstrap;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.GlobalServerInterceptor;
import ru.taska.api.b2b.v1.ProjectRecipientServiceGrpc;
import ru.taska.projectstorage.ProjectRecipientRpc;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;

/** Новый internal RPC не использует legacy fallback и требует message-bound контекст. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(ProjectRecipientRpc.class)
public class ProjectRecipientConfiguration {
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor projectRecipientGuard(
      RpcContextSecurity security, @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    String rpc = ProjectRecipientServiceGrpc.getCheckMethod().getFullMethodName();
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
