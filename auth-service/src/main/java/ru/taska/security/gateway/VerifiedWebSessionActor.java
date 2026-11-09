package ru.taska.security.gateway;

import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.exception.*;
import ru.taska.security.tenant.*;

/** A verified web-session RPC does not re-interpret its opaque credential as a legacy HMAC JWT. */
public final class VerifiedWebSessionActor {
  private VerifiedWebSessionActor() {}

  public static Mono<UUID> owner(Supplier<Mono<UUID>> legacy) {
    return Mono.deferContextual(
        context -> {
          if (!context.hasKey(VerifiedTenantContext.class)) return Mono.defer(legacy);
          var verified = VerifiedTenantContext.require(context);
          var claims = RpcContextBridge.details(verified).getContext();
          if ((claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  && claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
              || !claims.hasSessionId()
              || claims.getSessionId().isBlank()
              || verified.actorId() == null)
            return Mono.error(
                new DomainException(
                    DomainStatus.PERMISSION_DENIED,
                    "A web session is required",
                    java.util.Map.of("reason", "SESSION_REQUIRED")));
          return Mono.just(UUID.fromString(verified.actorId()));
        });
  }
}
