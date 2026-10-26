package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.SignupProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Committed separately, so rejected email attempts still count against the IP budget. */
@Service
@RequiredArgsConstructor
public class SignupIpLimiter {
    private final DatabaseClient database;
    private final SignupProperties properties;
    public Mono<Void> reserve(String ip) {
        String bucket = "ip:" + DigestUtils.sha256Hex(ip);
        return database.sql("""
                INSERT INTO taska.signup_rate_limits AS current (bucket, started_at, attempts)
                VALUES (:bucket, now(), 1)
                ON CONFLICT (bucket) DO UPDATE SET
                  started_at=CASE WHEN current.started_at <= now()-interval '1 hour' THEN now() ELSE current.started_at END,
                  attempts=CASE WHEN current.started_at <= now()-interval '1 hour' THEN 1 ELSE current.attempts+1 END
                WHERE current.started_at <= now()-interval '1 hour' OR current.attempts < :maximum
                RETURNING attempts
                """).bind("bucket", bucket).bind("maximum", properties.getIpAttemptsPerHour())
                .map((r, m) -> r.get("attempts", Integer.class)).one()
                .switchIfEmpty(database.sql("SELECT started_at FROM taska.signup_rate_limits WHERE bucket=:bucket")
                        .bind("bucket", bucket).map((r,m) -> r.get("started_at", Instant.class)).one()
                        .flatMap(start -> Mono.error(limited(start.plusSeconds(3600)))))
                .then();
    }
    static DomainException limited(Instant allowedAt) {
        long seconds = Math.max(1, Duration.between(Instant.now(), allowedAt).toSeconds()+1);
        return new DomainException(DomainStatus.RESOURCE_EXHAUSTED, "Please try again later",
                Map.of("retryAfterSeconds", Long.toString(seconds)));
    }
}
