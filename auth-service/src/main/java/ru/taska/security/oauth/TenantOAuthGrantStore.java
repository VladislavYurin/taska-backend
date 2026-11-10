package ru.taska.security.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/**
 * TAS-498 user OAuth grants bound to exactly one tenant through a USER/ONE_TENANT integration
 * grant. The tenant is chosen once, by a verified tenant web session, for one pending
 * authorization request; code exchange and refresh only ever re-validate that binding.
 */
public class TenantOAuthGrantStore {
  /** Upper bound of an authorization-server refresh family (OAuthGrantStore.FAMILY_EXPIRES). */
  public static final int MAX_LIFETIME_DAYS = 30;

  /** Pending browser consent at the authorization server expires after ten minutes. */
  static final Duration CLAIM_WINDOW = Duration.ofMinutes(10);

  private static final Set<String> SCOPES = Set.of("read", "write");

  public record Approved(UUID grantId, UUID tenantId, List<String> scopes) {}

  /** Immutable credential facts for TrustedTenantContext; never an authorization decision. */
  public record Binding(
      UUID grantId,
      long grantVersion,
      UUID userId,
      UUID tenantId,
      List<String> scopes,
      Instant expiresAt,
      long authVersion) {}

  private record Request(String clientId, byte[] authorizationRequest, String level, byte[] digest) {}

  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final Clock clock;

  public TenantOAuthGrantStore(DatabaseClient db, TransactionalOperator tx, Clock clock) {
    this.db = Objects.requireNonNull(db);
    this.tx = Objects.requireNonNull(tx);
    this.clock = Objects.requireNonNull(clock);
  }

  private static DomainException failure(DomainStatus status, String code) {
    return new DomainException(status, code, Map.of("code", code));
  }

  private static DomainException invalid(String message) {
    return new DomainException(
        DomainStatus.INVALID_ARGUMENT, message, Map.of("code", "VALIDATION_FAILED"));
  }

  private static DomainException concealed() {
    return failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime utc(Instant time) {
    return time.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(Object value) {
    return ((OffsetDateTime) value).toInstant();
  }

  static String clientId(String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 2048 || !raw.equals(raw.strip()))
      throw invalid("Invalid OAuth client");
    return raw;
  }

  /** The authorization-server state token is stored only as a digest. */
  static byte[] authorizationRequest(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > 256 || !raw.matches("[A-Za-z0-9_.~=-]+"))
      throw invalid("Invalid authorization request");
    return DigestUtils.sha256(raw.getBytes(StandardCharsets.US_ASCII));
  }

  private static Request request(
      UUID tenant, String client, List<String> scopes, String authorizationRequest) {
    if (scopes == null
        || scopes.isEmpty()
        || scopes.size() > 2
        || Set.copyOf(scopes).size() != scopes.size()
        || !SCOPES.containsAll(scopes)
        || !scopes.contains("read")) throw invalid("Consent scopes must be read or read and write");
    String clientId = clientId(client);
    byte[] requestHash = authorizationRequest(authorizationRequest);
    String level = scopes.contains("write") ? "MODIFY" : "READ";
    String canonical =
        tenant + "\n" + clientId + "\n" + level + "\n" + HexFormat.of().formatHex(requestHash);
    return new Request(clientId, requestHash, level, DigestUtils.sha256(canonical));
  }

  static List<String> scopes(Object level) {
    return "MODIFY".equals(level) ? List.of("read", "write") : List.of("read");
  }

