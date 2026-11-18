package ru.taska.security.oauth;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;
import ru.taska.api.b2b.v1.TenantOAuthGrantServiceGrpc;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;

/**
 * Approval needs a verified tenant context. Edge token authentication produces that context, so
 * it has no RequestContext; its callers are restricted by service identity (TAS-317).
 */
@Component
@GlobalServerInterceptor
public final class TenantOAuthGrantInterceptor implements ServerInterceptor {
  private static final String APPROVE =
      TenantOAuthGrantServiceGrpc.getApproveTenantOAuthGrantMethod().getFullMethodName();
  private final ServerInterceptor guarded;

  public TenantOAuthGrantInterceptor(
      ObjectProvider<RpcContextSecurity> provider,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    RpcContextSecurity security = provider.getIfAvailable();
    guarded =
        security == null
            ? new ServerInterceptor() {
              @Override
              public <Q, S> ServerCall.Listener<Q> interceptCall(
                  ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
                call.close(
                    Status.UNAVAILABLE.withDescription("Tenant OAuth trust unavailable"),
                    new Metadata());
                return new ServerCall.Listener<>() {};
              }
            }
            : new TenantServerInterceptor(
                security, Map.of(APPROVE, new RpcContextSecurity.Policy(true, true)), environment);
  }

  @Override
  public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    if (APPROVE.equals(call.getMethodDescriptor().getFullMethodName()))
      return guarded.interceptCall(call, headers, next);
    return next.startCall(call, headers);
  }
}
