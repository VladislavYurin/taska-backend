package ru.taska.security.gateway;

import io.jsonwebtoken.Jwts;
import java.security.PublicKey;
import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.common.v1.*;
import ru.taska.exception.*;
import ru.taska.repository.UserRepository;
import ru.taska.security.pat.TenantPersonalTokenStore;
import ru.taska.security.session.WebSessionStore;

/**
 * Auth primary verification: signed B2B JWT + durable session + uncached membership, or bound PAT.
 */
public final class GatewayCredentialResolver {
  private final DatabaseClient db;
  private final UserRepository users;
  private final WebSessionStore.MembershipAuthority memberships;
  private final TenantPersonalTokenStore tokens;
  private final PublicKey publicKey;
  private final String kid;
  private final Clock clock;

  public GatewayCredentialResolver(
      DatabaseClient db,
      UserRepository users,
      WebSessionStore.MembershipAuthority memberships,
      TenantPersonalTokenStore tokens,
      PublicKey publicKey,
      String kid,
      Clock clock) {
    this.db = db;
    this.users = users;
    this.memberships = memberships;
    this.tokens = tokens;
    this.publicKey = publicKey;
    this.kid = kid;
    this.clock = clock;
  }

  private static DomainException rejected() {
    return new DomainException(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED");
  }

  private static UUID id(String value) {
    var parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) throw rejected();
    return parsed;
  }

