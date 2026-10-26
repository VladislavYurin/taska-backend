package ru.taska.service;

import io.r2dbc.spi.Row;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.config.props.TelegramProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Owns preferences and Telegram binding. User rows serialize all binding mutations. */
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final TelegramProperties telegram;

    public record Preferences(boolean emailEnabled, boolean telegramEnabled, String email,
            boolean telegramAvailable, String telegramStatus, String telegramUsername, String telegramName,
            UUID telegramLinkId, Instant telegramLinkExpiresAt, String telegramError) { }
    public record Link(UUID linkId, String url, Instant expiresAt, Preferences preferences) { }

    public Mono<Preferences> get(UUID user, String email) {
        if (!validEmail(email)) return Mono.error(invalid("A verified account email is required"));
        // Ordinary polling is read-only. Provision only missing rows or a changed verified address.
        return view(user).filter(current -> email.equals(current.email()))
                .switchIfEmpty(Mono.defer(() -> ensure(user, email).then(view(user)).as(tx::transactional)));
    }

    public Mono<Preferences> update(UUID user, String email, Boolean emailEnabled, Boolean telegramEnabled) {
        if (emailEnabled == null && telegramEnabled == null) return Mono.error(invalid("Specify a notification channel"));
        return ensure(user, email).then(lock(user)).then(view(user)).flatMap(current -> {
            if (Boolean.TRUE.equals(telegramEnabled)
                    && (!telegram.isEnabled() || !"CONNECTED".equals(current.telegramStatus()))) {
                return Mono.error(conflict("Connect and confirm Telegram before enabling notifications"));
            }
            var statement = db.sql("""
                    UPDATE taska.notification_preferences SET
                        email_version=CASE WHEN :emailEnabled IS NOT NULL AND email_enabled IS DISTINCT FROM :emailEnabled
                            THEN gen_random_uuid() ELSE email_version END,
                        telegram_version=CASE WHEN :tgEnabled IS NOT NULL AND telegram_enabled IS DISTINCT FROM :tgEnabled
                            THEN gen_random_uuid() ELSE telegram_version END,
                        email_enabled=COALESCE(:emailEnabled,email_enabled),
                        telegram_enabled=COALESCE(:tgEnabled,telegram_enabled), updated_at=now()
                    WHERE user_id=:user
                    """).bind("user", user);
            statement = emailEnabled == null ? statement.bindNull("emailEnabled", Boolean.class) : statement.bind("emailEnabled", emailEnabled);
            statement = telegramEnabled == null ? statement.bindNull("tgEnabled", Boolean.class) : statement.bind("tgEnabled", telegramEnabled);
            return statement.fetch().rowsUpdated().then(cancelObsolete(user)).then(view(user));
        }).as(tx::transactional);
    }

    public Mono<Link> createLink(UUID user, String email) {
        if (!telegram.isEnabled()) return Mono.error(unavailable());
        return Mono.defer(() -> {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            UUID link = UUID.randomUUID();
            Instant expiry = Instant.now().plus(telegram.getLinkTtl()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            return ensure(user, email).then(lock(user)).then(view(user)).flatMap(current -> {
                if ("CONNECTED".equals(current.telegramStatus()) || "UNAVAILABLE".equals(current.telegramStatus())) {
                    return Mono.error(conflict("Disconnect the previous Telegram account first"));
                }
                return db.sql("""
                        INSERT INTO taska.telegram_link_requests(user_id,link_id,token_hash,expires_at,bot_username)
                        VALUES (:user,:link,:hash,:expiry,:bot)
                        ON CONFLICT(user_id) DO UPDATE SET link_id=excluded.link_id, token_hash=excluded.token_hash,
                            expires_at=excluded.expires_at, bot_username=excluded.bot_username, consumed_at=NULL, candidate_chat_id=NULL,
                            candidate_user_id=NULL, candidate_username=NULL, candidate_name=NULL
                        """).bind("user", user).bind("link", link).bind("hash", hash(token)).bind("expiry", expiry)
                        .bind("bot", telegram.getBotUsername())
                        .fetch().rowsUpdated()
                        .then(view(user)).map(preferences -> new Link(link,
                                "https://t.me/" + telegram.getBotUsername() + "?start=" + token, expiry, preferences));
            });
        }).as(tx::transactional);
    }

    public Mono<Preferences> confirm(UUID user, String email, UUID link) {
        if (!telegram.isEnabled()) return Mono.error(unavailable());
        return ensure(user, email).then(lock(user)).then(db.sql("""
                UPDATE taska.notification_preferences p SET telegram_chat_id=r.candidate_chat_id,
                    telegram_user_id=r.candidate_user_id, telegram_username=r.candidate_username,
                    telegram_name=r.candidate_name, telegram_bot_username=r.bot_username, telegram_enabled=true, telegram_error=NULL,
                    telegram_version=gen_random_uuid(), updated_at=now()
                FROM taska.telegram_link_requests r
                WHERE p.user_id=:user AND r.user_id=p.user_id AND r.link_id=:link
                    AND r.expires_at>now() AND r.consumed_at IS NOT NULL AND r.candidate_chat_id IS NOT NULL
                    AND r.bot_username=:bot
                """).bind("user", user).bind("link", link).bind("bot", telegram.getBotUsername()).fetch().rowsUpdated())
                .flatMap(count -> count == 1 ? deleteLink(user) : Mono.error(conflict("Telegram link expired or has not been started")))
                .then(cancelObsolete(user)).then(view(user)).as(tx::transactional)
                .onErrorMap(DuplicateKeyException.class, e -> conflict("This Telegram account is already connected"));
    }

    public Mono<Preferences> disconnect(UUID user, String email) {
        return ensure(user, email).then(lock(user)).then(disconnectUser(user))
                .then(view(user)).as(tx::transactional);
    }

    /** Also used for authenticated /stop from the bound Telegram identity. Caller holds the row lock. */
    Mono<Void> disconnectUser(UUID user) {
        return db.sql("""
                UPDATE taska.notification_preferences SET telegram_enabled=false, telegram_chat_id=NULL,
                    telegram_user_id=NULL, telegram_username=NULL, telegram_name=NULL, telegram_bot_username=NULL, telegram_error=NULL,
                    telegram_version=gen_random_uuid(), updated_at=now() WHERE user_id=:user
                """).bind("user", user).fetch().rowsUpdated().then(deleteLink(user)).then(cancelObsolete(user));
    }

    /** A token event may be delayed: provision missing preferences without replacing a newer verified email. */
    public Mono<Void> ensureMissing(UUID user, String email) {
        if (!validEmail(email)) return Mono.error(invalid("A verified account email is required"));
        return db.sql("INSERT INTO taska.notification_preferences(user_id,email) VALUES (:user,:email) ON CONFLICT(user_id) DO NOTHING")
                .bind("user", user).bind("email", email).fetch().rowsUpdated().then();
    }

    /** email comes only from auth's verified gateway context or trusted auth lifecycle events. */
    public Mono<Void> ensure(UUID user, String email) {
        if (!validEmail(email)) {
            return Mono.error(invalid("A verified account email is required"));
        }
        return db.sql("""
                INSERT INTO taska.notification_preferences(user_id,email) VALUES (:user,:email)
                ON CONFLICT(user_id) DO UPDATE SET email=excluded.email,
                    email_version=gen_random_uuid(), updated_at=now()
                WHERE taska.notification_preferences.email IS DISTINCT FROM excluded.email
                """).bind("user", user).bind("email", email).fetch().rowsUpdated().then();
    }

    Mono<Void> lock(UUID user) {
        return db.sql("SELECT user_id FROM taska.notification_preferences WHERE user_id=:user FOR UPDATE")
                .bind("user", user).map((r,m) -> r.get("user_id", UUID.class)).one().then();
    }

    private Mono<Void> deleteLink(UUID user) {
        return db.sql("DELETE FROM taska.telegram_link_requests WHERE user_id=:user")
                .bind("user", user).fetch().rowsUpdated().then();
    }

    Mono<Void> cancelObsolete(UUID user) {
        return db.sql("""
                UPDATE taska.notification_deliveries d SET status='CANCELLED',lease_id=NULL,updated_at=now()
                FROM taska.notification_preferences p WHERE d.user_id=:user AND p.user_id=d.user_id
                  AND d.status IN ('PENDING','RETRY','PROCESSING') AND
                  ((d.channel='EMAIL' AND (NOT p.email_enabled OR d.channel_version<>p.email_version)) OR
                   (d.channel='TELEGRAM' AND (NOT p.telegram_enabled OR p.telegram_chat_id IS NULL
                        OR d.channel_version<>p.telegram_version)))
                """).bind("user", user).fetch().rowsUpdated().then();
    }

    private Mono<Preferences> view(UUID user) {
        return db.sql("""
                SELECT p.*, r.link_id, r.expires_at, r.consumed_at, r.candidate_username, r.candidate_name
                FROM taska.notification_preferences p LEFT JOIN taska.telegram_link_requests r
                    ON r.user_id=p.user_id AND r.expires_at>now() AND r.bot_username=:bot WHERE p.user_id=:user
                """).bind("user", user).bind("bot", telegram.getBotUsername()).map((row, meta) -> preferences(row)).one();
    }

    private Preferences preferences(Row r) {
        boolean bound = r.get("telegram_chat_id", Long.class) != null;
        UUID link = r.get("link_id", UUID.class);
        boolean candidate = link != null && r.get("consumed_at", Instant.class) != null;
        String error = r.get("telegram_error", String.class);
        if (bound && telegram.isEnabled()
                && !telegram.getBotUsername().equals(r.get("telegram_bot_username", String.class))) error = "BOT_CHANGED";
        String status = bound ? (!telegram.isEnabled() || error != null ? "UNAVAILABLE" : "CONNECTED")
                : candidate ? "AWAITING_CONFIRMATION" : link != null ? "PENDING" : "NOT_CONNECTED";
        return new Preferences(Boolean.TRUE.equals(r.get("email_enabled", Boolean.class)),
                Boolean.TRUE.equals(r.get("telegram_enabled", Boolean.class)), r.get("email", String.class),
                telegram.isEnabled(), status, r.get(candidate ? "candidate_username" : "telegram_username", String.class),
                r.get(candidate ? "candidate_name" : "telegram_name", String.class), link,
                r.get("expires_at", Instant.class), error);
    }

    private static boolean validEmail(String email) {
        return email != null && !email.isBlank() && email.length() <= 320
                && email.codePoints().noneMatch(Character::isISOControl);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static DomainException conflict(String message) { return new DomainException(DomainStatus.ABORTED, message); }
    static DomainException invalid(String message) { return new DomainException(DomainStatus.INVALID_ARGUMENT, message); }
    static DomainException unavailable() { return new DomainException(DomainStatus.UNAVAILABLE, "Telegram notifications are not configured"); }
}
