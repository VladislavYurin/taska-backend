package ru.taska.security.trust;

import io.r2dbc.spi.*;
import java.time.*;
import java.util.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.*;
import ru.taska.security.session.WebSessionStore;
import ru.taska.security.tenant.*;

/** Uncached primary-state validation. An active account alone never validates a credential. */
public final class AuthRpcTrustBackend implements RpcTrustBackend {
  private static final String TENANT_SERVICE_RPC = "ru.taska.b2b.v1.TenantService/";
  private final DatabaseClient db;
  private final ConnectionFactory connections;
  private final WebSessionStore.MembershipAuthority memberships;
  private final ManagedRpcTrust trust;
  private final Clock clock;

  public AuthRpcTrustBackend(
      DatabaseClient db,
      ConnectionFactory connections,
      WebSessionStore.MembershipAuthority memberships,
      ManagedRpcTrust trust,
      Clock clock) {
    this.db = db;
    this.connections = connections;
    this.memberships = memberships;
    this.trust = trust;
    this.clock = clock;
  }

  private static UUID id(String value) {
    var id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException();
    return id;
  }

  public Mono<Boolean> currentKey(String recipient, String issuer, String kid, String fingerprint) {
    return Mono.fromCallable(
            () -> {
              if (fingerprint == null && !recipient.equals(issuer)) return false;
              return trust.currentKey(recipient, issuer, kid, fingerprint);
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  public Mono<Boolean> allowed(
      RequestContext request, String rpc, RpcContextSecurity.Policy policy) {
    return Mono.defer(
            () -> {
              if (!trust.allowed(request, rpc, policy)) return Mono.just(false);
              var c = request.getContext();
              if (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE)
                return Mono.just(true);
              if (!c.hasActorUserId()
                  || !c.hasCredentialId()) return Mono.just(false);
              UUID user = id(c.getActorUserId());
              long generation = Long.parseLong(c.getActorGeneration());
              UUID credential = id(c.getCredentialId());
              boolean tenant = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT;
              boolean global = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL;
              boolean pat = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_USER_PAT;
              boolean targetAdmin = global && c.hasTenantId();
              boolean administrativeClaims =
                  c.hasAdministrativeScope()
                      || c.hasAdministrativeLevel()
                      || c.getManageGlobalAccounts();
              if (!tenant && !global && !pat) return Mono.just(false);
              if (administrativeClaims || targetAdmin) {
                if (!targetAdmin || !validWebAdminTarget(request, rpc, policy))
                  return Mono.just(false);
              }
              if ((tenant || pat) && (!c.hasTenantId() || !c.hasMembershipGeneration()))
                return Mono.just(false);
              if (global
                  && !targetAdmin
                  && (c.hasTenantId()
                      || c.hasGrantId()
                      || c.hasGrantGeneration()
                      || c.hasMembershipGeneration()))
                return Mono.just(false);
              if ((tenant || global)
                  && (!c.hasSessionId()
                      || !c.getSessionId().equals(c.getCredentialId())
                      || c.hasGrantId()
                      || c.hasGrantGeneration())) return Mono.just(false);
              var scopes = Set.copyOf(c.getScopesList());
              if (scopes.isEmpty()) return Mono.just(false);
              if (!targetAdmin
                  && !(global
                          ? Set.of("tenants:select", "tenants:create", "account:read")
                          : Set.of("read", "write"))
                      .containsAll(scopes)) return Mono.just(false);
              String sql;
              if (targetAdmin) {
                sql =
                    "SELECT (u.global_role='GLOBAL_ADMIN' AND w.assurance='ADMIN_EMAIL_MFA') AS"
                        + " allowed FROM taska.web_sessions w JOIN taska.users u ON"
                        + " u.id=w.user_id WHERE w.id=:credential AND w.user_id=:user AND"
                        + " w.state='ACTIVE' AND w.revoked_at IS NULL AND w.expires_at>:now AND"
                        + " w.auth_version=:generation AND u.auth_version=:generation AND"
                        + " u.status='ACTIVE'";
              } else if (pat) {
                if (c.hasSessionId() || !c.hasGrantId() || !c.hasGrantGeneration())
                  return Mono.just(false);
                sql =
                    "SELECT p.scopes FROM taska.personal_access_tokens p JOIN"
                        + " taska.integration_grants g ON g.id=p.integration_grant_id AND"
                        + " g.user_id=p.user_id JOIN taska.users u ON u.id=p.user_id WHERE"
                        + " p.id=:credential AND p.user_id=:user AND p.revoked_at IS NULL AND"
                        + " p.expires_at>:now AND g.id=:grant AND g.version=:grantVersion AND"
                        + " g.kind='USER' AND g.scope_type='ONE_TENANT' AND g.tenant_id=:tenant AND"
                        + " g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at>:now)"
                        + " AND u.auth_version=:generation AND u.status='ACTIVE'";
              } else if (tenant) {
                sql =
                    "SELECT ARRAY['read','write']::text[] AS scopes FROM taska.tenant_sessions s"
                        + " JOIN taska.web_sessions w ON w.id=s.web_session_id AND"
                        + " w.user_id=s.user_id JOIN taska.users u ON u.id=s.user_id WHERE"
                        + " s.id=:credential AND s.user_id=:user AND s.tenant_id=:tenant AND"
                        + " s.membership_version=:memberVersion AND s.revoked_at IS NULL AND"
                        + " s.expires_at>:now AND w.state='ACTIVE' AND w.revoked_at IS NULL AND"
                        + " w.expires_at>:now AND w.auth_version=:generation AND"
                        + " u.auth_version=:generation AND u.status='ACTIVE'";
              } else {
                sql =
                    "SELECT ARRAY['tenants:select','tenants:create','account:read']::text[] AS"
                        + " scopes FROM taska.web_sessions w JOIN taska.users u ON u.id=w.user_id"
                        + " WHERE w.id=:credential AND w.user_id=:user AND w.state='ACTIVE' AND"
                        + " w.revoked_at IS NULL AND w.expires_at>:now AND"
                        + " w.auth_version=:generation AND u.auth_version=:generation AND"
                        + " u.status='ACTIVE'";
              }
              var query =
                  db.sql(sql)
                      .bind("credential", credential)
                      .bind("user", user)
                      .bind("now", clock.instant())
                      .bind("generation", generation);
              if (tenant || pat) query = query.bind("tenant", id(c.getTenantId()));
              if (tenant)
                query = query.bind("memberVersion", Long.parseLong(c.getMembershipGeneration()));
              if (pat)
                query =
                    query
                        .bind("grant", id(c.getGrantId()))
                        .bind("grantVersion", Long.parseLong(c.getGrantGeneration()));
              return query
                  .map(
                      (r, m) ->
                          targetAdmin
                              ? Boolean.TRUE.equals(r.get("allowed", Boolean.class))
                              : Set.of(r.get("scopes", String[].class)).containsAll(scopes))
                  .one()
                  .defaultIfEmpty(false)
                  .flatMap(
                      valid -> {
                        if (!valid || global) return Mono.just(valid);
                        long expected = Long.parseLong(c.getMembershipGeneration());
                        return memberships
                            .current(id(c.getTenantId()), user)
                            .timeout(Duration.ofSeconds(3))
                            .map(current -> current > 0 && current == expected)
                            .defaultIfEmpty(false);
                      });
            })
        .onErrorResume(IllegalArgumentException.class, e -> Mono.just(false))
        .onErrorResume(
            ru.taska.exception.DomainException.class,
            e ->
                java.util.Set.of(
                            ru.taska.exception.DomainStatus.PERMISSION_DENIED,
                            ru.taska.exception.DomainStatus.UNAUTHENTICATED,
                            ru.taska.exception.DomainStatus.NOT_FOUND)
                        .contains(e.getStatus())
                    ? Mono.just(false)
                    : Mono.error(e))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private static boolean validWebAdminTarget(
      RequestContext request, String rpc, RpcContextSecurity.Policy policy) {
    var c = request.getContext();
    if (!c.hasTenantId()
        || !c.hasAdministrativeScope()
        || !c.hasAdministrativeLevel()
        || c.hasGrantId()
        || c.hasGrantGeneration()
        || c.hasMembershipGeneration()
        || c.getManageGlobalAccounts()
        || !c.hasSessionId()
        || !c.getSessionId().equals(c.getCredentialId())) return false;
    var target = id(c.getTenantId()).toString();
    var scope = c.getAdministrativeScope();
    if (scope.getKind() != AdminScope.KindValue.KIND_TENANT_SET
        || scope.getTenantIdsCount() != 1
        || !target.equals(scope.getTenantIds(0))) return false;
    var scopes = Set.copyOf(c.getScopesList());
    boolean readOnly =
        scopes.equals(Set.of("read"))
            && c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_READ;
    boolean modify =
        scopes.equals(Set.of("read", "write"))
            && c.getAdministrativeLevel() == AdminGrant.LevelValue.LEVEL_MODIFY;
    if (!readOnly && !modify) return false;
    if ("gatewayRequest".equals(rpc))
      return "gatewayRequest".equals(c.getAction()) && policy.mutation() == modify;
    if (TENANT_SERVICE_RPC.concat("GetTenant").equals(rpc))
      return "getTenant".equals(c.getAction()) && !policy.mutation();
    return TENANT_SERVICE_RPC.concat("RenameTenant").equals(rpc)
        && "renameTenant".equals(c.getAction())
        && modify
        && policy.mutation();
  }

  public Mono<RpcActorFacts> actorFacts(
      RequestContext request, String rpc, RpcContextSecurity.Policy policy) {
    return allowed(request, rpc, policy)
        .flatMap(
            ok -> {
              var c = request.getContext();
              if (!ok || c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_SERVICE)
                return Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());
              String sql;
              boolean pat = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_USER_PAT;
              boolean tenant = c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_TENANT;
              if (pat) {
                sql =
                    "SELECT u.global_role,false AS mfa,LEAST(p.expires_at,g.expires_at) AS expires"
                        + " FROM taska.users u JOIN taska.personal_access_tokens p ON"
                        + " p.user_id=u.id JOIN taska.integration_grants g ON"
                        + " g.id=p.integration_grant_id AND g.user_id=u.id WHERE u.id=:actor AND"
                        + " u.auth_version=:generation AND u.status='ACTIVE' AND p.id=:credential"
                        + " AND p.revoked_at IS NULL AND p.expires_at>:now AND g.id=:grant AND"
                        + " g.version=:grant_generation AND g.kind='USER' AND"
                        + " g.scope_type='ONE_TENANT' AND g.tenant_id=:tenant AND g.revoked_at IS"
                        + " NULL AND (g.expires_at IS NULL OR g.expires_at>:now) AND p.scopes @>"
                        + " :scopes::text[]";
              } else if (tenant) {
                sql =
                    "SELECT u.global_role,(w.assurance='ADMIN_EMAIL_MFA') AS"
                        + " mfa,LEAST(s.expires_at,w.expires_at) AS expires FROM taska.users u JOIN"
                        + " taska.web_sessions w ON w.user_id=u.id JOIN taska.tenant_sessions s ON"
                        + " s.web_session_id=w.id AND s.user_id=u.id WHERE u.id=:actor AND"
                        + " u.auth_version=:generation AND u.status='ACTIVE' AND s.id=:credential"
                        + " AND s.tenant_id=:tenant AND s.membership_version=:member_generation AND"
                        + " s.revoked_at IS NULL AND s.expires_at>:now AND w.state='ACTIVE' AND"
                        + " w.revoked_at IS NULL AND w.expires_at>:now AND"
                        + " w.auth_version=:generation";
              } else if (c.getCredentialKind() == CredentialKind.CREDENTIAL_KIND_WEB_GLOBAL) {
                sql =
                    "SELECT u.global_role,(w.assurance='ADMIN_EMAIL_MFA') AS mfa,w.expires_at AS"
                        + " expires FROM taska.users u JOIN taska.web_sessions w ON w.user_id=u.id"
                        + " WHERE u.id=:actor AND u.auth_version=:generation AND u.status='ACTIVE'"
                        + " AND w.id=:credential AND w.state='ACTIVE' AND w.revoked_at IS NULL AND"
                        + " w.expires_at>:now AND w.auth_version=:generation";
              } else return Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());
              var q =
                  db.sql(sql)
                      .bind("actor", id(c.getActorUserId()))
                      .bind("generation", Long.parseLong(c.getActorGeneration()))
                      .bind("credential", id(c.getCredentialId()))
                      .bind("now", clock.instant());
              if (pat || tenant) q = q.bind("tenant", id(c.getTenantId()));
              if (tenant)
                q = q.bind("member_generation", Long.parseLong(c.getMembershipGeneration()));
              if (pat)
                q =
                    q.bind("grant", id(c.getGrantId()))
                        .bind("grant_generation", Long.parseLong(c.getGrantGeneration()))
                        .bind("scopes", c.getScopesList().toArray(String[]::new));
              return q.map(
                      (r, m) -> {
                        Instant expiry = r.get("expires", OffsetDateTime.class).toInstant();
                        Instant assertion = Instant.parse(c.getExpiresAt());
                        if (assertion.isBefore(expiry)) expiry = assertion;
                        var out =
                            RpcActorFacts.newBuilder()
                                .setActorUserId(c.getActorUserId())
                                .setActorGeneration(c.getActorGeneration())
                                .setCredentialId(c.getCredentialId())
                                .setGlobalAdmin(
                                    "GLOBAL_ADMIN".equals(r.get("global_role", String.class)))
                                .setWebMfa(Boolean.TRUE.equals(r.get("mfa", Boolean.class)))
                                .addAllScopes(c.getScopesList())
                                .setExpiresAt(expiry.toString());
                        if (c.hasSessionId()) out.setSessionId(c.getSessionId());
                        return out.build();
                      })
                  .one()
                  .switchIfEmpty(Mono.error(io.grpc.Status.UNAUTHENTICATED.asRuntimeException()));
            });
  }

  public Mono<Boolean> consume(String recipient, String issuer, String jti, Instant retainUntil) {
    return Mono.defer(
            () -> {
              var services = trust.snapshot().services();
              var now = clock.instant();
              // The primary clock may differ from the recipient by the allowed five seconds.
              // Keep the nonce through the recipient's entire acceptance window even in that case.
              var primaryRetainUntil = retainUntil.plusSeconds(5);
              if (!services.containsKey(recipient)
                  || !services.containsKey(issuer)
                  || !primaryRetainUntil.isAfter(now)
                  || retainUntil.isAfter(now.plusSeconds(45))) return Mono.just(false);
              UUID assertion = id(jti);
              // Acquire directly from the pool, outside a caller's reactive transaction.
              // Consumption must
              // survive rollback/cancellation of the domain transaction and be visible to other
              // replicas.
              return Mono.usingWhen(
                  Mono.from(connections.create()),
                  connection ->
                      Mono.from(connection.setAutoCommit(true))
                          .then(
                              Mono.from(
                                  connection
                                      .createStatement(
                                          "INSERT INTO"
                                              + " taska.rpc_assertion_replay(recipient,issuer,assertion_id,retain_until)"
                                              + " VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING")
                                      .bind(0, recipient)
                                      .bind(1, issuer)
                                      .bind(2, assertion)
                                      .bind(3, primaryRetainUntil.atOffset(ZoneOffset.UTC))
                                      .execute()))
                          .flatMap(result -> Mono.from(result.getRowsUpdated()))
                          .map(count -> count == 1),
                  connection -> Mono.from(connection.close()));
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .cache();
  }

  public Mono<Void> cleanup() {
    return db.sql(
            "DELETE FROM taska.rpc_assertion_replay WHERE ctid IN (SELECT ctid FROM"
                + " taska.rpc_assertion_replay WHERE retain_until<:now LIMIT 10000)")
        .bind("now", clock.instant())
        .fetch()
        .rowsUpdated()
        .then();
  }
}
