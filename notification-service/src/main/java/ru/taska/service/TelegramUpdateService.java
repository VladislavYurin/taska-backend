package ru.taska.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.config.props.TelegramProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Processes a minimal private-chat command subset; never persists raw updates or link tokens. */
@Service
@RequiredArgsConstructor
public class TelegramUpdateService {
    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final NotificationSettingsService settings;
    private final TelegramProperties telegram;
    private final ObjectMapper mapper;

    public Mono<Void> process(String secret, String json) {
        if (!telegram.isEnabled()) return Mono.error(NotificationSettingsService.unavailable());
        if (secret == null || !MessageDigest.isEqual(telegram.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8))) {
            return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED, "Invalid Telegram webhook credentials"));
        }
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 32768) {
            return Mono.error(NotificationSettingsService.invalid("Telegram update is too large"));
        }
        return Mono.defer(() -> {
            JsonNode update;
            try { update = mapper.readTree(json); }
            catch (RuntimeException error) { return Mono.error(NotificationSettingsService.invalid("Invalid Telegram update")); }
            if (update == null || !update.isObject() || !update.path("update_id").isIntegralNumber()
                    || !update.path("update_id").canConvertToLong() || update.path("update_id").longValue() < 0) {
                return Mono.error(NotificationSettingsService.invalid("Invalid Telegram update identifier"));
            }
            long updateId = update.path("update_id").longValue();
            return db.sql("""
                    INSERT INTO taska.telegram_updates(bot_username,update_id) VALUES (:bot,:id)
                    ON CONFLICT DO NOTHING
                    """).bind("bot", telegram.getBotUsername()).bind("id", updateId).fetch().rowsUpdated()
                    .flatMap(inserted -> inserted == 0 ? Mono.empty() : command(updateId, update.path("message")));
        }).as(tx::transactional);
    }

    private Mono<Void> command(long updateId, JsonNode message) {
        JsonNode from = message.path("from");
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asText("")) || from.path("is_bot").asBoolean(false)
                || !from.path("id").isIntegralNumber() || !from.path("id").canConvertToLong()
                || !chat.path("id").isIntegralNumber() || !chat.path("id").canConvertToLong()
                || !message.path("text").isString()) return Mono.empty();
        long user = from.path("id").longValue();
        long chatId = chat.path("id").longValue();
        if (user <= 0 || user != chatId) return Mono.empty();
        String text = message.path("text").asText().strip();
        if (text.equals("/stop")) {
            return db.sql("""
                    SELECT p.user_id FROM taska.notification_preferences p
                    LEFT JOIN taska.telegram_link_requests r ON p.user_id=r.user_id
                    WHERE (p.telegram_user_id=:tgUser AND p.telegram_chat_id=:chat)
                        OR (r.candidate_user_id=:tgUser AND r.candidate_chat_id=:chat)
                    ORDER BY p.user_id FOR UPDATE OF p
                    """).bind("tgUser", user).bind("chat", chatId)
                    .map((r, m) -> r.get("user_id", UUID.class)).all()
                    .concatMap(settings::disconnectUser).then(reply(updateId, chatId,
                            "Telegram notifications are disconnected. You can reconnect from your Taska profile."));
        }
        if (text.startsWith("/start ")) {
            String token = text.substring(7).strip();
            if (!token.matches("[A-Za-z0-9_-]{43}")) return invalidLink(updateId, chatId);
            String hash = NotificationSettingsService.hash(token);
            return db.sql("""
                    SELECT user_id FROM taska.telegram_link_requests WHERE token_hash=:hash
                        AND consumed_at IS NULL AND expires_at>now() AND bot_username=:bot
                    """).bind("hash", hash).bind("bot", telegram.getBotUsername()).map((r, m) -> r.get("user_id", UUID.class)).one()
                    .flatMap(owner -> settings.lock(owner).then(db.sql("""
                            UPDATE taska.telegram_link_requests SET consumed_at=now(),
                                candidate_chat_id=:chat, candidate_user_id=:tgUser,
                                candidate_username=:username, candidate_name=:name
                            WHERE user_id=:owner AND token_hash=:hash AND consumed_at IS NULL AND expires_at>now()
                                AND NOT EXISTS (SELECT 1 FROM taska.notification_preferences
                                    WHERE telegram_user_id=:tgUser OR telegram_chat_id=:chat)
                            """).bind("owner", owner).bind("hash", hash).bind("chat", chatId).bind("tgUser", user)
                            .bind("username", clipped(from.path("username").asText(""), 64))
                            .bind("name", clipped(from.path("first_name").asText("Telegram account"), 100))
                            .fetch().rowsUpdated())
                            .flatMap(count -> count == 1 ? reply(updateId, chatId,
                                    "Return to your Taska profile and confirm this Telegram account. Notifications are not enabled yet.")
                                    : invalidLink(updateId, chatId)).thenReturn(true))
                    .switchIfEmpty(Mono.defer(() -> invalidLink(updateId, chatId).thenReturn(false))).then();
        }
        if (text.equals("/start") || text.equals("/help")) {
            return reply(updateId, chatId, "Connect this bot from Taska: Settings → My profile → Notifications → Connect Telegram. Use /stop to disconnect.");
        }
        return Mono.empty();
    }

    private Mono<Void> invalidLink(long updateId, long chatId) {
        return reply(updateId, chatId, "This connection link is expired, already used or unavailable. Create a new link in your Taska profile.");
    }

    private Mono<Void> reply(long updateId, long chatId, String text) {
        return db.sql("""
                INSERT INTO taska.notification_deliveries(dedup_key,channel,reply_chat_id,reply_text,bot_username)
                VALUES (:key,'TELEGRAM',:chat,:text,:bot) ON CONFLICT DO NOTHING
                """).bind("key", "reply:" + telegram.getBotUsername() + ":" + updateId)
                .bind("chat", chatId).bind("text", text).bind("bot", telegram.getBotUsername()).fetch().rowsUpdated().then();
    }

    static String clipped(String value, int length) {
        String safe = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
        return safe.codePointCount(0, safe.length()) <= length ? safe : safe.substring(0, safe.offsetByCodePoints(0, length));
    }
}
