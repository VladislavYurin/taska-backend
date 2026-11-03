package ru.taska.security.session;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Jwts;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TenantAccessIssuerTest {
  @Test
  void globalAndTenantClaimsStaySeparateAndExpireWithinSession() throws Exception {
    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var now = Instant.parse("2026-11-03T12:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var issuer = new TenantAccessIssuer(pair.getPrivate(), "session-key-1", clock,
        Duration.ofMinutes(15));
    UUID user = UUID.randomUUID(), web = UUID.randomUUID(), tenant = UUID.randomUUID();
    var global = new WebSessionStore.Issued(web, web, user, null, 0,
        UUID.randomUUID(), "unused", now.plusSeconds(300), 2, now);
    var globalJwt = issuer.issue(global);
    var globalClaims = Jwts.parser().clock(() -> Date.from(now)).verifyWith(pair.getPublic()).build()
        .parseSignedClaims(globalJwt);
    assertEquals("session-key-1", globalClaims.getHeader().getKeyId());
    assertEquals("WEB_GLOBAL", globalClaims.getPayload().get("kind"));
    assertEquals("2", globalClaims.getPayload().get("account_generation"));
    assertFalse(globalClaims.getPayload().containsKey("tenant_id"));
    assertEquals(Date.from(now.plusSeconds(300)), globalClaims.getPayload().getExpiration());

    var tenantSession = new WebSessionStore.Issued(web, UUID.randomUUID(), user, tenant, 7,
        UUID.randomUUID(), "unused", now.plusSeconds(3600), 2, now);
    var tenantClaims = Jwts.parser().clock(() -> Date.from(now)).verifyWith(pair.getPublic()).build()
        .parseSignedClaims(issuer.issue(tenantSession)).getPayload();
    assertEquals("WEB_TENANT", tenantClaims.get("kind"));
    assertEquals(tenant.toString(), tenantClaims.get("tenant_id"));
    assertEquals("7", tenantClaims.get("membership_generation"));
    assertEquals(issuer.expiresIn(tenantSession), 900);
    assertEquals(issuer.issue(tenantSession), issuer.issue(tenantSession));
  }
}