  public Mono<GatewayCredential> resolve(String secret) {
    return Mono.defer(
            () -> {
              if (secret == null || secret.isBlank() || secret.length() > 16384)
                return Mono.error(rejected());
              if (secret.startsWith("tsk_pat_")) {
                if (tokens == null) return Mono.error(rejected());
                return tokens
                    .authenticate(secret)
                    .flatMap(
                        b ->
                            membership(b.tenantId(), b.userId(), null)
                                .flatMap(
                                    generation ->
                                        profile(
                                            b.userId(),
                                            TrustedTenantContext.newBuilder()
                                                .setActorUserId(b.userId().toString())
                                                .setTenantId(b.tenantId().toString())
                                                .setCredentialKind(
                                                    CredentialKind.CREDENTIAL_KIND_USER_PAT)
                                                .setCredentialId(b.tokenId().toString())
                                                .setGrantId(b.grantId().toString())
                                                .setActorGeneration(Long.toString(b.authVersion()))
                                                .setGrantGeneration(Long.toString(b.grantVersion()))
                                                .setMembershipGeneration(Long.toString(generation))
                                                .addAllScopes(b.scopes())
                                                .build(),
                                            b.tokenId().toString())));
              }
              final io.jsonwebtoken.Claims c;
              try {
                var jwt =
                    Jwts.parser()
                        .verifyWith(publicKey)
                        .requireIssuer("taska-auth")
                        .requireAudience("taska-api")
                        .clock(() -> Date.from(clock.instant()))
                        .build()
                        .parseSignedClaims(secret);
                if (!kid.equals(jwt.getHeader().getKeyId())) throw rejected();
                c = jwt.getPayload();
                if (c.getIssuedAt() == null
                    || c.getExpiration() == null
                    || !c.getExpiration().toInstant().isAfter(clock.instant())
                    || !c.getExpiration().toInstant().isAfter(c.getIssuedAt().toInstant())
                    || c.getIssuedAt().toInstant().isAfter(clock.instant().plusSeconds(5))
                    || Duration.between(c.getIssuedAt().toInstant(), c.getExpiration().toInstant())
                            .compareTo(Duration.ofMinutes(15))
                        > 0) throw rejected();
              } catch (Exception e) {
                return Mono.error(rejected());
              }
              UUID user = id(c.getSubject()), session = id(c.get("sid", String.class));
              long account = Long.parseLong(c.get("account_generation", String.class));
              boolean tenant = "WEB_TENANT".equals(c.get("kind", String.class));
              if (!tenant && !"WEB_GLOBAL".equals(c.get("kind", String.class)))
                return Mono.error(rejected());
              Object rawScopes = c.get("scopes");
              if (!(rawScopes instanceof List<?> supplied) || supplied.isEmpty())
                return Mono.error(rejected());
              var allowedScopes =
                  tenant
                      ? Set.of("read", "write")
                      : Set.of("tenants:select", "tenants:create", "account:read");
              var scopes = new ArrayList<String>();
              for (Object scope : supplied) {
                if (!(scope instanceof String value)
                    || !allowedScopes.contains(value)
                    || scopes.contains(value)) return Mono.error(rejected());
                scopes.add(value);
              }
              UUID company = tenant ? id(c.get("tenant_id", String.class)) : null;
              if (!tenant && c.containsKey("tenant_id")) return Mono.error(rejected());
              var binding =
                  TrustedTenantContext.newBuilder()
                      .setActorUserId(user.toString())
                      .setSessionId(session.toString())
                      .setCredentialId(session.toString())
                      .setActorGeneration(Long.toString(account))
                      .setCredentialKind(
                          tenant
                              ? CredentialKind.CREDENTIAL_KIND_WEB_TENANT
                              : CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL);
              String query =
                  tenant
                      ? "SELECT s.membership_version FROM taska.tenant_sessions s JOIN"
                          + " taska.web_sessions w ON w.id=s.web_session_id AND"
                          + " w.user_id=s.user_id JOIN taska.users u ON u.id=s.user_id WHERE"
                          + " s.id=:sid AND s.user_id=:user AND s.tenant_id=:tenant AND"
                          + " s.revoked_at IS NULL AND s.expires_at>:now AND w.state='ACTIVE' AND"
                          + " w.revoked_at IS NULL AND w.expires_at>:now AND"
                          + " w.auth_version=:version AND u.auth_version=:version AND"
                          + " u.status='ACTIVE'"
                      : "SELECT 0::bigint AS membership_version FROM taska.web_sessions w JOIN"
                          + " taska.users u ON u.id=w.user_id WHERE w.id=:sid AND w.user_id=:user"
                          + " AND w.state='ACTIVE' AND w.revoked_at IS NULL AND w.expires_at>:now"
                          + " AND w.auth_version=:version AND u.auth_version=:version AND"
                          + " u.status='ACTIVE'";
              var q =
                  db.sql(query)
                      .bind("sid", session)
                      .bind("user", user)
                      .bind("version", account)
                      .bind("now", clock.instant());
              if (tenant) q = q.bind("tenant", company);
              return q.map((r, m) -> r.get("membership_version", Long.class))
                  .one()
                  .switchIfEmpty(Mono.error(rejected()))
                  .flatMap(
                      generation -> {
                        if (!tenant)
                          return profile(user, binding.addAllScopes(scopes).build(), null);
                        long claimed = Long.parseLong(c.get("membership_generation", String.class));
                        if (generation != claimed) return Mono.error(rejected());
                        return membership(company, user, generation)
                            .flatMap(
                                current ->
                                    profile(
                                        user,
                                        binding
                                            .setTenantId(company.toString())
                                            .setMembershipGeneration(Long.toString(current))
                                            .addAllScopes(scopes)
                                            .build(),
                                        null));
                      });
            })
        .onErrorMap(e -> e instanceof IllegalArgumentException, e -> rejected())
        .onErrorMap(
            e ->
                e instanceof org.springframework.dao.DataAccessException
                    || e instanceof io.r2dbc.spi.R2dbcException,
            e -> new DomainException(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"));
  }

  private Mono<Long> membership(UUID tenant, UUID user, Long expected) {
    return memberships
        .current(tenant, user)
        .timeout(Duration.ofSeconds(5))
        .onErrorMap(
            e -> !(e instanceof DomainException),
            e -> new DomainException(DomainStatus.UNAVAILABLE, "DEPENDENCY_UNAVAILABLE"))
        .switchIfEmpty(Mono.error(rejected()))
        .flatMap(
            g ->
                g < 1 || (expected != null && !expected.equals(g))
                    ? Mono.error(new DomainException(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"))
                    : Mono.just(g));
  }

  private Mono<GatewayCredential> profile(UUID user, TrustedTenantContext binding, String token) {
    return users
        .findById(user)
        .switchIfEmpty(Mono.error(rejected()))
        .flatMap(
            u -> {
              if (!"ACTIVE".equals(u.getStatus().name())) return Mono.error(rejected());
              var profile =
                  UserContext.newBuilder()
                      .setUserId(user.toString())
                      .setEmail(u.getEmail())
                      .setDisplayName(u.getDisplayName())
                      .setStatus(UserStatus.USER_STATUS_ACTIVE)
                      .setGlobalRole(
                          u.getGlobalRole() == ru.taska.domain.GlobalRole.GLOBAL_ADMIN
                              ? GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN
                              : GlobalRoleProto.GLOBAL_ROLE_USER)
                      .setTokenKind(
                          token == null
                              ? AccessTokenKind.ACCESS_TOKEN_KIND_JWT
                              : AccessTokenKind.ACCESS_TOKEN_KIND_PAT)
                      .addAllScopes(binding.getScopesList());
              if (token != null) profile.setTokenId(token);
              return Mono.just(
                  GatewayCredential.newBuilder().setUser(profile).setBinding(binding).build());
            });
  }
}
