package ru.taska.security.directory;

import io.grpc.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import ru.taska.api.b2b.v1.ProjectDirectoryServiceGrpc;
import ru.taska.security.tenant.*;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
public class ProjectDirectorySecurity {
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor projectDirectoryGuard(
      RpcContextSecurity security, @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    String rpc = ProjectDirectoryServiceGrpc.getProfilesMethod().getFullMethodName();
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
