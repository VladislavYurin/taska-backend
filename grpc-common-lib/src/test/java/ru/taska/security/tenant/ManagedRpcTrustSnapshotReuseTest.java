package ru.taska.security.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.StatusRuntimeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.b2b.v1.TrustedTenantContext;
import tools.jackson.databind.json.JsonMapper;

/** Повторное использование модели не отменяет чтение файла и актуальность полномочий. */
class ManagedRpcTrustSnapshotReuseTest {
  private static final Instant NOW = Instant.parse("2026-11-19T03:00:00Z");
  private static final String RPC = "owned.Service/Read";
  @TempDir Path directory;
  private final JsonMapper json = JsonMapper.builder().build();
  private final TestClock clock = new TestClock();
  private ManagedRpcTrust.Snapshot document;
  private ManagedRpcTrust trust;

  @BeforeEach
  void setup() throws Exception {
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var service = new ManagedRpcTrust.Service("1", Set.of("a".repeat(64)));
    document =
        new ManagedRpcTrust.Snapshot(
            1,
            1,
            "test",
            NOW,
            NOW.plusSeconds(110),
            Map.of("a", service, "b", service),
            List.of(
                new ManagedRpcTrust.Key(
                    "a",
                    "one",
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    NOW.minusSeconds(1),
                    NOW.plusSeconds(3600),
                    false)),
            List.of(
                new ManagedRpcTrust.Edge(
                    "a",
                    "b",
                    RPC,
                    "readOwned",
                    Set.of("WEB_TENANT"),
                    Set.of("read"),
                    "USER",
                    Set.of(),
                    "BOUND",
                    Set.of())));
    publish(document);
    Files.writeString(
        directory.resolve("signing.json"),
        json.writeValueAsString(
            new ManagedRpcTrust.SigningMaterial(
                "one", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()))));
    trust =
        new ManagedRpcTrust(
            directory.resolve("trust.json"), directory.resolve("signing.json"), "a", "test", clock);
  }

  private void publish(ManagedRpcTrust.Snapshot value) throws Exception {
    Path next = directory.resolve("trust.next");
    Files.writeString(next, json.writeValueAsString(value));
    Files.move(
        next,
        directory.resolve("trust.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  void unchangedContentReusesAnImmutableModel() {
    var first = trust.snapshot();
    assertSame(first, trust.snapshot());
    assertThrows(UnsupportedOperationException.class, () -> first.services().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> first.services().get("a").certificates().clear());
    assertThrows(UnsupportedOperationException.class, () -> first.keys().clear());
    assertThrows(UnsupportedOperationException.class, () -> first.edges().clear());
    var edge = first.edges().getFirst();
    for (Set<String> values :
        List.of(edge.kinds(), edge.scopes(), edge.serviceIdentities(), edge.tenants()))
      assertThrows(UnsupportedOperationException.class, () -> values.add("forged"));
  }

  @Test
  void sameGenerationTamperingIsDetectedEvenWithIdenticalFileMetadata() throws Exception {
    Path path = directory.resolve("trust.json");
    String original = Files.readString(path);
    var modified = Files.getLastModifiedTime(path);
    Files.writeString(path, original.replace("readOwned", "readOther"));
    Files.setLastModifiedTime(path, modified);
    assertThrows(StatusRuntimeException.class, trust::snapshot);
    Files.writeString(path, original);
    assertTrue(trust.allowed(request(), RPC, new RpcContextSecurity.Policy(true, false)));
  }

  @Test
  void cachedContentStillExpiresAndRejectsClockRollback() {
    trust.snapshot();
    clock.now = NOW.plusSeconds(110);
    assertThrows(StatusRuntimeException.class, trust::snapshot);
    clock.now = NOW.minusSeconds(6);
    assertThrows(StatusRuntimeException.class, trust::snapshot);
  }

  @Test
  void reusedModelDoesNotCacheKeyValidity() throws Exception {
    var key = document.keys().getFirst();
    publish(
        new ManagedRpcTrust.Snapshot(
            1,
            2,
            "test",
            NOW,
            NOW.plusSeconds(110),
            document.services(),
            List.of(
                new ManagedRpcTrust.Key(
                    key.issuer(), key.kid(), key.publicKey(), NOW, NOW.plusSeconds(10), false)),
            document.edges()));
    var snapshot = trust.snapshot();
    assertNotNull(trust.verificationKey("a", "one"));
    clock.now = NOW.plusSeconds(10);
    assertSame(snapshot, trust.snapshot());
    assertNull(trust.verificationKey("a", "one"));
    assertFalse(trust.currentKey("b", "a", "one", null));
    assertThrows(StatusRuntimeException.class, () -> trust.signingKey("a"));
  }

  @Test
  void missingCorruptOrOversizedSourceNeverFallsBack() throws Exception {
    Path path = directory.resolve("trust.json");
    Files.delete(path);
    assertThrows(StatusRuntimeException.class, trust::snapshot);
    Files.writeString(path, "{}");
    assertThrows(StatusRuntimeException.class, trust::snapshot);
    Files.writeString(path, " ".repeat(1_048_577));
    assertThrows(StatusRuntimeException.class, trust::snapshot);
    publish(document);
    assertTrue(trust.allowed(request(), RPC, new RpcContextSecurity.Policy(true, false)));
  }

  @Test
  void nextGenerationRevokesKeysAndGrantsAndRejectsThePreviousFile() throws Exception {
    var first = trust.snapshot();
    assertTrue(trust.currentKey("b", "a", "one", null));
    assertTrue(trust.allowed(request(), RPC, new RpcContextSecurity.Policy(true, false)));
    var key = document.keys().getFirst();
    publish(
        new ManagedRpcTrust.Snapshot(
            1,
            2,
            "test",
            NOW,
            NOW.plusSeconds(110),
            document.services(),
            List.of(
                new ManagedRpcTrust.Key(
                    key.issuer(),
                    key.kid(),
                    key.publicKey(),
                    key.notBefore(),
                    key.notAfter(),
                    true)),
            List.of()));
    assertNotSame(first, trust.snapshot());
    assertFalse(trust.currentKey("b", "a", "one", null));
    assertFalse(trust.allowed(request(), RPC, new RpcContextSecurity.Policy(true, false)));
    publish(document);
    assertThrows(StatusRuntimeException.class, trust::snapshot);
  }

  @Test
  void concurrentReadersReuseOnlyTheCurrentValidatedModel() throws Exception {
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<ManagedRpcTrust.Snapshot>>();
      for (int i = 0; i < 100; i++) tasks.add(trust::snapshot);
      var first = trust.snapshot();
      for (var result : workers.invokeAll(tasks)) assertSame(first, result.get());
      publish(
          new ManagedRpcTrust.Snapshot(
              1,
              2,
              "test",
              NOW,
              NOW.plusSeconds(110),
              document.services(),
              document.keys(),
              document.edges()));
      var next = trust.snapshot();
      assertNotSame(first, next);
      for (var result : workers.invokeAll(tasks)) assertSame(next, result.get());
    }
  }

  private RequestContext request() {
    return RequestContext.newBuilder()
        .setContext(
            TrustedTenantContext.newBuilder()
                .setIssuerService("a")
                .setAudienceService("b")
                .setAction("readOwned")
                .setCredentialKind(CredentialKind.CREDENTIAL_KIND_WEB_TENANT)
                .setTenantId("00000000-0000-0000-0000-000000000001")
                .addScopes("read"))
        .build();
  }

  private static final class TestClock extends Clock {
    Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    public Instant instant() {
      return now;
    }
  }
}
