package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.entity.*;
import ru.taska.event.*;
import ru.taska.mapper.AdminInviteUserMapper;
import ru.taska.repository.*;
import java.time.Instant;

/** The canonical email mutex is shared with administrative invitations and social signup. */
@Service
@RequiredArgsConstructor
public class EmailSignupTransaction {
    private final DatabaseClient database;
    private final UserRepository users;
    private final InviteTokenRepository tokens;
    private final InviteTokenService tokenService;
    private final AdminInviteUserMapper mapper;
    private final OutboxEventRepository outbox;
    private final tools.jackson.databind.ObjectMapper json;

    @Transactional
    public Mono<Void> signup(String email, String name, String requestId) {
        return database.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email, 0))")
                .bind("email", email).map((r,m) -> true).one()
                .then(reserveEmail(email))
                .then(Mono.defer(() -> users.findByCanonicalEmailForUpdate(email).singleOptional()))
                .flatMap(found -> {
                    if (found.isEmpty()) return create(email, name, requestId);
                    User user = found.get();
                    if (user.getStatus() == UserStatus.INVITED && user.getCreatedVia() == UserCreatedVia.SIGNUP) {
                        return tokens.findLatestByUserId(user.getId()).singleOptional().flatMap(latest -> {
                            if (latest.isPresent() && !latest.get().getExpiresAt().isAfter(Instant.now())) {
                                return users.delete(user).then(Mono.defer(() -> create(email, name, requestId)));
                            }
                            return resend(user, requestId);
                        });
                    }
                    return switch (user.getStatus()) {
                        case INVITED -> resend(user, requestId);
                        case ACTIVE, LOCKED -> alreadyRegistered(user, requestId);
                        case BLOCKED -> Mono.empty();
                    };
                });
    }

    private Mono<Void> reserveEmail(String email) {
        String bucket = "email:" + DigestUtils.sha256Hex(email);
        return database.sql("""
                INSERT INTO taska.signup_rate_limits AS current (bucket, started_at, attempts)
                VALUES (:bucket, now(), 1)
                ON CONFLICT (bucket) DO UPDATE SET started_at=now(), attempts=1
                WHERE current.started_at <= now()-interval '60 seconds'
                RETURNING attempts
                """).bind("bucket", bucket).map((r,m) -> r.get("attempts", Integer.class)).one()
                .switchIfEmpty(database.sql("SELECT started_at FROM taska.signup_rate_limits WHERE bucket=:bucket")
                        .bind("bucket", bucket).map((r,m) -> r.get("started_at", Instant.class)).one()
                        .flatMap(start -> Mono.error(SignupIpLimiter.limited(start.plusSeconds(60)))))
                .then();
    }
    private Mono<Void> create(String email, String name, String requestId) {
        return users.save(User.builder().email(email).displayName(name).createdVia(UserCreatedVia.SIGNUP)
                        .status(UserStatus.INVITED).build())
                .flatMap(user -> queueInvitation(user, requestId));
    }
    private Mono<Void> resend(User user, String requestId) {
        return tokens.findLatestByUserId(user.getId()).singleOptional().flatMap(latest -> {
            if (latest.isPresent() && latest.get().getCreatedAt().plusSeconds(60).isAfter(Instant.now())) {
                return Mono.empty();
            }
            return queueInvitation(user, requestId);
        });
    }
    private Mono<Void> queueInvitation(User user, String requestId) {
        return tokens.revokeUnusedForUser(user.getId()).then(tokenService.createInviteToken(user.getId()))
                .flatMap(token -> {
                    var event = mapper.buildUserInvitedOutboxEvent(token, user.getEmail(), requestId);
                    ((tools.jackson.databind.node.ObjectNode) event.getPayload()).put("emailKind",
                            user.getCreatedVia() == UserCreatedVia.SIGNUP ? "SIGNUP" : "INVITATION");
                    return outbox.save(event);
                }).then();
    }
    private Mono<Void> alreadyRegistered(User user, String requestId) {
        return outbox.save(OutboxEvent.builder().aggregateType(AggregateType.USER.getValue())
                .aggregateId(user.getId()).eventType(EventType.USER_SIGNUP_EXISTING_ACCOUNT.getValue())
                .payload(json.createObjectNode().put("email", user.getEmail()))
                .requestId(requestId).attempts(0).build()).then();
    }
}
