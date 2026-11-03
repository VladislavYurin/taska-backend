package ru.taska.security.tenant;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.r2dbc.TenantTransactionExecutor;

/** Local registry insert only: does not issue or forward a tenant credential. */
public final class TenantCreationTransaction {
  private final TenantTransactionExecutor executor;

  public TenantCreationTransaction(TenantTransactionExecutor executor) {
    this.executor = executor;
  }

  public <T> Mono<T> execute(UUID newTenant, Function<DatabaseClient, Mono<T>> work) {
    return VerifiedTenantContext.current()
        .flatMap(
            original -> {
              var claims = RpcContextBridge.details(original).getContext();
              if (original.tenantId() != null
                  || original.actorId() == null
                  || !original.action().equals("createTenant")
                  || claims.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL
                  || !claims.hasSessionId())
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED, "Global web session required"));
              var local =
                  new VerifiedTenantContext(
                      original.actorId(),
                      newTenant.toString(),
                      original.requestId(),
                      original.action(),
                      original.scopes(),
                      original.wire());
              return executor.execute(work).contextWrite(local::install);
            });
  }
}
