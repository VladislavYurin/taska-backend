package ru.taska.security.tenant;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Read-only operation lookup from the original global web session; never forwarded. */
public final class ProvisioningReadTransaction {

  public <T> Mono<T> execute(UUID tenant, Mono<T> read) {
    return VerifiedTenantContext.current()
        .flatMap(
            original -> {
              var claims = RpcContextBridge.details(original).getContext();
              if (original.tenantId() != null
                  || original.actorId() == null
                  || !"getTenantOperation".equals(original.action())
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !claims.hasSessionId()
                  || !original.scopes().contains("read"))
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "Session required"));
              var local =
                  new VerifiedTenantContext(
                      original.actorId(),
                      tenant.toString(),
                      original.requestId(),
                      original.action(),
                      original.scopes(),
                      original.wire());
              return read.contextWrite(local::install);
            });
  }
}
