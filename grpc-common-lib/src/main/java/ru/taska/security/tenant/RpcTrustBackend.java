package ru.taska.security.tenant;

import java.time.Instant;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.RequestContext;

/** Auth-primary implementation; no credential secrets travel to domain services. */
public interface RpcTrustBackend {
  Mono<Boolean> allowed(RequestContext context, String rpc, RpcContextSecurity.Policy policy);

  Mono<Boolean> consume(String recipient, String issuer, String jti, Instant retainUntil);

  Mono<Boolean> currentKey(String recipient, String issuer, String kid, String peerFingerprint);
}
