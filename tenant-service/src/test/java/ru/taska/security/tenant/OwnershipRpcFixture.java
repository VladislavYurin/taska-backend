package ru.taska.security.tenant;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.TrustedTenantContext;

/** Только компонентные тесты: trusted fixture перед настоящим gRPC handler, без имитации wire. */
public final class OwnershipRpcFixture {
  private OwnershipRpcFixture() {}

  public static <T> Mono<T> call(TrustedTenantContext claims, Supplier<Mono<T>> request) {
    return RegistryTestContext.withClaims(
        claims,
        VerifiedTenantContext.current()
            .flatMap(
                verified -> {
                  var transport =
                      io.grpc.Context.current().withValue(RpcContextBridge.INBOUND, verified);
                  var previous = transport.attach();
                  try {
                    return request.get();
                  } finally {
                    transport.detach(previous);
                  }
                }));
  }
}
