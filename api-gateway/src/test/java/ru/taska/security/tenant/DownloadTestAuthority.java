package ru.taska.security.tenant;

import java.util.*;
import ru.taska.api.b2b.v1.*;

/** Test-only lease for gateway chunk demand tests; no production verifier is bypassed. */
public final class DownloadTestAuthority {
  private DownloadTestAuthority() {}

  public static VerifiedTenantContext verified() {
    String actor = UUID.randomUUID().toString(), tenant = UUID.randomUUID().toString();
    var claims =
        TrustedTenantContext.newBuilder()
            .setActorUserId(actor)
            .setTenantId(tenant)
            .setAction("gatewayRequest")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
            .addScopes("read")
            .build();
    return new VerifiedTenantContext(
        actor,
        tenant,
        UUID.randomUUID().toString(),
        "gatewayRequest",
        Set.of("read"),
        RequestContext.newBuilder().setContext(claims).build().toByteArray());
  }
}
