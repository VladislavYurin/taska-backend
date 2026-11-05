package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.fail;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.AdminGrant;
import ru.taska.api.b2b.v1.AdminScope;
import ru.taska.billing.SubscriptionDependencies.FinancialReadAuthority;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Shared shape checks plus mandatory primary current owner/admin authority. */
final class FinancialAccess {
  private final FinancialReadAuthority readers;

  FinancialAccess(FinancialReadAuthority readers) {
    this.readers = readers;
  }

  Mono<VerifiedTenantContext> authorize(UUID tenant, String action, boolean mutation) {
    return VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e -> fail(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"))
        .map(
            v -> {
              if (!tenant.toString().equals(v.tenantId()) || !action.equals(v.action()))
                throw fail(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
              return v;
            })
        .flatMap(
            v -> {
              var c = RpcContextBridge.details(v).getContext();
              boolean ordinary =
                  switch (c.getCredentialKind()) {
                    case CREDENTIAL_KIND_WEB_TENANT ->
                        c.hasSessionId()
                            && !c.getSessionId().isBlank()
                            && c.hasMembershipGeneration()
                            && !c.getMembershipGeneration().isBlank();
                    case CREDENTIAL_KIND_USER_PAT, CREDENTIAL_KIND_USER_OAUTH ->
                        c.hasCredentialId()
                            && !c.getCredentialId().isBlank()
                            && c.hasMembershipGeneration()
                            && !c.getMembershipGeneration().isBlank();
                    default -> false;
                  };
              boolean admin =
                  switch (c.getCredentialKind()) {
                    case CREDENTIAL_KIND_WEB_GLOBAL ->
                        c.hasSessionId() && !c.getSessionId().isBlank();
                    case CREDENTIAL_KIND_ADMIN_PAT, CREDENTIAL_KIND_ADMIN_OAUTH ->
                        c.hasCredentialId()
                            && !c.getCredentialId().isBlank()
                            && c.hasGrantId()
                            && !c.getGrantId().isBlank()
                            && c.hasGrantGeneration()
                            && !c.getGrantGeneration().isBlank();
                    default -> false;
                  };
              if (admin) {
                var scope = c.getAdministrativeScope();
                admin =
                    ((!mutation && c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_READ)
                            || c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_MODIFY)
                        && (scope.getKind() == AdminScope.KindValue.KIND_PLATFORM
                            || (scope.getKind() == AdminScope.KindValue.KIND_TENANT_SET
                                && scope.getTenantIdsList().contains(tenant.toString())));
              }
              if (v.actorId() == null
                  || c.getActorGeneration().isBlank()
                  || !v.scopes().contains(mutation ? "write" : "read")
                  || (!ordinary && !admin))
                return Mono.error(fail(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return readers
                  .allowed(v)
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")))
                  .flatMap(
                      ok ->
                          ok
                              ? Mono.just(v)
                              : Mono.error(fail(DomainStatus.PERMISSION_DENIED, "OWNER_REQUIRED")));
            });
  }
}
