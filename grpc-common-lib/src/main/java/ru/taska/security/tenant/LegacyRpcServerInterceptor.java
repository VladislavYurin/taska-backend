package ru.taska.security.tenant;

import io.grpc.*;
import java.util.Map;

/** Enforces the registered legacy boundary before any handler/domain/outbox execution. */
public final class LegacyRpcServerInterceptor implements ServerInterceptor {
  private final Map<String, RpcContextSecurity.Policy> policies;
  private final ServerInterceptor guarded;
  private final LegacyRpcDatabaseBoundary database;

  public LegacyRpcServerInterceptor(
      String audience, RpcContextSecurity security, String environment) {
    this(audience, security, environment, null);
  }

  public LegacyRpcServerInterceptor(
      String audience,
      RpcContextSecurity security,
      String environment,
      LegacyRpcDatabaseBoundary database) {
    this.database = database;
    policies = RpcRoutePolicies.policies(audience);
    guarded =
        security == null ? null : new TenantServerInterceptor(security, policies, environment);
  }

  @Override
  public <Q, S> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, S> call, Metadata headers, ServerCallHandler<Q, S> next) {
    if (!policies.containsKey(call.getMethodDescriptor().getFullMethodName()))
      return next.startCall(call, headers);
    if (guarded != null)
      return guarded.interceptCall(
          call,
          headers,
          (signed, h) -> {
            if (database == null) return next.startCall(signed, h);
            var scoped =
                io.grpc.Context.current().withValue(LegacyRpcDatabaseBoundary.KEY, database);
            return Contexts.interceptCall(scoped, signed, h, next);
          });
    call.close(
        Status.UNAVAILABLE.withDescription("Trusted RPC provider unavailable"), new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
