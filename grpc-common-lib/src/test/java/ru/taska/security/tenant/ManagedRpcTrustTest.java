package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Status;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;

class ManagedRpcTrustTest extends RpcContextFixture {
  @TempDir Path directory;
  ManagedTrustFixture fixture;
  ManagedRpcTrust source, target;

  @BeforeEach
  void managed() throws Exception {
    fixture = new ManagedTrustFixture(directory, List.of("a", "b"));
    fixture.userEdge("a", "b", RPC, "test.write");
    fixture.write();
    source = fixture.trust("a");
    target = fixture.trust("b");
    sender =
        new RpcContextSecurity(
            "a",
            Clock.systemUTC(),
            source,
            (i, j, t) -> Mono.just(true),
            (c, r, p) -> Mono.just(source.allowed(c, r, p)));
    recipient =
        new RpcContextSecurity(
            "b",
            Clock.systemUTC(),
            target,
            (i, j, t) -> Mono.just(true),
            (c, r, p) -> Mono.just(target.allowed(c, r, p)));
  }

  @Override
  RequestContext context() {
    var now = Instant.now();
    var c = super.context();
    return c.toBuilder()
        .setContext(
            c.getContext().toBuilder()
                .setIssuedAt(now.toString())
                .setExpiresAt(now.plusSeconds(30).toString()))
        .build();
  }

  @Test
  void rotationOverlapAndRevocationAffectAnExistingVerifier() throws Exception {
    var context = context();
    var message = message(context, "owned");
    var old = sign(context, message);
    assertNotNull(recipient.verify(message, old, RPC, "a", POLICY).block());
    var next = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    fixture.addKey("a", "two", next);
    fixture.activate("a", "two", next);
    fixture.write();
    assertNotNull(recipient.verify(message, old, RPC, "a", POLICY).block());
    var current = context();
    var newer = message(current, "owned");
    String token = sign(current, newer);
    assertNotNull(recipient.verify(newer, token, RPC, "a", POLICY).block());
    fixture.keys.replaceAll(
        k ->
            k.issuer().equals("a") && k.kid().equals("one")
                ? new ManagedRpcTrust.Key(
                    k.issuer(), k.kid(), k.publicKey(), k.notBefore(), k.notAfter(), true)
                : k);
    fixture.write();
    rejects(recipient.verify(message, old, RPC, "a", POLICY), Status.Code.UNAUTHENTICATED);
    assertNotNull(recipient.verify(newer, token, RPC, "a", POLICY).block());
    rejects(recipient.verify(newer, token, RPC, "b", POLICY), Status.Code.UNAUTHENTICATED);
  }

  @Test
  void missingExpiredCorruptOrRolledBackTrustNeverFallsBack() throws Exception {
    byte[] original = Files.readAllBytes(fixture.path("trust.json"));
    fixture.write();
    source.snapshot();
    Files.write(fixture.path("trust.json"), original);
    assertThrows(io.grpc.StatusRuntimeException.class, source::snapshot);
    fixture.write(Instant.now().minusSeconds(1));
    assertThrows(io.grpc.StatusRuntimeException.class, source::snapshot);
    fixture.write();
    source.snapshot();
    Files.writeString(fixture.path("trust.json"), "{}");
    assertThrows(io.grpc.StatusRuntimeException.class, source::snapshot);
    Files.delete(fixture.path("trust.json"));
    assertThrows(io.grpc.StatusRuntimeException.class, source::snapshot);
  }

  @Test
  void servicePurposeIdentityTenantAndScopesCannotBeSubstituted() throws Exception {
    fixture.edges.add(
        new ManagedRpcTrust.Edge(
            "a",
            "b",
            RPC,
            "test.write",
            Set.of("SERVICE"),
            Set.of("write"),
            "outbox",
            Set.of("a"),
            "SET",
            Set.of(TENANT)));
    fixture.write();
    var c =
        context().getContext().toBuilder()
            .clearActorUserId()
            .clearMembershipGeneration()
            .setCredentialKind(CredentialKind.CREDENTIAL_KIND_SERVICE)
            .setServicePurpose("outbox")
            .setServiceIdentity("a");
    var r = context().toBuilder().setContext(c).build();
    assertTrue(source.allowed(r, RPC, POLICY));
    for (var changed :
        List.of(
            c.clone().setServicePurpose("billing"),
            c.clone().setServiceIdentity("b"),
            c.clone().setTenantId(UUID.randomUUID().toString()),
            c.clone().setActorGeneration("2"),
            c.clone().addScopes("admin"),
            c.clone().setManageGlobalAccounts(true)))
      assertFalse(source.allowed(r.toBuilder().setContext(changed).build(), RPC, POLICY));
    assertFalse(source.allowed(r, RPC + "Other", POLICY));
    assertFalse(
        source.allowed(
            r.toBuilder().setContext(c.clone().setAudienceService("a")).build(), RPC, POLICY));
  }
}