  /**
   * Approve from the verified tenant web session. The caller has already compared the live
   * membership generation with the session; this transaction re-checks account and session state
   * under the account lock, so one authorization request yields at most one grant.
   */
  public Mono<Approved> approve(
      UUID userId,
      UUID tenantSessionId,
      long membershipVersion,
      UUID tenantId,
      String clientId,
      List<String> scopes,
      String authorizationRequestId,
      UUID key) {
    if (userId == null || tenantSessionId == null || tenantId == null || key == null)
      return Mono.error(invalid("Missing consent identity"));
    return Mono.defer(
        () -> {
          Request request = request(tenantId, clientId, scopes, authorizationRequestId);
          Instant now = now();
          return db.sql(
                  "SELECT auth_version FROM taska.users WHERE id=:user AND status='ACTIVE' FOR"
                      + " UPDATE")
              .bind("user", userId)
              .fetch()
              .one()
              .switchIfEmpty(
                  Mono.error(failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED")))
              .flatMap(
                  user ->
                      db.sql(
                              "SELECT t.id FROM taska.tenant_sessions t JOIN taska.web_sessions w"
                                  + " ON w.id=t.web_session_id AND w.user_id=t.user_id WHERE"
                                  + " t.id=:session AND t.user_id=:user AND t.tenant_id=:tenant"
                                  + " AND t.membership_version=:membership AND t.revoked_at IS"
                                  + " NULL AND t.expires_at>:now AND w.state='ACTIVE' AND"
                                  + " w.revoked_at IS NULL AND w.expires_at>:now AND"
                                  + " w.auth_version=:authVersion")
                          .bind("session", tenantSessionId)
                          .bind("user", userId)
                          .bind("tenant", tenantId)
                          .bind("membership", membershipVersion)
                          .bind("now", utc(now))
                          .bind("authVersion", ((Number) user.get("auth_version")).longValue())
                          .fetch()
                          .one()
                          .switchIfEmpty(
                              Mono.error(failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED")))
                          .then(
                              replay(userId, key, request, now)
                                  .switchIfEmpty(
                                      Mono.defer(
                                          () -> create(userId, tenantId, request, key, now)))))
              .as(tx::transactional)
              // Lost race on the same request or key: the committed approval wins.
              .onErrorMap(
                  DataIntegrityViolationException.class,
                  error -> failure(DomainStatus.ABORTED, "INVALID_STATE"));
        });
  }

  private Mono<Approved> replay(UUID user, UUID key, Request request, Instant now) {
    return db.sql(
            "SELECT a.request_sha256,a.claim_expires_at,a.claimed_at,g.id,g.tenant_id,"
                + "g.access_level,g.revoked_at FROM taska.oauth_grant_approvals a JOIN"
                + " taska.integration_grants g ON g.id=a.grant_id AND g.user_id=a.user_id WHERE"
                + " a.user_id=:user AND a.idempotency_key=:key")
        .bind("user", user)
        .bind("key", key)
        .fetch()
        .one()
        .flatMap(
            row -> {
              if (!MessageDigest.isEqual(request.digest(), bytes(row.get("request_sha256"))))
                return Mono.error(failure(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT"));
              // A consumed, revoked or stale approval is never handed out again.
              if (row.get("claimed_at") != null
                  || row.get("revoked_at") != null
                  || !instant(row.get("claim_expires_at")).isAfter(now))
                return Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"));
              return Mono.just(
                  new Approved(
                      (UUID) row.get("id"),
                      (UUID) row.get("tenant_id"),
                      scopes(row.get("access_level"))));
            })
        .switchIfEmpty(
            // A different key for an already approved request: one request, one tenant.
            db.sql(
                    "SELECT 1 AS approved FROM taska.oauth_grant_approvals WHERE user_id=:user AND"
                        + " authorization_request_sha256=:request")
                .bind("user", user)
                .bind("request", request.authorizationRequest())
                .fetch()
                .one()
                .flatMap(row -> Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"))));
  }

  private Mono<Approved> create(UUID user, UUID tenant, Request request, UUID key, Instant now) {
    UUID grant = UUID.randomUUID();
    return db.sql(
            "INSERT INTO taska.integration_grants"
                + "(id,user_id,kind,scope_type,tenant_id,access_level,permissions,version,"
                + "created_at,expires_at) VALUES (:id,:user,'USER','ONE_TENANT',:tenant,"
                + ":level,:permissions,1,:now,:expires)")
        .bind("id", grant)
        .bind("user", user)
        .bind("tenant", tenant)
        .bind("level", request.level())
        .bind("permissions", new String[0])
        .bind("now", utc(now))
        .bind("expires", utc(now.plus(MAX_LIFETIME_DAYS, ChronoUnit.DAYS)))
        .fetch()
        .rowsUpdated()
        .then(
            db.sql(
                    "INSERT INTO taska.oauth_grant_approvals(grant_id,user_id,client_id,"
                        + "authorization_request_sha256,idempotency_key,request_sha256,created_at,"
                        + "claim_expires_at) VALUES (:grant,:user,:client,:request,:key,:digest,"
                        + ":now,:claimBy)")
                .bind("grant", grant)
                .bind("user", user)
                .bind("client", request.clientId())
                .bind("request", request.authorizationRequest())
                .bind("key", key)
                .bind("digest", request.digest())
                .bind("now", utc(now))
                .bind("claimBy", utc(now.plus(CLAIM_WINDOW)))
                .fetch()
                .rowsUpdated())
        .thenReturn(new Approved(grant, tenant, scopes(request.level())));
  }

  /**
   * Bind an approval to the pending authorization it was made for. Succeeds once, inside the
   * claim window, for the same account, client and authorization request only.
   */
  public Mono<Binding> claim(
      UUID grantId, UUID userId, String clientId, String authorizationRequestId) {
    return Mono.defer(
        () -> {
          String client = clientId(clientId);
          byte[] request = authorizationRequest(authorizationRequestId);
          Instant now = now();
          return grant(grantId, userId, true)
              .flatMap(
                  row -> {
                    if (!client.equals(row.get("client_id"))
                        || !MessageDigest.isEqual(
                            request, bytes(row.get("authorization_request_sha256"))))
                      return Mono.error(concealed());
                    if (row.get("claimed_at") != null
                        || !instant(row.get("claim_expires_at")).isAfter(now))
                      return Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"));
                    return live(row, now)
                        .flatMap(
                            binding ->
                                db.sql(
                                        "UPDATE taska.oauth_grant_approvals SET claimed_at=:now"
                                            + " WHERE grant_id=:grant AND claimed_at IS NULL")
                                    .bind("now", utc(now))
                                    .bind("grant", grantId)
                                    .fetch()
                                    .rowsUpdated()
                                    .thenReturn(binding));
                  })
              .as(tx::transactional);
        });
  }

  /**
   * Code exchange and refresh: the claimed grant is still live at the generation recorded when it
   * was bound. A tenant, when present, must equal the bound one.
   */
  public Mono<Binding> validate(
      UUID grantId, UUID userId, String clientId, long grantVersion, UUID tenantId) {
    return Mono.defer(
        () -> {
          String client = clientId(clientId);
          Instant now = now();
          return grant(grantId, userId, false)
              .flatMap(
                  row -> {
                    if (!client.equals(row.get("client_id"))
                        || row.get("claimed_at") == null
                        || (tenantId != null && !tenantId.equals(row.get("tenant_id"))))
                      return Mono.error(concealed());
                    if (((Number) row.get("version")).longValue() != grantVersion)
                      return Mono.error(failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
                    return live(row, now);
                  });
        });
  }

  private Mono<Map<String, Object>> grant(UUID grantId, UUID userId, boolean lock) {
    if (grantId == null || userId == null) return Mono.error(concealed());
    return db.sql(
            "SELECT a.client_id,a.authorization_request_sha256,a.claim_expires_at,a.claimed_at,"
                + "g.id,g.user_id,g.kind,g.scope_type,g.tenant_id,g.access_level,g.version,"
                + "g.expires_at,g.revoked_at,u.status,u.auth_version FROM"
                + " taska.oauth_grant_approvals a JOIN taska.integration_grants g ON"
                + " g.id=a.grant_id AND g.user_id=a.user_id JOIN taska.users u ON u.id=a.user_id"
                + " WHERE a.grant_id=:grant AND a.user_id=:user"
                + (lock ? " FOR UPDATE OF a" : ""))
        .bind("grant", grantId)
        .bind("user", userId)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(concealed()));
  }

  private static Mono<Binding> live(Map<String, Object> row, Instant now) {
    if (!"USER".equals(row.get("kind"))
        || !"ONE_TENANT".equals(row.get("scope_type"))
        || row.get("tenant_id") == null) return Mono.error(concealed());
    Instant expires = instant(row.get("expires_at"));
    if (row.get("revoked_at") != null
        || !expires.isAfter(now)
        || !"ACTIVE".equals(row.get("status")))
      return Mono.error(failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
    return Mono.just(
        new Binding(
            (UUID) row.get("id"),
            ((Number) row.get("version")).longValue(),
            (UUID) row.get("user_id"),
            (UUID) row.get("tenant_id"),
            scopes(row.get("access_level")),
            expires,
            ((Number) row.get("auth_version")).longValue()));
  }

  private static byte[] bytes(Object value) {
    if (value instanceof byte[] data) return data;
    java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) value).duplicate();
    byte[] data = new byte[buffer.remaining()];
    buffer.get(data);
    return data;
  }
}
