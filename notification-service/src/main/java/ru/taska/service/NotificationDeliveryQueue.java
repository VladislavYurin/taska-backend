package ru.taska.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.config.props.TelegramProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.repository.NotificationRepository;

/** Durable per-channel outbox. Network calls occur outside the inbox transaction and claim transaction. */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryQueue {
    private final DatabaseClient db;
    private final NotificationRepository notifications;
    private final NotificationSettingsService settings;
    private final TelegramSender telegram;
    private final TelegramProperties properties;
    private final JavaMailSender mail;
    private final EmailMessageFactory emailMessages;
    private final MeterRegistry metrics;
    @Value("${notification.delivery.max-attempts:8}") private int maxAttempts = 8;
    private final java.util.Map<String,java.util.concurrent.atomic.AtomicLong> queueDepth = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String,java.util.concurrent.atomic.AtomicLong> queueAge = new java.util.concurrent.ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    public void validate() {
        if(maxAttempts<1 || maxAttempts>20) throw new IllegalArgumentException("Notification delivery max attempts must be 1..20");
    }

    public Mono<Void> cleanup() {
        return db.sql("DELETE FROM taska.telegram_link_requests WHERE expires_at<now()-interval '1 day'")
                .fetch().rowsUpdated().then(db.sql("DELETE FROM taska.telegram_updates WHERE processed_at<now()-interval '7 days'")
                        .fetch().rowsUpdated()).then(db.sql("""
                    UPDATE taska.notification_deliveries SET status='CANCELLED',lease_id=NULL,updated_at=now()
                    WHERE reply_chat_id IS NOT NULL AND created_at<now()-interval '1 hour' AND status IN ('PENDING','RETRY')
                    """).fetch().rowsUpdated()).then(observe());
    }

    public Mono<Void> observe() {
        return db.sql("""
                SELECT c.channel, count(d.id) AS depth,
                    COALESCE(EXTRACT(EPOCH FROM now()-min(d.created_at))::bigint,0) AS age
                FROM (VALUES ('EMAIL'),('TELEGRAM')) c(channel)
                LEFT JOIN taska.notification_deliveries d ON d.channel=c.channel AND d.status IN ('PENDING','RETRY','PROCESSING')
                GROUP BY c.channel
                """).map((r,m) -> {
                    String channel=r.get("channel",String.class);
                    queueDepth.computeIfAbsent(channel,c -> gauge("taska.notification.queue.depth",c)).set(r.get("depth",Long.class));
                    queueAge.computeIfAbsent(channel,c -> gauge("taska.notification.queue.age.seconds",c)).set(r.get("age",Long.class));
                    return true;
                }).all().then();
    }

    private java.util.concurrent.atomic.AtomicLong gauge(String name,String channel) {
        var value=new java.util.concurrent.atomic.AtomicLong();
        metrics.gauge(name,io.micrometer.core.instrument.Tags.of("channel",channel),value);
        return value;
    }

    private record Delivery(UUID id, UUID lease, UUID notification, UUID user, String channel,
            UUID version, Long replyChat, String replyText, String bot, int attempts) { }
    private record Target(String email, Long chat) { }

    public Mono<Void> enqueue(Notification notification) {
        if (notification.getNotificationType() == NotificationType.USER_INVITED) return Mono.empty();
        return db.sql("""
                INSERT INTO taska.notification_deliveries(dedup_key,notification_id,user_id,channel,channel_version,bot_username)
                SELECT :id::text || ':EMAIL', :id, user_id, 'EMAIL', email_version, NULL
                    FROM taska.notification_preferences WHERE user_id=:user AND email_enabled AND email IS NOT NULL
                UNION ALL
                SELECT :id::text || ':TELEGRAM', :id, user_id, 'TELEGRAM', telegram_version, telegram_bot_username
                    FROM taska.notification_preferences WHERE user_id=:user AND telegram_enabled
                        AND telegram_chat_id IS NOT NULL AND telegram_error IS NULL
                        AND :telegramAvailable AND telegram_bot_username=:bot
                ON CONFLICT DO NOTHING
                """).bind("id", notification.getId()).bind("user", notification.getUserId())
                .bind("telegramAvailable", properties.isEnabled()
                        && notification.getNotificationType() != NotificationType.PERSONAL_ACCESS_TOKEN_CREATED
                        && notification.getNotificationType() != NotificationType.PERSONAL_ACCESS_TOKEN_REVOKED)
                .bind("bot", java.util.Objects.toString(properties.getBotUsername(), ""))
                .fetch().rowsUpdated().then();
    }

    public Mono<Void> dispatchEmail() { return dispatch("EMAIL", 10); }

    public Mono<Void> dispatchTelegram() {
        if (!properties.isEnabled()) return Mono.empty();
        // Distributed conservative limit: at most one request per 1.1s across all instances/chats.
        return db.sql("""
                UPDATE taska.notification_channel_throttle SET next_send_at=now()+interval '1100 milliseconds'
                WHERE channel='TELEGRAM' AND next_send_at<=now()
                """).fetch().rowsUpdated().flatMap(count -> count == 1 ? dispatch("TELEGRAM", 1) : Mono.empty());
    }

    private Mono<Void> dispatch(String channel, int limit) {
        return Mono.defer(() -> {
            UUID lease = UUID.randomUUID();
            return db.sql("""
                    UPDATE taska.notification_deliveries SET status='FAILED',lease_id=NULL,last_error='ATTEMPTS_EXHAUSTED',updated_at=now()
                    WHERE channel=:channel AND attempts>=:max AND status='PROCESSING'
                        AND last_attempt_at < now()-interval '2 minutes'
                    """).bind("channel", channel).bind("max", maxAttempts).fetch().rowsUpdated()
                    .thenMany(db.sql("""
                            WITH due AS (
                                SELECT id FROM taska.notification_deliveries WHERE channel=:channel AND attempts<:max
                                  AND ((status IN ('PENDING','RETRY') AND next_attempt_at<=now())
                                    OR (status='PROCESSING' AND last_attempt_at<now()-interval '2 minutes'))
                                ORDER BY next_attempt_at,id LIMIT :limit FOR UPDATE SKIP LOCKED
                            )
                            UPDATE taska.notification_deliveries d SET status='PROCESSING',lease_id=:lease,
                                attempts=d.attempts+1,last_attempt_at=now(),updated_at=now()
                            FROM due WHERE d.id=due.id
                            RETURNING d.*
                            """).bind("channel", channel).bind("max", maxAttempts).bind("limit", limit).bind("lease", lease)
                            .map((r, m) -> new Delivery(r.get("id", UUID.class), lease, r.get("notification_id", UUID.class),
                                    r.get("user_id", UUID.class), r.get("channel", String.class), r.get("channel_version", UUID.class),
                                    r.get("reply_chat_id", Long.class), r.get("reply_text", String.class), r.get("bot_username", String.class),
                                    r.get("attempts", Integer.class))).all())
                    .flatMap(this::send, channel.equals("EMAIL") ? 4 : 1).then();
        });
    }

    private Mono<Void> send(Delivery d) {
        Mono<Boolean> delivered;
        if (d.replyChat() != null) {
            delivered = properties.getBotUsername().equals(d.bot())
                    ? telegram.reply(d.replyChat(), d.replyText()).thenReturn(true) : Mono.just(false);
        } else {
            delivered = target(d).flatMap(target -> notifications.findById(d.notification()).flatMap(n -> {
                Mono<Void> send = "EMAIL".equals(d.channel())
                        ? Mono.fromRunnable(() -> mail.send(emailMessages.notification(n, target.email())))
                                .subscribeOn(Schedulers.boundedElastic()).then()
                        : telegram.notification(target.chat(), n);
                return send.thenReturn(true);
            })).defaultIfEmpty(false);
        }
        // Keep database completion failures out of the transport error handler: the lease recovers them.
        return delivered.onErrorResume(error -> failure(d, error).then(Mono.<Boolean>empty()))
                .flatMap(sent -> finish(d, sent ? "SENT" : "CANCELLED", null, 0));
    }

    private Mono<Target> target(Delivery d) {
        String condition = "EMAIL".equals(d.channel())
                ? "p.email_enabled AND p.email_version=:version AND p.email IS NOT NULL"
                : "p.telegram_enabled AND p.telegram_version=:version AND p.telegram_chat_id IS NOT NULL AND p.telegram_error IS NULL AND p.telegram_bot_username=:bot";
        var query = db.sql("SELECT p.email,p.telegram_chat_id FROM taska.notification_preferences p "
                        + "JOIN taska.notification_deliveries d ON d.user_id=p.user_id "
                        + "WHERE d.id=:id AND d.lease_id=:lease AND d.status='PROCESSING' AND " + condition)
                .bind("id", d.id()).bind("lease", d.lease()).bind("version", d.version());
        if ("TELEGRAM".equals(d.channel())) query = query.bind("bot", properties.getBotUsername());
        return query.map((r,m) -> new Target(r.get("email", String.class),r.get("telegram_chat_id", Long.class))).one();
    }

    private Mono<Void> failure(Delivery d, Throwable error) {
        DeliveryFailure safe = error instanceof DeliveryFailure f ? f
                : new DeliveryFailure("EMAIL_TEMPORARY", false, false, 60);
        String status = safe.permanent() || d.attempts() >= maxAttempts ? "FAILED" : "RETRY";
        long delay = Math.max(safe.retryAfter(), Math.min(3600, 60L << Math.min(6, d.attempts()-1)));
        Mono<Void> cooldown = "TELEGRAM_RATE_LIMIT".equals(safe.getMessage())
                ? db.sql("UPDATE taska.notification_channel_throttle SET next_send_at=GREATEST(next_send_at,now()+make_interval(secs=>:delay)) WHERE channel='TELEGRAM'")
                        .bind("delay", safe.retryAfter()).fetch().rowsUpdated().then() : Mono.empty();
        Mono<Void> unavailable = safe.disconnected() && d.user() != null
                ? db.sql("""
                        UPDATE taska.notification_preferences SET telegram_enabled=false,telegram_error=:code,
                            telegram_version=gen_random_uuid(),updated_at=now()
                        WHERE user_id=:user AND telegram_version=:version
                        """).bind("code", safe.getMessage()).bind("user", d.user()).bind("version", d.version())
                        .fetch().rowsUpdated().then(settings.cancelObsolete(d.user())) : Mono.empty();
        // Finalize the failed item before cancellation of siblings on a permanently unavailable binding.
        return finish(d, status, safe.getMessage(), delay).then(cooldown).then(unavailable);
    }

    private Mono<Void> finish(Delivery d, String status, String error, long delay) {
        var query = db.sql("""
                UPDATE taska.notification_deliveries SET status=:status,lease_id=NULL,last_error=:error,
                    next_attempt_at=now()+make_interval(secs=>:delay),updated_at=now()
                WHERE id=:id AND lease_id=:lease AND status='PROCESSING'
                """).bind("status", status).bind("delay", delay).bind("id", d.id()).bind("lease", d.lease());
        query = error == null ? query.bindNull("error", String.class) : query.bind("error", error);
        return query.fetch().rowsUpdated().doOnNext(count -> {
            if (count == 1) metrics.counter("taska.notification.delivery", "channel", d.channel(), "status", status).increment();
        }).then();
    }
}
