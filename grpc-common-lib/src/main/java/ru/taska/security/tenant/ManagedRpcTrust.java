package ru.taska.security.tenant;

import io.grpc.Status;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import ru.taska.api.b2b.v1.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, atomically replaced operator snapshot. No last-known-good fallback after expiry. */
public final class ManagedRpcTrust implements RpcContextSecurity.Keys {
  public record Service(String generation, Set<String> certificates) {}

  public record Key(
      String issuer,
      String kid,
      String publicKey,
      Instant notBefore,
      Instant notAfter,
      boolean revoked) {}

  public record Edge(
      String issuer,
      String audience,
      String rpc,
      String action,
      Set<String> kinds,
      Set<String> scopes,
      String purpose,
      Set<String> serviceIdentities,
      String tenantMode,
      Set<String> tenants) {}

  public record Snapshot(
      int version,
      long generation,
      String environment,
      Instant issuedAt,
      Instant validUntil,
      Map<String, Service> services,
      List<Key> keys,
      List<Edge> edges) {}

  public record SigningMaterial(String kid, String privateKey) {
    @Override
    public String toString() {
      return "SigningMaterial[redacted]";
    }
  }

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Set<String> PURPOSES =
      Set.of("provisioning", "outbox", "billing", "admin", "membership");
  private final Path snapshotFile, privateKeyFile;
  private final String service, environment;
  private final Clock clock;
  private final AtomicLong generation = new AtomicLong(-1);
  private byte[] generationDigest;

  public ManagedRpcTrust(
      Path snapshotFile, Path privateKeyFile, String service, String environment, Clock clock) {
    this.snapshotFile = snapshotFile;
    this.privateKeyFile = privateKeyFile;
    this.service = service;
    this.environment = environment;
    this.clock = clock;
    snapshot();
    signingKey(service);
  }

  public synchronized Snapshot snapshot() {
    try {
      if (Files.size(snapshotFile) > 1_048_576) throw new IllegalArgumentException();
      byte[] bytes = Files.readAllBytes(snapshotFile);
      if (bytes.length > 1_048_576) throw new IllegalArgumentException();
      var s = JSON.readValue(bytes, Snapshot.class);
      var now = clock.instant();
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      if (s.generation() < generation.get())
        throw Status.UNAVAILABLE
            .withDescription("RPC trust snapshot rollback")
            .asRuntimeException();
      if (s.generation() == generation.get() && !Arrays.equals(digest, generationDigest))
        throw Status.UNAVAILABLE
            .withDescription("RPC trust snapshot generation reused")
            .asRuntimeException();
      if (s.version() != 1
          || !environment.equals(s.environment())
          || s.issuedAt().isAfter(now.plusSeconds(5))
          || !now.isBefore(s.validUntil())
          || !s.validUntil().isAfter(s.issuedAt())
          || Duration.between(s.issuedAt(), s.validUntil()).compareTo(Duration.ofMinutes(2)) > 0
          || !s.services().containsKey(service)
          || s.keys() == null
          || s.edges() == null) throw new IllegalArgumentException();
      for (var entry : s.services().entrySet()) {
        if (!entry.getKey().matches("[a-z][a-z0-9-]{0,63}")
            || !entry.getValue().generation().matches("[1-9][0-9]*")
            || entry.getValue().certificates().stream().anyMatch(v -> !v.matches("[a-f0-9]{64}")))
          throw new IllegalArgumentException();
      }
      var ids = new HashSet<String>();
      for (var k : s.keys())
        if (!s.services().containsKey(k.issuer())
            || k.kid().isBlank()
            || !ids.add(k.issuer() + "/" + k.kid())
            || !k.notAfter().isAfter(k.notBefore())) throw new IllegalArgumentException();
      for (var e : s.edges())
        if (!s.services().containsKey(e.issuer())
            || !s.services().containsKey(e.audience())
            || e.rpc().isBlank()
            || e.rpc().contains("*")
            || e.action().isBlank()
            || e.scopes().isEmpty()
            || e.kinds().isEmpty()
            || !Set.of("NONE", "BOUND", "SET").contains(e.tenantMode())
            || (e.tenantMode().equals("SET") && e.tenants().isEmpty())
            || (e.kinds().contains("SERVICE")
                ? (!PURPOSES.contains(e.purpose())
                    || e.serviceIdentities().isEmpty()
                    || !s.services().keySet().containsAll(e.serviceIdentities()))
                : (!"USER".equals(e.purpose()) || !e.serviceIdentities().isEmpty())))
          throw new IllegalArgumentException();
      generation.set(s.generation());
      generationDigest = digest;
      return s;
    } catch (Exception e) {
      if (e instanceof io.grpc.StatusRuntimeException status) throw status;
      String reason =
          e instanceof tools.jackson.core.JacksonException
              ? "malformed"
              : e instanceof java.io.IOException ? "unreadable" : "invalid or expired";
      throw Status.UNAVAILABLE.withDescription("RPC trust snapshot " + reason).asRuntimeException();
    }
  }

