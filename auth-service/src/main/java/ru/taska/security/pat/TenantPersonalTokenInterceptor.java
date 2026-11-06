package ru.taska.security.pat;

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
import ru.taska.api.b2b.v1.TenantPersonalTokenServiceGrpc;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;

/**
 * Issuance needs a verified tenant context. Edge authentication produces that context, so it has
 * no RequestContext; its callers are restricted by service identity (TAS-317), not by this guard.
 */
@Component
@GlobalServerInterceptor
public final class TenantPersonalTokenInterceptor implements ServerInterceptor {
  private static final String CREATE =
      TenantPersonalTokenServiceGrpc.getCreateTenantPersonalTokenMethod().getFullMethodName();
  private final ServerInterceptor guarded;

  public TenantPersonalTokenInterceptor(
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
                    Status.UNAVAILABLE.withDescription("Tenant token trust unavailable"),
                    new Metadata());
                return new ServerCall.Listener<>() {};
              }
            }
            : new TenantServerInterceptor(
                security, Map.of(CREATE, new RpcContextSecurity.Policy(true, true)), environment);
  }

  @Override
  public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    if (CREATE.equals(call.getMethodDescriptor().getFullMethodName()))
      return guarded.interceptCall(call, headers, next);
    return next.startCall(call, headers);
  }
}
