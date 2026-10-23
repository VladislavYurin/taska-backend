package ru.taska.service;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.taska.event.TaskaEvent;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Durable, private invitation-email outbox. SMTP is at-least-once; activation remains one-time. */
@Service
@Slf4j
@RequiredArgsConstructor
public class InvitationEmailQueue {
    private final DatabaseClient database;
    private final JavaMailSender mail;
    private final EmailMessageFactory emailMessages;
    private final MeterRegistry metrics;
    @Value("${notification.invitation.accept-url:http://localhost:5173/}")
    private String acceptUrl;

    private record Delivery(UUID userId, UUID eventId, UUID leaseId, String email, String token, Instant expiresAt) { }

    @PostConstruct
    void validateAcceptUrl() {
        URI uri = URI.create(acceptUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                || UriComponentsBuilder.fromUri(uri).build().getQueryParams().containsKey("token")) {
            throw new IllegalArgumentException("Invitation accept URL must use HTTPS (HTTP allowed only on localhost) and no credentials, fragment or token");
        }
    }

    public Mono<Void> enqueue(TaskaEvent event) {
        var payload = event.payload();
        if (payload == null || payload.get("rawInviteToken") == null || payload.get("expiresAt") == null) {
            // Old events had no token on the wire. They remain valid inbox events but cannot be delivered as invitations.
            return reject(event, "missing_credentials");
        }
        if (event.id() == null || event.aggregateId() == null || event.occurredAt() == null
                || !payload.path("rawInviteToken").isString() || !payload.path("email").isString()
                || !payload.path("expiresAt").isString()) {
            return reject(event, "invalid_payload");
        }
        String token = payload.get("rawInviteToken").stringValue();
        String email = payload.get("email").stringValue();
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(payload.get("expiresAt").stringValue());
        } catch (DateTimeParseException error) {
            return reject(event, "invalid_payload");
        }
        if (!token.matches("[A-Za-z0-9_-]{43}") || email.isBlank() || email.length() > 320
                || email.codePoints().anyMatch(Character::isISOControl)) {
            return reject(event, "invalid_payload");
        }
        return database.sql("""
                INSERT INTO taska.invitation_email_deliveries AS current
                    (user_id, event_id, email, token, issued_at, expires_at, status)
                VALUES (:user, :event, :email, CASE WHEN :expires > now() THEN :token ELSE NULL END,
                        :issued, :expires, CASE WHEN :expires > now() THEN 'PENDING' ELSE 'EXPIRED' END)
                ON CONFLICT (user_id) DO UPDATE SET event_id=excluded.event_id, email=excluded.email,
                    token=excluded.token, issued_at=excluded.issued_at, expires_at=excluded.expires_at,
                    status=excluded.status, attempts=0, next_attempt_at=now(), last_attempt_at=NULL, lease_id=NULL
                WHERE current.issued_at < excluded.issued_at
                """).bind("user", event.aggregateId()).bind("event", event.id()).bind("email", email)
                .bind("token", token).bind("issued", event.occurredAt()).bind("expires", expiresAt)
                .fetch().rowsUpdated().then();
    }

    private Mono<Void> reject(TaskaEvent event, String reason) {
        // Permanent data errors must not poison Kafka retries; database errors below still propagate.
        return Mono.fromRunnable(() -> {
            metrics.counter("taska.invitation.delivery.rejected", "reason", reason).increment();
            log.warn("Invitation delivery event rejected: eventId={}, reason={}", event.id(), reason);
        });
    }

    public Mono<Void> dispatchBatch() {
        UUID lease = UUID.randomUUID();
        return database.sql("""
                UPDATE taska.invitation_email_deliveries SET token=NULL, lease_id=NULL,
                    status=CASE WHEN expires_at <= now() THEN 'EXPIRED' ELSE 'FAILED' END
                WHERE token IS NOT NULL AND (expires_at <= now() OR
                    (attempts >= 8 AND status='PROCESSING' AND last_attempt_at < now() - interval '2 minutes'))
                """).fetch().rowsUpdated().thenMany(database.sql("""
                WITH due AS (
                    SELECT user_id FROM taska.invitation_email_deliveries
                    WHERE expires_at > now() AND attempts < 8 AND
                        ((status IN ('PENDING','RETRY') AND next_attempt_at <= now()) OR
                         (status='PROCESSING' AND last_attempt_at < now() - interval '2 minutes'))
                    ORDER BY next_attempt_at, user_id LIMIT 10 FOR UPDATE SKIP LOCKED
                )
                UPDATE taska.invitation_email_deliveries d SET status='PROCESSING', lease_id=:lease,
                    attempts=d.attempts+1, last_attempt_at=now()
                FROM due WHERE d.user_id=due.user_id
                RETURNING d.user_id, d.event_id, d.email, d.token, d.expires_at
                """).bind("lease", lease).map((row, metadata) -> new Delivery(row.get("user_id", UUID.class),
                        row.get("event_id", UUID.class), lease, row.get("email", String.class),
                        row.get("token", String.class), row.get("expires_at", Instant.class))).all())
                .flatMap(this::send, 4).then();
    }

    private Mono<Void> send(Delivery delivery) {
        return Mono.fromRunnable(() -> {
                    String link = UriComponentsBuilder.fromUriString(acceptUrl).queryParam("token", delivery.token())
                            .build().encode().toUriString();
                    var message = emailMessages.invitation(delivery.email(), link, delivery.expiresAt());
                    mail.send(message);
                }).subscribeOn(Schedulers.boundedElastic())
                .then(finish(delivery, true))
                .onErrorResume(error -> {
                    // SMTP exceptions can contain the message body. Never log them or persist their text.
                    log.warn("Invitation email delivery failed: eventId={}, errorType={}",
                            delivery.eventId(), error.getClass().getSimpleName());
                    return finish(delivery, false);
                });
    }

    private Mono<Void> finish(Delivery delivery, boolean success) {
        return database.sql(success ? """
                UPDATE taska.invitation_email_deliveries SET status=CASE WHEN expires_at <= now() THEN 'EXPIRED' ELSE 'SENT' END,
                    token=NULL, lease_id=NULL
                WHERE user_id=:user AND event_id=:event AND lease_id=:lease
                """ : """
                UPDATE taska.invitation_email_deliveries SET status=CASE WHEN attempts >= 8 THEN 'FAILED' ELSE 'RETRY' END,
                    token=CASE WHEN attempts >= 8 THEN NULL ELSE token END,
                    next_attempt_at=now() + make_interval(secs => LEAST(3600, 60 * POWER(2, attempts-1))), lease_id=NULL
                WHERE user_id=:user AND event_id=:event AND lease_id=:lease
                """).bind("user", delivery.userId()).bind("event", delivery.eventId()).bind("lease", delivery.leaseId())
                .fetch().rowsUpdated().then();
    }
}
