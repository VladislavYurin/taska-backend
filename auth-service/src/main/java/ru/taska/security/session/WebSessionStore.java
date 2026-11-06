package ru.taska.security.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Durable, independent refresh families; the API boundary must authenticate the caller. */
public final class WebSessionStore {
  private final DatabaseClient db;
  private final TransactionalOperator tx;
  private final Clock clock;
  private final SecureRandom random;
  private final Duration lifetime;
  private final MembershipAuthority memberships;
  private final byte[] receiptKey;

  /**
   * Returns the current ACTIVE membership generation, including creation/payment lifecycle states.
   * Empty means unknown/foreign tenant (404). Inactive membership must fail with
   * PERMISSION_DENIED/MEMBERSHIP_INACTIVE; authority outages propagate unchanged.
   */
  public interface MembershipAuthority {
    Mono<Long> current(UUID tenantId, UUID userId);
  }

  public record Issued(
      UUID webSessionId,
      UUID sessionId,
      UUID userId,
      UUID tenantId,
      long membershipVersion,
      UUID familyId,
      String refreshToken,
      Instant expiresAt,
      long authVersion,
      Instant issuedAt) {}

  private record Token(String raw, byte[] hash) {}

  public WebSessionStore(
      DatabaseClient db,
      TransactionalOperator tx,
      Clock clock,
      SecureRandom random,
      Duration lifetime,
      MembershipAuthority memberships,
      byte[] receiptKey) {
    this.db = Objects.requireNonNull(db);
    this.tx = Objects.requireNonNull(tx);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    if (lifetime.isNegative() || lifetime.isZero())
      throw new IllegalArgumentException("session lifetime must be positive");
    this.lifetime = lifetime;
    this.memberships = Objects.requireNonNull(memberships);
    if (receiptKey.length != 32) throw new IllegalArgumentException("AES-256 receipt key required");
    this.receiptKey = receiptKey.clone();
  }

