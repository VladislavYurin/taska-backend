package ru.taska.security.session;

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
import ru.taska.api.b2b.v1.AuthSessionServiceGrpc;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.TenantServerInterceptor;

/** Only tenant-session commands need a verified delegated context. */
@Component
@GlobalServerInterceptor
public final class AuthSessionInterceptor implements ServerInterceptor {
  private final ServerInterceptor guarded;

  public AuthSessionInterceptor(ObjectProvider<RpcContextSecurity> provider,
      @Value("${DEPLOY_ENVIRONMENT:local}") String environment) {
    RpcContextSecurity security = provider.getIfAvailable();
    guarded = security == null ? new ServerInterceptor() {
      @Override
      public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call,
          Metadata headers, ServerCallHandler<Q, S> next) {
        call.close(Status.UNAVAILABLE.withDescription("Auth session trust unavailable"),
            new Metadata());
        return new ServerCall.Listener<>() {};
      }
    } : new TenantServerInterceptor(security, Map.of(
        AuthSessionServiceGrpc.getCreateTenantSessionMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(false, true),
        AuthSessionServiceGrpc.getRevokeTenantSessionMethod().getFullMethodName(),
            new RpcContextSecurity.Policy(false, true)), environment);
  }

  @Override
  public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call,
      Metadata headers, ServerCallHandler<Q, S> next) {
    String name = call.getMethodDescriptor().getFullMethodName();
    if (name.equals(AuthSessionServiceGrpc.getCreateTenantSessionMethod().getFullMethodName())
        || name.equals(AuthSessionServiceGrpc.getRevokeTenantSessionMethod().getFullMethodName()))
      return guarded.interceptCall(call, headers, next);
    return next.startCall(call, headers);
  }
}
