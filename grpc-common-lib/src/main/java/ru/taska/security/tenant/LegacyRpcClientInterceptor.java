package ru.taska.security.tenant;

import io.grpc.*;

/**
 * Public/auth bootstrap and existing private B2B methods keep their separate explicit boundaries.
 */
public final class LegacyRpcClientInterceptor implements ClientInterceptor {
  private final TenantClientInterceptor guarded;

  public LegacyRpcClientInterceptor(RpcContextSecurity security) {
    guarded =
        new TenantClientInterceptor(
            security,
            RpcRoutePolicies.all().entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, e -> e.getValue().policy())));
  }

  @Override
  public <Q, S> ClientCall<Q, S> interceptCall(
      MethodDescriptor<Q, S> method, CallOptions options, Channel next) {
    return RpcRoutePolicies.all().containsKey(method.getFullMethodName())
        ? guarded.interceptCall(method, options, next)
        : next.newCall(method, options);
  }
}
