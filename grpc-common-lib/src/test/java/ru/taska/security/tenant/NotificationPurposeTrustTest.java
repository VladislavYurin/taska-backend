package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;

class NotificationPurposeTrustTest {
  @TempDir Path directory;

  @Test
  void recognizedPurposeDoesNotAuthorizeAnotherEdgeIdentityTenantOrExtraScope() throws Exception {
    String rpc = "ru.taska.b2b.v1.IssueRecipientService/Resolve",
        tenant = UUID.randomUUID().toString();
    var fixture =
        new ManagedTrustFixture(directory, List.of("notification-service", "issue-service"));
    fixture.edges.add(
        new ManagedRpcTrust.Edge(
            "notification-service",
            "issue-service",
            rpc,
            "resolveIssueRecipients",
            Set.of("SERVICE"),
            Set.of("read", "notifications:recipients"),
            "NOTIFICATION_DELIVERY",
            Set.of("notification-service"),
            "SET",
            Set.of(tenant)));
    fixture.write();
    var trust = fixture.trust("notification-service");
    var c =
        TrustedTenantContext.newBuilder()
            .setContextVersion(1)
            .setIssuerService("notification-service")
            .setAudienceService("issue-service")
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setActorGeneration("1")
            .setTenantId(tenant)
            .setServiceIdentity("notification-service")
            .setServicePurpose("NOTIFICATION_DELIVERY")
            .setAction("resolveIssueRecipients")
            .addScopes("read")
            .addScopes("notifications:recipients")
            .setIssuedAt(Instant.now().toString())
            .setExpiresAt(Instant.now().plusSeconds(30).toString());
    var policy = new RpcContextSecurity.Policy(true, false);
    assertTrue(trust.allowed(RequestContext.newBuilder().setContext(c).build(), rpc, policy));
    for (var changed :
        List.of(
            c.clone().setServicePurpose("outbox"),
            c.clone().setServiceIdentity("issue-service"),
            c.clone().setTenantId(UUID.randomUUID().toString()),
            c.clone().addScopes("write")))
      assertFalse(
          trust.allowed(RequestContext.newBuilder().setContext(changed).build(), rpc, policy));
    assertFalse(
        trust.allowed(RequestContext.newBuilder().setContext(c).build(), rpc + "Other", policy));
  }
}
