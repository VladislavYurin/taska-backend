package ru.taska.oauth;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Shared OAuth budget, committed before flow validation/provider calls or account transactions. */
@Component
public class OAuthIpLimiter {
  private final DatabaseClient database;
  private final OAuthProperties properties;
  private final TransactionalOperator reservation;

  public OAuthIpLimiter(
      DatabaseClient database,
      OAuthProperties properties,
      @Qualifier("requiresNewTransactionalOperator") TransactionalOperator reservation) {
    this.database = database;
    this.properties = properties;
    this.reservation = reservation;
  }

  public Mono<Void> reserve(String ip) {
    return Mono.defer(
        () -> {
          String bucket = bucket(ip);
          return database
              .sql(
                  """
INSERT INTO taska.signup_rate_limits AS current (bucket, started_at, attempts)
VALUES (:bucket, now(), 1)
ON CONFLICT (bucket) DO UPDATE SET
  started_at=CASE WHEN current.started_at <= now()-interval '1 hour' THEN now() ELSE current.started_at END,
  attempts=CASE WHEN current.started_at <= now()-interval '1 hour' THEN 1 ELSE current.attempts+1 END
WHERE current.started_at <= now()-interval '1 hour' OR current.attempts < :maximum
RETURNING attempts
""")
              .bind("bucket", bucket)
              .bind("maximum", properties.getIpAttemptsPerHour())
              .map((row, metadata) -> row.get("attempts", Integer.class))
              .one()
              .switchIfEmpty(
                  Mono.defer(
                      () ->
                          database
                              .sql(
                                  "SELECT started_at FROM taska.signup_rate_limits WHERE"
                                      + " bucket=:bucket")
                              .bind("bucket", bucket)
                              .map((row, metadata) -> row.get("started_at", Instant.class))
                              .one()
                              .defaultIfEmpty(Instant.now().minusSeconds(3599))
                              .flatMap(start -> Mono.error(limited(start.plusSeconds(3600))))))
              .then()
              .as(reservation::transactional);
        });
  }

  static String bucket(String ip) {
    return "oauth:" + DigestUtils.sha256Hex(canonicalIp(ip));
  }

  private static String canonicalIp(String raw) {
    // Missing/invalid internal addresses share a conservative bucket, including old gateways.
    // Only numeric literals reach InetAddress: no DNS and no caller-chosen arbitrary bucket keys.
    if (raw == null || raw.length() > 128) return "unknown";
    String ip = raw.strip();
    if (!ip.matches("[0-9a-fA-F:.]+")
        || !ip.contains(":") && !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return "unknown";
    try {
      return InetAddress.getByName(ip).getHostAddress();
    } catch (UnknownHostException invalid) {
      return "unknown";
    }
  }

  private static DomainException limited(Instant allowedAt) {
    long retry = Math.max(1, Duration.between(Instant.now(), allowedAt).toSeconds() + 1);
    return new DomainException(
        DomainStatus.RESOURCE_EXHAUSTED,
        "Please try again later",
        Map.of("retryAfterSeconds", Long.toString(retry)));
  }
}
