package ru.taska.security.pat;

import io.r2dbc.postgresql.codec.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * TAS-463 user PATs bound to exactly one tenant through a USER/ONE_TENANT integration grant. The
 * tenant is fixed at issuance and is never re-derived from a later request or web selection.
 */
public class TenantPersonalTokenStore {
  public static final int MAX_LIFETIME_DAYS = 90;
  private static final Pattern FORMAT = Pattern.compile("tsk_pat_[A-Za-z0-9_-]{43}");
  private static final Set<String> SCOPES = Set.of("read", "write");

  public record Issued(
      UUID tokenId,
      UUID tenantId,
      String name,
      List<String> scopes,
      Instant createdAt,
      Instant expiresAt,
      String secret) {}

  /** Immutable credential facts for TrustedTenantContext; never an authorization decision. */
  public record Binding(
      UUID tokenId,
      UUID grantId,
      long grantVersion,
      UUID userId,
      UUID tenantId,
      List<String> scopes,
      Instant expiresAt,
      long authVersion) {}

  private record Request(String name, String[] scopes, int days, byte[] digest) {}

  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final Clock clock;
  private final SecureRandom random;
  private final PersonalTokenProperties policy;
  private final byte[] receiptKey;
  private final ObjectMapper json;

  public TenantPersonalTokenStore(
      DatabaseClient db,
      TransactionalOperator tx,
      Clock clock,
      SecureRandom random,
      PersonalTokenProperties policy,
      byte[] receiptKey,
      ObjectMapper json) {
    this.db = Objects.requireNonNull(db);
    this.tx = Objects.requireNonNull(tx);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    this.policy = Objects.requireNonNull(policy);
    this.json = Objects.requireNonNull(json);
    if (receiptKey.length != 32) throw new IllegalArgumentException("AES-256 receipt key required");
    this.receiptKey = receiptKey.clone();
  }

  private static DomainException failure(DomainStatus status, String code) {
    return new DomainException(status, code, Map.of("code", code));
  }

  private static DomainException invalid(String message) {
    return new DomainException(
        DomainStatus.INVALID_ARGUMENT, message, Map.of("code", "VALIDATION_FAILED"));
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

  private Request request(UUID tenant, String name, List<String> scopes, long days) {
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty() || normalized.length() > 100)
      throw invalid("Token name must contain 1 to 100 characters");
    if (scopes == null
        || scopes.isEmpty()
        || scopes.size() > 2
        || Set.copyOf(scopes).size() != scopes.size()
        || !SCOPES.containsAll(scopes)) throw invalid("Token scopes must be read and/or write");
    int max = Math.min(MAX_LIFETIME_DAYS, policy.getMaxLifetimeDays());
    if (days < 1 || days > max)
      throw new DomainException(
          DomainStatus.INVALID_ARGUMENT,
          "Token lifetime is outside policy",
          Map.of("code", "VALIDATION_FAILED", "maxLifetimeDays", String.valueOf(max)));
    // write includes read; storage keeps the canonical legacy shape.
    String[] stored = scopes.contains("write") ? new String[] {"read", "write"} : new String[] {"read"};
    String canonical = tenant + "\n" + normalized + "\n" + String.join(",", stored) + "\n" + days;
    return new Request(normalized, stored, (int) days, DigestUtils.sha256(canonical));
  }

