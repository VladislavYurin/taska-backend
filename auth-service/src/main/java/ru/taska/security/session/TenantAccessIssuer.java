package ru.taska.security.session;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.time.*;
import java.util.*;

/** Separate B2B Ed25519 issuer; legacy HMAC JWTs cannot become tenant authority. */
public final class TenantAccessIssuer {
  private final PrivateKey key;
  private final String kid;
  private final Clock clock;
  private final Duration accessTtl;

  public TenantAccessIssuer(PrivateKey key, String kid, Clock clock, Duration accessTtl) {
    this.key = Objects.requireNonNull(key);
    if (!"Ed25519".equalsIgnoreCase(key.getAlgorithm()) && !"EdDSA".equalsIgnoreCase(key.getAlgorithm()))
      throw new IllegalArgumentException("Ed25519 signing key required");
    if (kid == null || !kid.matches("[A-Za-z0-9._-]{1,64}"))
      throw new IllegalArgumentException("Invalid key id");
    if (accessTtl.isZero() || accessTtl.isNegative() || accessTtl.compareTo(Duration.ofMinutes(15)) > 0)
      throw new IllegalArgumentException("Access TTL must be within 15 minutes");
    this.kid = kid;
    this.clock = Objects.requireNonNull(clock);
    this.accessTtl = accessTtl;
  }

  public String issue(WebSessionStore.Issued session) {
    Instant now = session.issuedAt();
    Instant expires = now.plus(accessTtl);
    if (expires.isAfter(session.expiresAt())) expires = session.expiresAt();
    if (!expires.isAfter(clock.instant())) throw new IllegalStateException("Session expired");
    var builder = Jwts.builder().header().keyId(kid).and()
        .issuer("taska-auth").audience().add("taska-api").and()
        .subject(session.userId().toString())
        .claim("sid", session.sessionId().toString())
        .claim("kind", session.tenantId() == null ? "WEB_GLOBAL" : "WEB_TENANT")
        .claim("account_generation", Long.toString(session.authVersion()))
        .id(tokenId(session))
        .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(expires));
    if (session.tenantId() == null)
      builder.claim("scopes", List.of("tenants:select", "account:read"));
    else
      builder.claim("tenant_id", session.tenantId().toString())
          .claim("membership_generation", Long.toString(session.membershipVersion()))
          .claim("scopes", List.of("read", "write"));
    return builder.signWith(key, Jwts.SIG.EdDSA).compact();
  }

  private static String tokenId(WebSessionStore.Issued session) {
    try {
      byte[] input = (session.sessionId() + ":" + session.refreshToken())
          .getBytes(StandardCharsets.US_ASCII);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(input));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  public long expiresIn(WebSessionStore.Issued session) {
    return Math.max(0, Math.min(accessTtl.getSeconds(),
        Duration.between(clock.instant(), session.expiresAt()).getSeconds()));
  }
}
