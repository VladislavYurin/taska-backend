package ru.taska.security.tenant;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Local scope for one owner cancellation from the global web session; never forwarded. */
public final class TenantCancellationTransaction {
  public <T> Mono<T> execute(UUID tenant, Mono<T> work) {
    return VerifiedTenantContext.current()
        .flatMap(
            original -> {
              var claims = RpcContextBridge.details(original).getContext();
              if (original.tenantId() != null
                  || original.actorId() == null
                  || !original.action().equals("cancelTenantCreation")
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !claims.hasSessionId()
                  || claims.getSessionId().isBlank()
                  || !original.scopes().contains("write"))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "Global web session required"));
              var local =
                  new VerifiedTenantContext(
                      original.actorId(),
                      tenant.toString(),
                      original.requestId(),
                      original.action(),
                      original.scopes(),
                      original.wire());
              return work.contextWrite(local::install);
            });
  }
}
