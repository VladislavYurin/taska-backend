package ru.taska.security.pat;

import com.google.protobuf.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.AccessTokenKind;
import ru.taska.api.common.v1.UserContext;
import ru.taska.entity.OutboxEvent;
import ru.taska.entity.User;
import ru.taska.entity.UserStatus;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.event.OutboxEventStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.repository.UserRepository;
import ru.taska.util.UserContexts;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {
    public static final String PREFIX = "tsk_pat_";
    private static final Pattern FORMAT = Pattern.compile("tsk_pat_[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PersonalAccessTokenRepository tokens;
    private final UserRepository users;
    private final OutboxEventRepository outbox;
    private final PersonalTokenProperties policy;
    private final Clock personalTokenClock;
    private final ObjectMapper json;

    @Transactional
    public Mono<IssuedPersonalAccessToken> create(UUID owner, String name, List<String> scopes,
                                                 Integer expiresInDays, String requestId) {
        return Mono.defer(() -> {
            String normalizedName = name == null ? "" : name.trim();
            int days = expiresInDays == null ? 30 : expiresInDays;
            Set<String> requestedScopes = scopes == null ? Set.of() : new HashSet<>(scopes);
            if (normalizedName.isEmpty() || normalizedName.length() > 100) {
                return invalid("Token name must contain 1 to 100 characters");
            }
            if (!requestedScopes.equals(Set.of("read")) && !requestedScopes.equals(Set.of("read", "write"))) {
                return invalid("Token scopes must be read or read,write");
            }
            if (days < 1 || days > policy.getMaxLifetimeDays()) {
                return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT,
                        "Token lifetime is outside policy", Map.of("maxLifetimeDays", String.valueOf(policy.getMaxLifetimeDays()))));
            }
            Instant now = now();
            // Serialize issuance for a user: concurrent requests cannot both pass the active-count limit.
            return users.findByIdForUpdate(owner).filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .switchIfEmpty(Mono.error(unauthenticated()))
                    .flatMap(user -> tokens.countActive(owner, now).flatMap(count -> {
                        if (count >= policy.getMaxActiveTokens()) {
                            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                                    "Active token limit reached", Map.of("reason", "ACTIVE_TOKEN_LIMIT",
                                    "limit", String.valueOf(policy.getMaxActiveTokens()))));
                        }
                        byte[] entropy = new byte[32];
                        RANDOM.nextBytes(entropy);
                        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
                        var token = PersonalAccessToken.builder().userId(owner).name(normalizedName)
                                .tokenPrefix(secret.substring(0, PREFIX.length() + 4)).tokenHash(DigestUtils.sha256Hex(secret))
                                .scopes(requestedScopes.contains("write") ? new String[]{"read", "write"} : new String[]{"read"})
                                .createdAt(now).expiresAt(now.plus(days, ChronoUnit.DAYS)).build();
                        return tokens.save(token).flatMap(saved -> event(user, saved, EventType.PERSONAL_ACCESS_TOKEN_CREATED,
                                        null, requestId).thenReturn(new IssuedPersonalAccessToken(saved, secret)));
                    }));
        });
    }

    public Flux<PersonalAccessToken> list(UUID owner) { return tokens.listOwned(owner); }

    @Transactional
    public Mono<Void> revoke(UUID owner, UUID id, String requestId) {
        // JWT validation at the RPC boundary rejects blocked/deleted accounts; LOCKED owners may still revoke.
        // Unknown, foreign and already-revoked ids are all an idempotent no-op.
        return users.findByIdForUpdate(owner).switchIfEmpty(Mono.error(unauthenticated()))
                .flatMap(user -> tokens.revokeOwned(id, owner, now(), "OWNER_REVOKED")
                        .flatMap(token -> event(user, token, EventType.PERSONAL_ACCESS_TOKEN_REVOKED,
                                "OWNER_REVOKED", requestId))).then();
    }

    // Internal lifecycle hook intentionally accepts BLOCKED/INVITED users to invalidate their existing tokens.
    @Transactional
    public Mono<Void> revokeAll(User user, String reason, String requestId) {
        return tokens.revokeAll(user.getId(), now(), reason)
                .concatMap(token -> event(user, token, EventType.PERSONAL_ACCESS_TOKEN_REVOKED, reason, requestId)).then();
    }

    public Mono<UserContext> validate(String secret) {
        return Mono.defer(() -> {
            if (secret == null || !FORMAT.matcher(secret).matches()) return Mono.error(unauthenticated());
            String hash = DigestUtils.sha256Hex(secret);
            return tokens.findByTokenHash(hash)
                    .filter(token -> MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                            token.getTokenHash().getBytes(StandardCharsets.US_ASCII)))
                    .filter(token -> token.getRevokedAt() == null && token.getExpiresAt().isAfter(now()))
                    .switchIfEmpty(Mono.error(unauthenticated()))
                    .flatMap(token -> users.findById(token.getUserId())
                            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                            .switchIfEmpty(Mono.error(unauthenticated()))
                            .flatMap(user -> {
                                Instant usedAt = now();
                                return tokens.touchIfStale(token.getId(), usedAt, usedAt.minus(5, ChronoUnit.MINUTES))
                                        .thenReturn(UserContexts.builder(user).setTokenKind(AccessTokenKind.ACCESS_TOKEN_KIND_PAT)
                                                .setTokenId(token.getId().toString()).addAllScopes(List.of(token.getScopes()))
                                                .setTokenExpiresAt(timestamp(token.getExpiresAt())).build());
                            }));
        });
    }

    public PersonalTokenProperties policy() { return policy; }

    private Mono<Void> event(User user, PersonalAccessToken token, EventType type, String reason, String requestId) {
        var payload = json.createObjectNode().put("userId", user.getId().toString()).put("email", user.getEmail())
                .put("tokenId", token.getId().toString()).put("tokenPrefix", token.getTokenPrefix())
                .put("expiresAt", token.getExpiresAt().toString());
        if (reason != null) payload.put("reason", reason);
        return outbox.save(OutboxEvent.builder().aggregateType(AggregateType.USER.getValue()).aggregateId(user.getId())
                .eventType(type.getValue()).payload(payload).status(OutboxEventStatus.NEW).attempts(0)
                .createdAt(now()).requestId(requestId).build()).then();
    }

    private Instant now() { return personalTokenClock.instant().truncatedTo(ChronoUnit.MICROS); }
    public static Timestamp timestamp(Instant time) {
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond()).setNanos(time.getNano()).build();
    }
    private static <T> Mono<T> invalid(String message) {
        return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, message));
    }
    private static DomainException unauthenticated() {
        return new DomainException(DomainStatus.UNAUTHENTICATED, "Invalid or expired access token");
    }
}
