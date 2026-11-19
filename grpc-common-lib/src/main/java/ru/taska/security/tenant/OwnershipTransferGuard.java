package ru.taska.security.tenant;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.OwnershipTransferRequest;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Идентификатор операции не даёт полномочий прочитать receipt или изменить billing. */
public final class OwnershipTransferGuard {
  private OwnershipTransferGuard() {}

  public static UUID id(String text) {
    try {
      var id = UUID.fromString(text);
      if (!id.toString().equals(text)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "VALIDATION_FAILED");
    }
  }

  public static Mono<VerifiedTenantContext> require(
      OwnershipTransferRequest q, String issuer, String action, String scope, boolean mutation) {
    id(q.getTenantId());
    id(q.getOperationId());
    return VerifiedTenantContext.current()
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              if (!q.getTenantId().equals(v.tenantId())
                  || !action.equals(v.action())
                  || c.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !issuer.equals(c.getIssuerService())
                  || !issuer.equals(c.getServiceIdentity())
                  || !"billing".equals(c.getServicePurpose())
                  || !v.scopes().contains(scope)
                  || !v.scopes().contains(mutation ? "write" : "read")
                  || !q.getUnknownFields().asMap().isEmpty())
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "OWNERSHIP_AUTHORITY_REQUIRED"));
              return Mono.just(v);
            });
  }
}