  /**
   * Issue from the verified tenant web session. The caller has already admitted pat.create for
   * this tenant through the shared capability policy; this transaction re-checks account and
   * session state under the account lock and serializes the active-token limit.
   */
  public Mono<Issued> issue(
      UUID userId,
      UUID tenantSessionId,
      UUID tenantId,
      String name,
      List<String> scopes,
      long expiresInDays,
      UUID key,
      String requestId) {
    if (userId == null || tenantSessionId == null || tenantId == null || key == null)
      return Mono.error(invalid("Missing issuance identity"));
    return Mono.defer(
        () -> {
          Request request = request(tenantId, name, scopes, expiresInDays);
          Instant now = now();
          return db.sql(
                  "SELECT email,auth_version FROM taska.users WHERE id=:user AND status='ACTIVE'"
                      + " FOR UPDATE")
              .bind("user", userId)
              .fetch()
              .one()
              .switchIfEmpty(
                  Mono.error(failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED")))
              .flatMap(
                  user -> {
                    long authVersion = ((Number) user.get("auth_version")).longValue();
                    return db.sql(
                            "SELECT t.id FROM taska.tenant_sessions t JOIN taska.web_sessions w"
                                + " ON w.id=t.web_session_id AND w.user_id=t.user_id WHERE"
                                + " t.id=:session AND t.user_id=:user AND t.tenant_id=:tenant AND"
                                + " t.revoked_at IS NULL AND t.expires_at>:now AND"
                                + " w.state='ACTIVE' AND w.revoked_at IS NULL AND"
                                + " w.expires_at>:now AND w.auth_version=:authVersion")
                        .bind("session", tenantSessionId)
                        .bind("user", userId)
                        .bind("tenant", tenantId)
                        .bind("now", utc(now))
                        .bind("authVersion", authVersion)
                        .fetch()
                        .one()
                        .switchIfEmpty(
                            Mono.error(failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED")))
                        .then(
                            replay(userId, key, request, now)
                                .switchIfEmpty(
                                    Mono.defer(
                                        () ->
                                            create(
                                                userId,
                                                (String) user.get("email"),
                                                tenantId,
                                                request,
                                                key,
                                                requestId,
                                                now))));
                  })
              .as(tx::transactional);
        });
  }

  private Mono<Issued> replay(UUID user, UUID key, Request request, Instant now) {
    return db.sql(
            "SELECT r.request_sha256,r.nonce,r.ciphertext,r.expires_at AS receipt_expires,"
                + "p.id,p.name,p.scopes,p.created_at,p.expires_at,p.revoked_at,g.tenant_id FROM"
                + " taska.personal_token_issuance_receipts r JOIN taska.personal_access_tokens p"
                + " ON p.id=r.token_id JOIN taska.integration_grants g ON"
                + " g.id=p.integration_grant_id WHERE r.user_id=:user AND r.idempotency_key=:key")
        .bind("user", user)
        .bind("key", key)
        .fetch()
        .one()
        .flatMap(
            row -> {
              if (!MessageDigest.isEqual(request.digest(), bytes(row.get("request_sha256"))))
                return Mono.error(failure(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT"));
              if (row.get("ciphertext") == null
                  || !instant(row.get("receipt_expires")).isAfter(now)
                  || row.get("revoked_at") != null
                  || !instant(row.get("expires_at")).isAfter(now))
                return Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"));
              UUID token = (UUID) row.get("id");
              return Mono.just(
                  new Issued(
                      token,
                      (UUID) row.get("tenant_id"),
                      (String) row.get("name"),
                      List.of((String[]) row.get("scopes")),
                      instant(row.get("created_at")),
                      instant(row.get("expires_at")),
                      unseal(user, key, bytes(row.get("nonce")), bytes(row.get("ciphertext")))));
            });
  }

  private Mono<Issued> create(
      UUID user,
      String email,
      UUID tenant,
      Request request,
      UUID key,
      String requestId,
      Instant now) {
    return db.sql(
            "SELECT count(*) AS active FROM taska.personal_access_tokens WHERE user_id=:user AND"
                + " revoked_at IS NULL AND expires_at>:now")
        .bind("user", user)
        .bind("now", utc(now))
        .fetch()
        .one()
        .flatMap(
            count -> {
              if (((Number) count.get("active")).longValue() >= policy.getMaxActiveTokens())
                return Mono.error(
                    new DomainException(
                        DomainStatus.FAILED_PRECONDITION,
                        "Active token limit reached",
                        Map.of(
                            "code",
                            "QUOTA_EXCEEDED",
                            "limit",
                            String.valueOf(policy.getMaxActiveTokens()))));
              UUID grant = UUID.randomUUID(), token = UUID.randomUUID();
              Instant expires = now.plus(request.days(), ChronoUnit.DAYS);
              String secret = secret();
              String prefix = secret.substring(0, PersonalAccessTokenService.PREFIX.length() + 4);
              Sealed sealed = seal(user, key, secret);
              return db.sql(
                      "INSERT INTO taska.integration_grants"
                          + "(id,user_id,kind,scope_type,tenant_id,access_level,permissions,version,"
                          + "created_at,expires_at) VALUES (:id,:user,'USER','ONE_TENANT',:tenant,"
                          + ":level,:permissions,1,:now,:expires)")
                  .bind("id", grant)
                  .bind("user", user)
                  .bind("tenant", tenant)
                  .bind("level", request.scopes().length == 2 ? "MODIFY" : "READ")
                  .bind("permissions", new String[0])
                  .bind("now", utc(now))
                  .bind("expires", utc(expires))
                  .fetch()
                  .rowsUpdated()
                  .then(
                      db.sql(
                              "INSERT INTO taska.personal_access_tokens"
                                  + "(id,user_id,name,token_prefix,token_hash,scopes,created_at,"
                                  + "expires_at,integration_grant_id) VALUES (:id,:user,:name,"
                                  + ":prefix,:hash,:scopes,:now,:expires,:grant)")
                          .bind("id", token)
                          .bind("user", user)
                          .bind("name", request.name())
                          .bind("prefix", prefix)
                          .bind("hash", DigestUtils.sha256Hex(secret))
                          .bind("scopes", request.scopes())
                          .bind("now", utc(now))
                          .bind("expires", utc(expires))
                          .bind("grant", grant)
                          .fetch()
                          .rowsUpdated())
                  .then(
                      db.sql(
                              "INSERT INTO taska.personal_token_issuance_receipts"
                                  + "(user_id,idempotency_key,request_sha256,token_id,nonce,"
                                  + "ciphertext,created_at,expires_at) VALUES (:user,:key,:digest,"
                                  + ":token,:nonce,:ciphertext,:now,:expires)")
                          .bind("user", user)
                          .bind("key", key)
                          .bind("digest", request.digest())
                          .bind("token", token)
                          .bind("nonce", sealed.nonce())
                          .bind("ciphertext", sealed.ciphertext())
                          .bind("now", utc(now))
                          .bind("expires", utc(now.plusSeconds(60)))
                          .fetch()
                          .rowsUpdated())
                  .then(created(user, email, tenant, token, prefix, expires, requestId, now))
                  .thenReturn(
                      new Issued(
                          token,
                          tenant,
                          request.name(),
                          List.of(request.scopes()),
                          now,
                          expires,
                          secret));
            });
  }

  private Mono<Long> created(
      UUID user,
      String email,
      UUID tenant,
      UUID token,
      String prefix,
      Instant expires,
      String requestId,
      Instant now) {
    // Same notification contract as legacy issuance; never the secret or its hash.
    var payload =
        json.createObjectNode()
            .put("userId", user.toString())
            .put("email", email)
            .put("tokenId", token.toString())
            .put("tokenPrefix", prefix)
            .put("expiresAt", expires.toString())
            .put("tenantId", tenant.toString());
    var statement =
        db.sql(
                "INSERT INTO taska.outbox_events"
                    + "(aggregate_type,aggregate_id,event_type,payload,status,attempts,created_at,"
                    + "request_id) VALUES (:type,:aggregate,:event,:payload,'NEW',0,:now,:request)")
            .bind("type", AggregateType.USER.getValue())
            .bind("aggregate", user)
            .bind("event", EventType.PERSONAL_ACCESS_TOKEN_CREATED.getValue())
            .bind("payload", Json.of(payload.toString()))
            .bind("now", utc(now));
    statement =
        requestId == null || requestId.isBlank()
            ? statement.bindNull("request", String.class)
            : statement.bind("request", requestId);
    return statement.fetch().rowsUpdated();
  }

  /**
   * Resolve a presented secret to its immutable tenant binding. Legacy unbound tokens never
   * authenticate here: a missing tenant must not mean "all companies".
   */
  public Mono<Binding> authenticate(String secret) {
    return Mono.defer(
        () -> {
          if (secret == null || !FORMAT.matcher(secret).matches())
            return Mono.error(failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"));
          Instant now = now();
          return db.sql(
                  "SELECT p.id,p.user_id,p.scopes,p.expires_at,p.revoked_at,p.last_used_at,"
                      + "g.id AS grant_id,g.kind,g.scope_type,g.tenant_id,g.version,"
                      + "g.expires_at AS grant_expires,g.revoked_at AS grant_revoked,u.status,"
                      + "u.auth_version FROM taska.personal_access_tokens p JOIN"
                      + " taska.integration_grants g ON g.id=p.integration_grant_id AND"
                      + " g.user_id=p.user_id JOIN taska.users u ON u.id=p.user_id WHERE"
                      + " p.token_hash=:hash")
              .bind("hash", DigestUtils.sha256Hex(secret))
              .fetch()
              .one()
              .switchIfEmpty(
                  Mono.error(failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED")))
              .flatMap(
                  row -> {
                    if (!"USER".equals(row.get("kind"))
                        || !"ONE_TENANT".equals(row.get("scope_type"))
                        || row.get("tenant_id") == null)
                      return Mono.error(
                          failure(DomainStatus.UNAUTHENTICATED, "AUTHENTICATION_REQUIRED"));
                    Instant expires = instant(row.get("expires_at"));
                    if (row.get("revoked_at") != null
                        || row.get("grant_revoked") != null
                        || !expires.isAfter(now)
                        || (row.get("grant_expires") != null
                            && !instant(row.get("grant_expires")).isAfter(now))
                        || !"ACTIVE".equals(row.get("status")))
                      return Mono.error(failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED"));
                    var binding =
                        new Binding(
                            (UUID) row.get("id"),
                            (UUID) row.get("grant_id"),
                            ((Number) row.get("version")).longValue(),
                            (UUID) row.get("user_id"),
                            (UUID) row.get("tenant_id"),
                            List.of((String[]) row.get("scopes")),
                            expires,
                            ((Number) row.get("auth_version")).longValue());
                    Object used = row.get("last_used_at");
                    if (used != null && instant(used).isAfter(now.minus(5, ChronoUnit.MINUTES)))
                      return Mono.just(binding);
                    return db.sql(
                            "UPDATE taska.personal_access_tokens SET last_used_at=:now WHERE"
                                + " id=:id AND revoked_at IS NULL AND (last_used_at IS NULL OR"
                                + " last_used_at<:threshold)")
                        .bind("now", utc(now))
                        .bind("id", binding.tokenId())
                        .bind("threshold", utc(now.minus(5, ChronoUnit.MINUTES)))
                        .fetch()
                        .rowsUpdated()
                        .thenReturn(binding);
                  });
        });
  }

  private String secret() {
    byte[] entropy = new byte[32];
    random.nextBytes(entropy);
    String raw =
        PersonalAccessTokenService.PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    Arrays.fill(entropy, (byte) 0);
    return raw;
  }

  private record Sealed(byte[] nonce, byte[] ciphertext) {}

  private Sealed seal(UUID user, UUID key, String raw) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(receiptKey, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(user, key));
      return new Sealed(nonce, cipher.doFinal(raw.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Cannot seal token receipt", error);
    }
  }

  private String unseal(UUID user, UUID key, byte[] nonce, byte[] ciphertext) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(receiptKey, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad(user, key));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Invalid token receipt", error);
    }
  }

  // Distinct from session receipt AAD, so a receipt cannot be replayed into the other table.
  private static byte[] aad(UUID user, UUID key) {
    return ("tenant-pat:" + user + ":" + key).getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(Object value) {
    if (value instanceof byte[] data) return data;
    java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) value).duplicate();
    byte[] data = new byte[buffer.remaining()];
    buffer.get(data);
    return data;
  }
}
