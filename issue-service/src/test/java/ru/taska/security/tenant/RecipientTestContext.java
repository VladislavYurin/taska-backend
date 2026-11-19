package ru.taska.security.tenant;

import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;

/**
 * Только test artifact создаёт authority без подписи; внешний E2E использует настоящую проверку.
 */
public final class RecipientTestContext {
  public static <T> Mono<T> service(UUID tenant, String issuer, String purpose, Mono<T> work) {
    var scopes = Set.of("read", "notifications:recipients");
    var wire =
        RequestContext.newBuilder()
            .setContext(
                TrustedTenantContext.newBuilder()
                    .setTenantId(tenant.toString())
                    .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
                    .setIssuerService(issuer)
                    .setServiceIdentity(issuer)
                    .setServicePurpose(purpose)
                    .addAllScopes(scopes))
            .build()
            .toByteArray();
    var c =
        new VerifiedTenantContext(
            null,
            tenant.toString(),
            UUID.randomUUID().toString(),
            "resolveIssueRecipients",
            scopes,
            wire);
    return work.contextWrite(c::install);
  }
}
