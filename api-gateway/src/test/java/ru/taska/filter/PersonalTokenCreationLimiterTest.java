package ru.taska.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import mapper.GrpcErrorDetails;
import org.junit.jupiter.api.Test;

class PersonalTokenCreationLimiterTest {
  @Test
  void limitsPerUserExpiresAndDoesNotEvictActiveBucketsAtCapacity() {
    var clock = new MutableClock();
    var limiter = new PersonalTokenCreationLimiter(2, Duration.ofHours(1), 1, clock);
    limiter.acquire("a");
    limiter.acquire("a");
    for (String user : java.util.List.of("a", "b", "a")) {
      assertThatThrownBy(() -> limiter.acquire(user))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(GrpcErrorDetails.decode(error.getTrailers()))
                      .containsEntry("retryAfterSeconds", "3600"));
    }
    clock.value = clock.value.plusSeconds(3600);
    limiter.acquire("b");
  }

  @Test
  void parallelRequestsCannotOverrunOneUserLimit() {
    var limiter = new PersonalTokenCreationLimiter(5, Duration.ofHours(1), 10, new MutableClock());
    AtomicInteger passed = new AtomicInteger();
    java.util.stream.IntStream.range(0, 100)
        .parallel()
        .forEach(
            i -> {
              try {
                limiter.acquire("same");
                passed.incrementAndGet();
              } catch (StatusRuntimeException expected) {
              }
            });
    assertThat(passed.get()).isEqualTo(5);
  }

  private static class MutableClock extends Clock {
    Instant value = Instant.parse("2026-10-26T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return value;
    }
  }
}