  private Key key(Snapshot s, String issuer, String kid) {
    var now = clock.instant();
    return s.keys().stream()
        .filter(
            k ->
                issuer.equals(k.issuer())
                    && kid.equals(k.kid())
                    && !k.revoked()
                    && !now.isBefore(k.notBefore())
                    && now.isBefore(k.notAfter()))
        .findFirst()
        .orElse(null);
  }

  private PublicKey publicKey(Key k) {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(k.publicKey())));
    } catch (Exception e) {
      throw unavailable();
    }
  }

  @Override
  public PublicKey verificationKey(String issuer, String kid) {
    var k = key(snapshot(), issuer, kid);
    return k == null ? null : publicKey(k);
  }

  /** One primary snapshot checks both the exact signing key and the original TLS peer. */
  public boolean currentKey(String recipient, String issuer, String kid, String fingerprint) {
    var s = snapshot();
    var identity = s.services().get(issuer);
    if (!s.services().containsKey(recipient)
        || identity == null
        || identity.certificates().isEmpty()
        || (fingerprint != null && !identity.certificates().contains(fingerprint))) return false;
    var k = key(s, issuer, kid);
    return k != null && publicKey(k) != null;
  }

  @Override
  public RpcContextSecurity.SigningKey signingKey(String issuer) {
    try {
      if (!service.equals(issuer)) throw unavailable();
      var s = snapshot();
      if (Files.size(privateKeyFile) > 8192) throw unavailable();
      var material = JSON.readValue(Files.readAllBytes(privateKeyFile), SigningMaterial.class);
      var kid = material.kid();
      var k = key(s, issuer, kid);
      if (k == null || Files.size(privateKeyFile) > 8192) throw unavailable();
      var privateKey =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(material.privateKey())));
      // Fail closed during a non-atomic kid/private-key rollout, without exposing either key.
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(new byte[] {1});
      byte[] proof = signer.sign();
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey(k));
      verifier.update(new byte[] {1});
      if (!verifier.verify(proof)) throw unavailable();
      return new RpcContextSecurity.SigningKey(kid, privateKey);
    } catch (Exception e) {
      if (e instanceof io.grpc.StatusRuntimeException status) throw status;
      String reason =
          e instanceof tools.jackson.core.JacksonException
              ? "malformed"
              : e instanceof java.io.IOException
                  ? "unreadable"
                  : e instanceof GeneralSecurityException ? "invalid key" : "invalid";
      throw Status.UNAVAILABLE
          .withDescription("RPC signing material " + reason)
          .asRuntimeException();
    }
  }

  public boolean peerAllowed(String peer, X509Certificate certificate) {
    try {
      certificate.checkValidity(Date.from(clock.instant()));
      var identity = snapshot().services().get(peer);
      String fingerprint =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
      return identity != null && identity.certificates().contains(fingerprint);
    } catch (java.security.GeneralSecurityException e) {
      return false;
    }
  }

  public boolean allowed(RequestContext request, String rpc, RpcContextSecurity.Policy policy) {
    var s = snapshot();
    var c = request.getContext();
    boolean system = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE;
    if (!s.services().containsKey(c.getIssuerService())
        || !s.services().containsKey(c.getAudienceService())
        || c.getScopesCount() == 0
        || (policy.tenantRequired() && !c.hasTenantId())
        || (system
            && (!c.hasServicePurpose()
                || !c.hasServiceIdentity()
                || !s.services().containsKey(c.getServiceIdentity())
                || !s.services()
                    .get(c.getServiceIdentity())
                    .generation()
                    .equals(c.getActorGeneration())))
        || (!system && (c.hasServicePurpose() || c.hasServiceIdentity()))
        || (system
            && (c.hasActorUserId()
                || c.hasSessionId()
                || c.hasCredentialId()
                || c.hasGrantId()
                || c.hasAdministrativeScope()
                || c.hasAdministrativeLevel()
                || c.getManageGlobalAccounts()))) return false;
    String kind = c.getCredentialKind().name().replace("CREDENTIAL_KIND_", "");
    return s.edges().stream()
        .anyMatch(
            e ->
                e.issuer().equals(c.getIssuerService())
                    && e.audience().equals(c.getAudienceService())
                    && e.rpc().equals(rpc)
                    && e.action().equals(c.getAction())
                    && e.kinds().contains(kind)
                    && e.scopes().containsAll(c.getScopesList())
                    && e.purpose().equals(system ? c.getServicePurpose() : "USER")
                    && (!system || e.serviceIdentities().contains(c.getServiceIdentity()))
                    && switch (e.tenantMode()) {
                      case "NONE" -> !c.hasTenantId();
                      case "BOUND" -> c.hasTenantId();
                      case "SET" -> c.hasTenantId() && e.tenants().contains(c.getTenantId());
                      default -> false;
                    });
  }

  public static io.grpc.StatusRuntimeException unavailable() {
    return Status.UNAVAILABLE.withDescription("RPC trust unavailable").asRuntimeException();
  }
}
