package ru.taska.filter;

import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mapper.GrpcErrorDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A bounded per-instance fixed-window limiter; active buckets are never evicted to bypass a limit.
 */
@Component
public class PersonalTokenCreationLimiter {
  private final int attempts;
  private final Duration window;
  private final int maxUsers;
  private final Clock clock;
  private final Map<String, Bucket> buckets = new HashMap<>();

  @Autowired
  public PersonalTokenCreationLimiter(
      @Value("${app.personal-tokens.creation-attempts:5}") int attempts,
      @Value("${app.personal-tokens.creation-window:1h}") Duration window,
      @Value("${app.personal-tokens.max-tracked-users:10000}") int maxUsers) {
    this(attempts, window, maxUsers, Clock.systemUTC());
  }

  PersonalTokenCreationLimiter(int attempts, Duration window, int maxUsers, Clock clock) {
    if (attempts < 1
        || maxUsers < 1
        || window.isNegative()
        || window.isZero()
        || window.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("Invalid personal token issuance limits");
    }
    this.attempts = attempts;
    this.window = window;
    this.maxUsers = maxUsers;
    this.clock = clock;
  }

  public synchronized void acquire(String userId) {
    Instant now = clock.instant();
    buckets.values().removeIf(bucket -> !bucket.expiresAt.isAfter(now));
    Bucket bucket = buckets.get(userId);
    if (bucket == null) {
      if (buckets.size() >= maxUsers) {
        limited(
            now,
            buckets.values().stream()
                .map(value -> value.expiresAt)
                .min(Instant::compareTo)
                .orElse(now.plus(window)));
      }
      bucket = new Bucket(now.plus(window));
      buckets.put(userId, bucket);
    }
    if (bucket.count >= attempts) limited(now, bucket.expiresAt);
    bucket.count++;
  }

  private static void limited(Instant now, Instant next) {
    long retry = Math.max(1, (Duration.between(now, next).toMillis() + 999) / 1000);
    throw Status.RESOURCE_EXHAUSTED
        .withDescription("Token issuance rate limit reached; try later")
        .asRuntimeException(
            GrpcErrorDetails.encode(Map.of("retryAfterSeconds", Long.toString(retry))));
  }

  private static final class Bucket {
    private final Instant expiresAt;
    private int count;

    private Bucket(Instant expiresAt) {
      this.expiresAt = expiresAt;
    }
  }
}
