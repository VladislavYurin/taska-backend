package ru.taska.mcp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class McpQuotaLimiterTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void tokenAndUserBucketsAreSeparateForReadsAndWritesAndRefillContinuously() {
    var limiter = limiter(2, 1, 3, 2, 4, 8, 10);
    limiter.acquire("u", "t", false).close();
    limiter.acquire("u", "t", false).close();
    rejected(() -> limiter.acquire("u", "t", false), "TOKEN_RATE_LIMIT", 30);
    limiter.acquire("u", "t", true).close();
    rejected(() -> limiter.acquire("u", "t", true), "TOKEN_RATE_LIMIT", 60);
    limiter.acquire("u", "t2", false).close();
    rejected(() -> limiter.acquire("u", "t2", false), "USER_RATE_LIMIT", 20);
    limiter.acquire("u", "t2", true).close();
    now.set(Duration.ofSeconds(30).toNanos());
    limiter.acquire("u", "t", false).close();
    rejected(() -> limiter.acquire("u", "t", true), "TOKEN_RATE_LIMIT", 30);
    now.set(Duration.ofSeconds(60).toNanos());
    limiter.acquire("u", "t", true).close();
  }

  @Test
  void failedUserAdmissionDoesNotChargeOrTrackAnotherTokenOrPunishAnotherUser() {
    var limiter = limiter(2, 1, 1, 1, 4, 8, 2);
    limiter.acquire("u", "t", false).close();
    rejected(() -> limiter.acquire("u", "rejected", false), "USER_RATE_LIMIT", 60);
    assertThat(limiter.tokenCount()).isEqualTo(1);
    limiter.acquire("other", "other", false).close();
    assertThat(limiter.tokenCount()).isEqualTo(2);
    assertThat(limiter.userCount()).isEqualTo(2);
  }

  @Test
  void concurrencyIsSharedAcrossReadWriteAndTokensAndCloseIsIdempotent() {
    var limiter = limiter(100, 100, 100, 100, 1, 2, 10);
    var first = limiter.acquire("u", "t", false);
    rejected(() -> limiter.acquire("u", "t", true), "TOKEN_CONCURRENCY_LIMIT", 1);
    var second = limiter.acquire("u", "t2", true);
    rejected(() -> limiter.acquire("u", "t3", false), "USER_CONCURRENCY_LIMIT", 1);
    first.close();
    first.close();
    var third = limiter.acquire("u", "t3", false);
    rejected(() -> limiter.acquire("u", "t4", false), "USER_CONCURRENCY_LIMIT", 1);
    second.close();
    third.close();
    limiter.acquire("u", "t", true).close();
  }

  @Test
  void activeEntriesCannotBeEvictedAndIdleEvictionCannotResetPartiallyRefilledQuota() {
    var limiter = limiter(1, 1, 1, 1, 1, 1, 2);
    var active = limiter.acquire("active", "active", false);
    limiter.acquire("idle", "idle", false).close();
    now.set(Duration.ofSeconds(59).toNanos());
    rejected(() -> limiter.acquire("new", "new", false), "TRACKED_IDENTITY_LIMIT", 1);
    now.set(Duration.ofSeconds(61).toNanos());
    var fresh = limiter.acquire("new", "new", false);
    assertThat(limiter.tokenCount()).isEqualTo(2);
    rejected(() -> limiter.acquire("active", "active", false), "TOKEN_CONCURRENCY_LIMIT", 1);
    fresh.close();
    active.close();
    now.set(Duration.ofMinutes(3).toNanos());
    limiter.acquire("another", "another", false).close();
    assertThat(limiter.tokenCount()).isEqualTo(1);
    assertThat(limiter.userCount()).isEqualTo(1);
  }

  @Test
  void racingAdmissionsNeverExceedFourPerToken() throws Exception {
    var limiter = new McpQuotaLimiter(McpQuotaProperties.defaults(), now::get);
    var leases = new ConcurrentLinkedQueue<McpQuotaLimiter.Lease>();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(24)) {
      var attempts = new ArrayList<CompletableFuture<Void>>();
      for (int i = 0; i < 24; i++)
        attempts.add(
            CompletableFuture.runAsync(
                () -> {
                  try {
                    start.await();
                    leases.add(limiter.acquire("u", "t", false));
                  } catch (GatewayToolException expected) {
                    assertThat(expected.code()).isEqualTo("rate_limited");
                  } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                  }
                },
                pool));
      start.countDown();
      CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get();
    }
    assertThat(leases).hasSize(4);
    leases.forEach(McpQuotaLimiter.Lease::close);
    limiter.acquire("u", "t", false).close();
  }

  private McpQuotaLimiter limiter(int tr, int tw, int ur, int uw, int tc, int uc, int capacity) {
    return new McpQuotaLimiter(
        new McpQuotaProperties(tr, tw, ur, uw, tc, uc, capacity, capacity, Duration.ofMinutes(1)),
        now::get);
  }

  private static void rejected(Runnable action, String reason, long retry) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            GatewayToolException.class,
            error -> {
              assertThat(error.code()).isEqualTo("rate_limited");
              assertThat(error.details())
                  .containsEntry("reason", reason)
                  .containsEntry("retry_after_seconds", retry);
            });
  }
}