  private Token token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Token(raw, sha(raw));
  }

  private static byte[] sha(String raw) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static DomainException failure(DomainStatus status, String code) {
    return new DomainException(status, code, Map.of("code", code));
  }

  private static DomainException denied() {
    return failure(DomainStatus.UNAUTHENTICATED, "TOKEN_REVOKED");
  }

  public Mono<Issued> openGlobal(UUID userId, String assurance) {
    return openGlobal(userId, assurance, UUID.randomUUID());
  }

  public Mono<Issued> openGlobal(UUID userId, String assurance, UUID key) {
    if (!Set.of("PASSWORD", "SOCIAL").contains(assurance))
      return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid assurance"));
    if (key == null)
      return Mono.error(
          new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid idempotency key"));
    return Mono.defer(
        () -> {
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS),
              expires = now.plus(lifetime);
          UUID session = UUID.randomUUID(), family = UUID.randomUUID();
          Token secret = token();
          return db.sql(
                  "SELECT id,global_role,auth_version FROM taska.users"
                      + " WHERE id=:user AND status='ACTIVE' FOR UPDATE")
              .bind("user", userId)
              .fetch()
              .one()
              .switchIfEmpty(Mono.error(denied()))
              .flatMap(
                  user -> {
                    // GLOBAL_ADMIN requires the separate email-MFA path (TAS-328).
                    if (!"USER".equals(user.get("global_role").toString()))
                      return Mono.error(failure(DomainStatus.PERMISSION_DENIED, "MFA_REQUIRED"));
                    return replayIssuance(
                            userId,
                            key,
                            "GLOBAL_" + assurance,
                            null,
                            null,
                            0,
                            ((Number) user.get("auth_version")).longValue(),
                            now)
                        .switchIfEmpty(
                            db.sql(
                                    "INSERT INTO taska.web_sessions"
                                        + "(id,user_id,state,assurance,auth_version,created_at,expires_at)"
                                        + " VALUES"
                                        + " (:id,:user,'ACTIVE',:assurance,:version,:now,:expires)")
                                .bind("id", session)
                                .bind("user", userId)
                                .bind("assurance", assurance)
                                .bind("version", ((Number) user.get("auth_version")).longValue())
                                .bind("now", now.atOffset(ZoneOffset.UTC))
                                .bind("expires", expires.atOffset(ZoneOffset.UTC))
                                .fetch()
                                .rowsUpdated()
                                .then(
                                    insertRefresh(
                                        userId, session, null, family, secret, now, expires))
                                .then(
                                    saveIssuance(
                                        userId,
                                        key,
                                        "GLOBAL_" + assurance,
                                        session,
                                        session,
                                        null,
                                        0,
                                        family,
                                        ((Number) user.get("auth_version")).longValue(),
                                        secret,
                                        now))
                                .thenReturn(
                                    new Issued(
                                        session,
                                        session,
                                        userId,
                                        null,
                                        0,
                                        family,
                                        secret.raw(),
                                        expires,
                                        ((Number) user.get("auth_version")).longValue(),
                                        now)));
                  })
              .as(tx::transactional);
        });
  }

  public Mono<Issued> openTenant(UUID userId, UUID webSessionId, UUID tenantId) {
    return openTenant(userId, webSessionId, tenantId, UUID.randomUUID());
  }

  public Mono<Issued> openTenant(UUID userId, UUID webSessionId, UUID tenantId, UUID key) {
    if (key == null)
      return Mono.error(
          new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid idempotency key"));
    return memberships
        .current(tenantId, userId)
        .switchIfEmpty(Mono.error(failure(DomainStatus.NOT_FOUND, "TENANT_NOT_FOUND")))
        .flatMap(
            membershipVersion ->
                openTenantValidated(userId, webSessionId, tenantId, membershipVersion, key));
  }

  private Mono<Issued> openTenantValidated(
      UUID userId, UUID webSessionId, UUID tenantId, long membershipVersion, UUID key) {
    if (membershipVersion < 1)
      return Mono.error(failure(DomainStatus.PERMISSION_DENIED, "MEMBERSHIP_INACTIVE"));
    return Mono.defer(
        () -> {
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          UUID session = UUID.randomUUID(), family = UUID.randomUUID();
          Token secret = token();
          return db.sql(
                  "SELECT w.expires_at,w.auth_version FROM taska.web_sessions w JOIN taska.users u"
                      + " ON u.id=w.user_id WHERE w.id=:web AND w.user_id=:user AND"
                      + " w.state='ACTIVE' AND w.revoked_at IS NULL AND w.expires_at>:now AND"
                      + " u.status='ACTIVE' AND w.auth_version=u.auth_version FOR UPDATE OF w")
              .bind("web", webSessionId)
              .bind("user", userId)
              .bind("now", now.atOffset(ZoneOffset.UTC))
              .fetch()
              .one()
              .switchIfEmpty(Mono.error(denied()))
              .flatMap(
                  web -> {
                    Instant expires = ((OffsetDateTime) web.get("expires_at")).toInstant();
                    return replayIssuance(
                            userId,
                            key,
                            "TENANT_SELECT",
                            webSessionId,
                            tenantId,
                            membershipVersion,
                            ((Number) web.get("auth_version")).longValue(),
                            now)
                        .switchIfEmpty(
                            db.sql(
                                    "INSERT INTO taska.tenant_sessions"
                                        + "(id,web_session_id,user_id,tenant_id,membership_version,version,created_at,expires_at)"
                                        + " VALUES"
                                        + " (:id,:web,:user,:tenant,:generation,1,:now,:expires)")
                                .bind("id", session)
                                .bind("web", webSessionId)
                                .bind("user", userId)
                                .bind("tenant", tenantId)
                                .bind("generation", membershipVersion)
                                .bind("now", now.atOffset(ZoneOffset.UTC))
                                .bind("expires", expires.atOffset(ZoneOffset.UTC))
                                .fetch()
                                .rowsUpdated()
                                .then(
                                    insertRefresh(
                                        userId,
                                        webSessionId,
                                        session,
                                        family,
                                        secret,
                                        now,
                                        expires))
                                .then(
                                    saveIssuance(
                                        userId,
                                        key,
                                        "TENANT_SELECT",
                                        webSessionId,
                                        session,
                                        tenantId,
                                        membershipVersion,
                                        family,
                                        ((Number) web.get("auth_version")).longValue(),
                                        secret,
                                        now))
                                .thenReturn(
                                    new Issued(
                                        webSessionId,
                                        session,
                                        userId,
                                        tenantId,
                                        membershipVersion,
                                        family,
                                        secret.raw(),
                                        expires,
                                        ((Number) web.get("auth_version")).longValue(),
                                        now)));
                  })
              .as(tx::transactional);
        });
  }

  private Mono<Issued> replayIssuance(
      UUID user,
      UUID key,
      String operation,
      UUID web,
      UUID tenant,
      long membershipVersion,
      long authVersion,
      Instant now) {
    return db.sql(
            "SELECT i.*,w.state AS web_state,w.revoked_at AS web_revoked, w.expires_at AS"
                + " web_expires,t.id AS tenant_session_id, t.revoked_at AS tenant_revoked FROM"
                + " taska.session_issuance_receipts i JOIN taska.web_sessions w ON"
                + " w.id=i.web_session_id LEFT JOIN taska.tenant_sessions t ON t.id=i.session_id"
                + " AND i.operation='TENANT_SELECT' WHERE i.user_id=:user AND"
                + " i.idempotency_key=:key")
        .bind("user", user)
        .bind("key", key)
        .fetch()
        .one()
        .flatMap(
            row -> {
              if (!operation.equals(row.get("operation"))
                  || (operation.equals("TENANT_SELECT")
                      && !Objects.equals(web, row.get("web_session_id")))
                  || !Objects.equals(tenant, row.get("tenant_id")))
                return Mono.error(failure(DomainStatus.ABORTED, "IDEMPOTENCY_CONFLICT"));
              if (!"ACTIVE".equals(row.get("web_state"))
                  || row.get("web_revoked") != null
                  || (tenant != null && row.get("tenant_session_id") == null)
                  || row.get("tenant_revoked") != null
                  || !((OffsetDateTime) row.get("web_expires")).toInstant().isAfter(now)
                  || ((Number) row.get("auth_version")).longValue() != authVersion
                  || ((Number) row.get("membership_version")).longValue() != membershipVersion)
                return Mono.error(denied());
              if (row.get("ciphertext") == null
                  || !((OffsetDateTime) row.get("expires_at")).toInstant().isAfter(now))
                return Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"));
              return Mono.just(
                  new Issued(
                      (UUID) row.get("web_session_id"),
                      (UUID) row.get("session_id"),
                      user,
                      (UUID) row.get("tenant_id"),
                      membershipVersion,
                      (UUID) row.get("family_id"),
                      unseal(user, key, bytes(row.get("nonce")), bytes(row.get("ciphertext"))),
                      ((OffsetDateTime) row.get("web_expires")).toInstant(),
                      authVersion,
                      ((OffsetDateTime) row.get("created_at")).toInstant()));
            });
  }

  private Mono<Long> saveIssuance(
      UUID user,
      UUID key,
      String operation,
      UUID web,
      UUID session,
      UUID tenant,
      long membershipVersion,
      UUID family,
      long authVersion,
      Token token,
      Instant now) {
    Sealed sealed = seal(user, key, token.raw());
    var statement =
        db.sql(
                "INSERT INTO taska.session_issuance_receipts"
                    + "(user_id,idempotency_key,operation,web_session_id,session_id,tenant_id,"
                    + "membership_version,family_id,auth_version,nonce,ciphertext,created_at,expires_at)"
                    + " VALUES (:user,:key,:operation,:web,:session,:tenant,:generation,:family,"
                    + ":authVersion,:nonce,:ciphertext,:now,:expires)")
            .bind("user", user)
            .bind("key", key)
            .bind("operation", operation)
            .bind("web", web)
            .bind("session", session)
            .bind("generation", membershipVersion)
            .bind("family", family)
            .bind("authVersion", authVersion)
            .bind("nonce", sealed.nonce())
            .bind("ciphertext", sealed.ciphertext())
            .bind("now", now.atOffset(ZoneOffset.UTC))
            .bind("expires", now.plusSeconds(60).atOffset(ZoneOffset.UTC));
    statement =
        tenant == null
            ? statement.bindNull("tenant", UUID.class)
            : statement.bind("tenant", tenant);
    return statement.fetch().rowsUpdated();
  }

  private Mono<Long> insertRefresh(
      UUID user, UUID web, UUID tenant, UUID family, Token token, Instant now, Instant expires) {
    var statement =
        db.sql(
                "INSERT INTO taska.refresh_tokens"
                    + "(id,user_id,token_hash,issued_at,expires_at,created_at,web_session_id,tenant_session_id,family_id)"
                    + " VALUES (:id,:user,:hash,:now,:expires,:now,:web,:tenant,:family)")
            .bind("id", UUID.randomUUID())
            .bind("user", user)
            .bind("hash", Base64.getEncoder().encodeToString(token.hash()))
            .bind("now", now.atOffset(ZoneOffset.UTC))
            .bind("expires", expires.atOffset(ZoneOffset.UTC))
            .bind("web", web)
            .bind("family", family);
    statement =
        tenant == null
            ? statement.bindNull("tenant", UUID.class)
            : statement.bind("tenant", tenant);
    return statement.fetch().rowsUpdated();
  }

  private record Sealed(byte[] nonce, byte[] ciphertext) {}

  private record RotationResult(Issued issued, boolean rejected) {}

  private Sealed seal(UUID oldId, UUID key, String raw) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(receiptKey, "AES"),
          new GCMParameterSpec(128, nonce));
      cipher.updateAAD((oldId + ":" + key).getBytes(StandardCharsets.US_ASCII));
      return new Sealed(nonce, cipher.doFinal(raw.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Cannot seal refresh receipt", error);
    }
  }

  private String unseal(UUID oldId, UUID key, byte[] nonce, byte[] ciphertext) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(receiptKey, "AES"),
          new GCMParameterSpec(128, nonce));
      cipher.updateAAD((oldId + ":" + key).getBytes(StandardCharsets.US_ASCII));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
    } catch (java.security.GeneralSecurityException error) {
      throw new IllegalStateException("Invalid refresh receipt", error);
    }
  }

  private static byte[] bytes(Object value) {
    if (value instanceof byte[] data) return data;
    java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) value).duplicate();
    byte[] data = new byte[buffer.remaining()];
    buffer.get(data);
    return data;
  }

  /** The refresh secret selects its family. No request field may change its tenant binding. */
  public Mono<Issued> rotate(String raw, UUID key) {
    if (raw == null || raw.isBlank() || raw.length() > 256 || key == null)
      return Mono.error(denied());
    String digest = Base64.getEncoder().encodeToString(sha(raw));
    return db.sql(
            "SELECT r.user_id,t.tenant_id FROM taska.refresh_tokens r"
                + " LEFT JOIN taska.tenant_sessions t ON t.id=r.tenant_session_id"
                + " WHERE r.token_hash=:hash AND r.web_session_id IS NOT NULL")
        .bind("hash", digest)
        .fetch()
        .one()
        .switchIfEmpty(Mono.error(denied()))
        .flatMap(
            found -> {
              UUID tenant = (UUID) found.get("tenant_id"), user = (UUID) found.get("user_id");
              Mono<Long> generation =
                  tenant == null ? Mono.just(0L) : memberships.current(tenant, user);
              return generation
                  .switchIfEmpty(Mono.error(denied()))
                  .flatMap(current -> rotateLocked(digest, key, current));
            });
  }

  private Mono<Issued> rotateLocked(String digest, UUID key, long currentGeneration) {
    return Mono.defer(
        () -> {
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          return db.sql(
                  "SELECT r.id,r.user_id,r.web_session_id,r.tenant_session_id,r.family_id,"
                      + " r.expires_at,r.revoked_at,r.replaced_by,w.state AS web_state,"
                      + " w.revoked_at AS web_revoked,w.expires_at AS web_expires,w.auth_version AS"
                      + " web_version, t.tenant_id,t.membership_version,t.revoked_at AS"
                      + " tenant_revoked,t.expires_at AS tenant_expires, u.status AS"
                      + " user_status,u.auth_version AS user_version FROM taska.refresh_tokens r"
                      + " JOIN taska.web_sessions w ON w.id=r.web_session_id JOIN taska.users u ON"
                      + " u.id=r.user_id LEFT JOIN taska.tenant_sessions t ON"
                      + " t.id=r.tenant_session_id WHERE r.token_hash=:hash FOR UPDATE OF r")
              .bind("hash", digest)
              .fetch()
              .one()
              .switchIfEmpty(Mono.error(denied()))
              .flatMap(
                  row -> {
                    UUID old = (UUID) row.get("id"), family = (UUID) row.get("family_id");
                    UUID web = (UUID) row.get("web_session_id");
                    UUID session = (UUID) row.get("tenant_session_id");
                    UUID tenant = (UUID) row.get("tenant_id");
                    UUID user = (UUID) row.get("user_id");
                    if (!"ACTIVE".equals(row.get("user_status"))
                        || !"ACTIVE".equals(row.get("web_state"))
                        || ((Number) row.get("web_version")).longValue()
                            != ((Number) row.get("user_version")).longValue()
                        || row.get("web_revoked") != null
                        || !((OffsetDateTime) row.get("web_expires")).toInstant().isAfter(now)
                        || (session != null
                            && (row.get("tenant_revoked") != null
                                || !((OffsetDateTime) row.get("tenant_expires"))
                                    .toInstant()
                                    .isAfter(now)))) return Mono.error(denied());
                    // Check the original binding before either rotation or response replay. A
                    // receipt
                    // cannot upgrade an old session to the actor's new membership generation.
                    if (!((OffsetDateTime) row.get("expires_at")).toInstant().isAfter(now)
                        || (tenant != null
                            && (currentGeneration < 1
                                || currentGeneration
                                    != ((Number) row.get("membership_version")).longValue())))
                      return Mono.error(denied());
                    if (row.get("revoked_at") != null)
                      return replayRotation(row, old, key, currentGeneration, now)
                          .switchIfEmpty(
                              revokeFamily(row, now).thenReturn(new RotationResult(null, true)));
                    Instant expires = ((OffsetDateTime) row.get("expires_at")).toInstant();
                    UUID nextId = UUID.randomUUID();
                    Token next = token();
                    Sealed receipt = seal(old, key, next.raw());
                    return insertRotated(row, nextId, next, now, expires)
                        .then(
                            db.sql(
                                    "UPDATE taska.refresh_tokens SET"
                                        + " revoked_at=:now,replaced_by=:next WHERE id=:old AND"
                                        + " revoked_at IS NULL")
                                .bind("now", now.atOffset(ZoneOffset.UTC))
                                .bind("next", nextId)
                                .bind("old", old)
                                .fetch()
                                .rowsUpdated())
                        .flatMap(
                            changed -> changed == 1 ? Mono.just(changed) : Mono.error(denied()))
                        .then(
                            db.sql(
                                    "INSERT INTO taska.refresh_rotation_receipts"
                                        + "(old_token_id,idempotency_key,nonce,ciphertext,created_at,expires_at)"
                                        + " VALUES (:old,:key,:nonce,:ciphertext,:now,:expires)")
                                .bind("old", old)
                                .bind("key", key)
                                .bind("nonce", receipt.nonce())
                                .bind("ciphertext", receipt.ciphertext())
                                .bind("now", now.atOffset(ZoneOffset.UTC))
                                .bind("expires", now.plusSeconds(60).atOffset(ZoneOffset.UTC))
                                .fetch()
                                .rowsUpdated())
                        .thenReturn(
                            new RotationResult(
                                new Issued(
                                    web,
                                    session == null ? web : session,
                                    user,
                                    tenant,
                                    currentGeneration,
                                    family,
                                    next.raw(),
                                    expires,
                                    ((Number) row.get("user_version")).longValue(),
                                    now),
                                false));
                  })
              .as(tx::transactional)
              .flatMap(
                  result -> result.rejected() ? Mono.error(denied()) : Mono.just(result.issued()));
        });
  }

  private Mono<Long> insertRotated(
      Map<String, Object> row, UUID id, Token next, Instant now, Instant expires) {
    var statement =
        db.sql(
                "INSERT INTO taska.refresh_tokens"
                    + "(id,user_id,token_hash,issued_at,expires_at,created_at,web_session_id,tenant_session_id,family_id)"
                    + " VALUES (:id,:user,:hash,:now,:expires,:now,:web,:tenant,:family)")
            .bind("id", id)
            .bind("user", (UUID) row.get("user_id"))
            .bind("hash", Base64.getEncoder().encodeToString(next.hash()))
            .bind("now", now.atOffset(ZoneOffset.UTC))
            .bind("expires", expires.atOffset(ZoneOffset.UTC))
            .bind("web", (UUID) row.get("web_session_id"))
            .bind("family", (UUID) row.get("family_id"));
    statement =
        row.get("tenant_session_id") == null
            ? statement.bindNull("tenant", UUID.class)
            : statement.bind("tenant", (UUID) row.get("tenant_session_id"));
    return statement.fetch().rowsUpdated();
  }

  private Mono<RotationResult> replayRotation(
      Map<String, Object> row, UUID old, UUID key, long currentGeneration, Instant now) {
    return db.sql(
            "SELECT nonce,ciphertext,created_at,expires_at FROM taska.refresh_rotation_receipts"
                + " WHERE old_token_id=:old AND idempotency_key=:key")
        .bind("old", old)
        .bind("key", key)
        .fetch()
        .one()
        .flatMap(
            receipt -> {
              if (receipt.get("ciphertext") == null
                  || !((OffsetDateTime) receipt.get("expires_at")).toInstant().isAfter(now))
                return Mono.error(failure(DomainStatus.ABORTED, "INVALID_STATE"));
              UUID web = (UUID) row.get("web_session_id");
              UUID tenantSession = (UUID) row.get("tenant_session_id");
              return Mono.just(
                  new RotationResult(
                      new Issued(
                          web,
                          tenantSession == null ? web : tenantSession,
                          (UUID) row.get("user_id"),
                          (UUID) row.get("tenant_id"),
                          currentGeneration,
                          (UUID) row.get("family_id"),
                          unseal(
                              old,
                              key,
                              bytes(receipt.get("nonce")),
                              bytes(receipt.get("ciphertext"))),
                          ((OffsetDateTime) row.get("expires_at")).toInstant(),
                          ((Number) row.get("user_version")).longValue(),
                          ((OffsetDateTime) receipt.get("created_at")).toInstant()),
                      false));
            });
  }

  private Mono<Void> revokeFamily(Map<String, Object> row, Instant now) {
    UUID session = (UUID) row.get("tenant_session_id");
    // A tenant refresh family is independent: reuse in tab A must not revoke tab B.
    Mono<?> revokeSession =
        session == null
            ? db.sql("UPDATE taska.web_sessions SET state='REVOKED',revoked_at=:now WHERE id=:id")
                .bind("now", now.atOffset(ZoneOffset.UTC))
                .bind("id", (UUID) row.get("web_session_id"))
                .fetch()
                .rowsUpdated()
            : db.sql(
                    "UPDATE taska.tenant_sessions SET revoked_at=:now,version=version+1"
                        + " WHERE id=:id AND revoked_at IS NULL")
                .bind("now", now.atOffset(ZoneOffset.UTC))
                .bind("id", session)
                .fetch()
                .rowsUpdated();
    return revokeSession
        .then(
            db.sql(
                    "UPDATE taska.refresh_tokens SET revoked_at=:now"
                        + " WHERE family_id=:family AND revoked_at IS NULL")
                .bind("now", now.atOffset(ZoneOffset.UTC))
                .bind("family", (UUID) row.get("family_id"))
                .fetch()
                .rowsUpdated())
        .then();
  }

  public Mono<Void> revokeTenant(UUID userId, UUID sessionId) {
    return Mono.defer(
        () -> {
          Instant now = clock.instant();
          return db.sql(
                  "UPDATE taska.tenant_sessions SET revoked_at=:now,version=version+1"
                      + " WHERE id=:session AND user_id=:user AND revoked_at IS NULL")
              .bind("now", now.atOffset(ZoneOffset.UTC))
              .bind("session", sessionId)
              .bind("user", userId)
              .fetch()
              .rowsUpdated()
              .flatMap(
                  updated ->
                      updated == 1
                          ? db.sql(
                                  "UPDATE taska.refresh_tokens SET revoked_at=:now"
                                      + " WHERE tenant_session_id=:session AND revoked_at IS NULL")
                              .bind("now", now.atOffset(ZoneOffset.UTC))
                              .bind("session", sessionId)
                              .fetch()
                              .rowsUpdated()
                              .then()
                          : db.sql(
                                  "SELECT id FROM taska.tenant_sessions WHERE id=:session AND"
                                      + " user_id=:user AND revoked_at IS NOT NULL")
                              .bind("session", sessionId)
                              .bind("user", userId)
                              .fetch()
                              .one()
                              .switchIfEmpty(
                                  Mono.error(failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
                              .then())
              .as(tx::transactional);
        });
  }

  /** Revoke only the authenticated parent web session and its children, preserving other logins. */
  public Mono<Void> revokeWeb(UUID userId, UUID webSessionId) {
    return Mono.defer(
        () -> {
          Instant now = clock.instant();
          return db.sql(
                  "SELECT id FROM taska.web_sessions WHERE id=:web AND user_id=:user FOR UPDATE")
              .bind("web", webSessionId)
              .bind("user", userId)
              .fetch()
              .one()
              .switchIfEmpty(Mono.error(failure(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND")))
              .flatMap(
                  found ->
                      db.sql(
                              "UPDATE taska.web_sessions SET state='REVOKED',revoked_at=:now"
                                  + " WHERE id=:web AND state='ACTIVE'")
                          .bind("now", now.atOffset(ZoneOffset.UTC))
                          .bind("web", webSessionId)
                          .fetch()
                          .rowsUpdated()
                          .then(
                              db.sql(
                                      "UPDATE taska.tenant_sessions SET"
                                          + " revoked_at=:now,version=version+1 WHERE"
                                          + " web_session_id=:web AND revoked_at IS NULL")
                                  .bind("now", now.atOffset(ZoneOffset.UTC))
                                  .bind("web", webSessionId)
                                  .fetch()
                                  .rowsUpdated())
                          .then(
                              db.sql(
                                      "UPDATE taska.refresh_tokens SET revoked_at=:now"
                                          + " WHERE web_session_id=:web AND revoked_at IS NULL")
                                  .bind("now", now.atOffset(ZoneOffset.UTC))
                                  .bind("web", webSessionId)
                                  .fetch()
                                  .rowsUpdated())
                          .then())
              .as(tx::transactional);
        });
  }

  public Mono<Void> revokeAll(UUID userId) {
    return Mono.defer(
        () -> {
          Instant now = clock.instant();
          return db.sql("UPDATE taska.users SET auth_version=auth_version+1 WHERE id=:user")
              .bind("user", userId)
              .fetch()
              .rowsUpdated()
              .then(
                  db.sql(
                          "UPDATE taska.web_sessions SET state='REVOKED',revoked_at=:now"
                              + " WHERE user_id=:user AND state='ACTIVE'")
                      .bind("now", now.atOffset(ZoneOffset.UTC))
                      .bind("user", userId)
                      .fetch()
                      .rowsUpdated())
              .then(
                  db.sql(
                          "UPDATE taska.tenant_sessions SET revoked_at=:now,version=version+1"
                              + " WHERE user_id=:user AND revoked_at IS NULL")
                      .bind("now", now.atOffset(ZoneOffset.UTC))
                      .bind("user", userId)
                      .fetch()
                      .rowsUpdated())
              .then(
                  db.sql(
                          "UPDATE taska.refresh_tokens SET revoked_at=:now WHERE user_id=:user AND"
                              + " web_session_id IS NOT NULL AND revoked_at IS NULL")
                      .bind("now", now.atOffset(ZoneOffset.UTC))
                      .bind("user", userId)
                      .fetch()
                      .rowsUpdated())
              .then()
              .as(tx::transactional);
        });
  }
}
