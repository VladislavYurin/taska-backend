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
  private static final Set<String> TENANT_TARGET_ACTIONS = Set.of("getTenant", "renameTenant");
  private static final Set<String> WEB_TENANT_SCOPES = Set.of("read", "write");
  private static final Set<String> WEB_GLOBAL_SCOPES =
      Set.of("tenants:select", "tenants:create", "account:read");

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

  private static DomainException tenantMismatch() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "TENANT_MISMATCH");
  }

  private static DomainException scopeDenied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
  }

  private static UUID id(String value) {
    if (value == null) throw rejected();
    var parsed = UUID.fromString(value);
    if (!parsed.toString().equals(value)) throw rejected();
    return parsed;
  }

  public Mono<GatewayCredential> resolve(String secret) {
    return resolve(secret, null, null);
  }

  /**
   * Resolve a credential for one route target. Only tenant detail actions may request this
   * binding. A global web session receives an admin target scope only after auth primary verifies
   * its current role and completed email MFA.
   */
  public Mono<GatewayCredential> resolve(
      String secret, String targetTenantId, String targetAction) {
    return Mono.defer(
            () -> {
              if (secret == null || secret.isBlank() || secret.length() > 16384)
                return Mono.error(rejected());
              boolean hasTarget = targetTenantId != null;
              boolean hasAction = targetAction != null;
              if (hasTarget != hasAction
                  || (hasAction && !TENANT_TARGET_ACTIONS.contains(targetAction)))
                return Mono.error(rejected());
              UUID target = hasTarget ? id(targetTenantId) : null;
              if (secret.startsWith("tsk_pat_")) {
                if (tokens == null) return Mono.error(rejected());
                return tokens
                    .authenticate(secret)
                    .flatMap(
                        b -> {
                          if (target != null && !target.equals(b.tenantId()))
                            return Mono.error(tenantMismatch());
                          if (target != null && !b.scopes().contains(requiredScope(targetAction)))
                            return Mono.error(scopeDenied());
                          return membership(b.tenantId(), b.userId(), null)
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
                                            b.tokenId().toString()));
                        });
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
              boolean global = "WEB_GLOBAL".equals(c.get("kind", String.class));
              if (!tenant && !global)
                return Mono.error(rejected());
              Object rawScopes = c.get("scopes");
              if (!(rawScopes instanceof List<?> supplied) || supplied.isEmpty())
                return Mono.error(rejected());
              var allowedScopes = tenant ? WEB_TENANT_SCOPES : WEB_GLOBAL_SCOPES;
              var scopes = new ArrayList<String>();
              for (Object scope : supplied) {
                if (!(scope instanceof String value)
                    || !allowedScopes.contains(value)
                    || scopes.contains(value)) return Mono.error(rejected());
                scopes.add(value);
              }
              String memberClaim = c.get("membership_generation", String.class);
              if (tenant && (memberClaim == null || !memberClaim.matches("[1-9][0-9]*")))
                return Mono.error(rejected());
              UUID company = tenant ? id(c.get("tenant_id", String.class)) : null;
              if (global && c.containsKey("tenant_id")) return Mono.error(rejected());
              if (target != null && global && !scopes.contains("tenants:select"))
                return Mono.error(scopeDenied());
              if (target != null && tenant && !target.equals(company))
                return Mono.error(tenantMismatch());
              if (target != null && tenant && !scopes.contains(requiredScope(targetAction)))
                return Mono.error(scopeDenied());
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
                      ? "SELECT s.membership_version,u.global_role,w.assurance FROM taska.tenant_sessions s JOIN"
                          + " taska.web_sessions w ON w.id=s.web_session_id AND"
                          + " w.user_id=s.user_id JOIN taska.users u ON u.id=s.user_id WHERE"
                          + " s.id=:sid AND s.user_id=:user AND s.tenant_id=:tenant AND"
                          + " s.revoked_at IS NULL AND s.expires_at>:now AND w.state='ACTIVE' AND"
                          + " w.revoked_at IS NULL AND w.expires_at>:now AND"
                          + " w.auth_version=:version AND u.auth_version=:version AND"
                          + " u.status='ACTIVE'"
                      : "SELECT 0::bigint AS membership_version,u.global_role,w.assurance FROM taska.web_sessions w JOIN"
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
              return q.map(
                      (r, m) ->
                          new SessionFacts(
                              r.get("membership_version", Long.class),
                              r.get("global_role", String.class),
                              r.get("assurance", String.class)))
                  .one()
                  .switchIfEmpty(Mono.error(rejected()))
                  .flatMap(
                      facts -> {
                        if (global && target != null) {
                          if (!"GLOBAL_ADMIN".equals(facts.globalRole()))
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.PERMISSION_DENIED, "ADMIN_GRANT_REQUIRED"));
                          if (!"ADMIN_EMAIL_MFA".equals(facts.assurance()))
                            return Mono.error(
                                new DomainException(DomainStatus.PERMISSION_DENIED, "MFA_REQUIRED"));
                          var adminScope =
                              AdminScope.newBuilder()
                                  .setKind(AdminScope.KindValue.KIND_TENANT_SET)
                                  .addTenantIds(target.toString())
                                  .build();
                          var level =
                              "renameTenant".equals(targetAction)
                                  ? AdminGrant.LevelValue.LEVEL_MODIFY
                                  : AdminGrant.LevelValue.LEVEL_READ;
                          var targetScopes =
                              "renameTenant".equals(targetAction)
                                  ? List.of("read", "write")
                                  : List.of("read");
                          return profile(
                              user,
                              binding
                                  .setTenantId(target.toString())
                                  .setAdministrativeScope(adminScope)
                                  .setAdministrativeLevel(level)
                                  .addAllScopes(targetScopes)
                                  .build(),
                              null);
                        }
                        if (!tenant)
                          return profile(user, binding.addAllScopes(scopes).build(), null);
                        long claimed = Long.parseLong(memberClaim);
                        if (!facts.membershipGeneration().equals(claimed))
                          return Mono.error(rejected());
                        return membership(company, user, claimed)
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
        .onErrorMap(
            e -> e instanceof IllegalArgumentException || e instanceof io.jsonwebtoken.JwtException,
            e -> rejected())
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

  private static String requiredScope(String action) {
    return "renameTenant".equals(action) ? "write" : "read";
  }

  private record SessionFacts(Long membershipGeneration, String globalRole, String assurance) {}

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
