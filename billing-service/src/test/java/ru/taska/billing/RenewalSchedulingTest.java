package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RenewalSchedulingTest {
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class Scheduling {}

  @Test
  @Timeout(15)
  void springSubscribesRepeatedlyToDeferredMonoWithIsoDelayAndFreshClock() throws Exception {
    Instant start = Instant.parse("2026-01-31T12:00:00Z");
    var time = new AtomicReference<>(start);
    var observed = new ConcurrentLinkedQueue<Instant>();
    var executed = new CountDownLatch(2);
    Clock clock =
        new Clock() {
          public Instant instant() {
            return time.get();
          }

          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }
        };
    var scheduler =
        new RenewalScheduler(
            org.mockito.Mockito.mock(InvoiceStore.class),
            now -> {
              observed.add(now);
              time.set(now.plusSeconds(1));
              executed.countDown();
              return Flux.empty();
            },
            (tenant, action, operation) -> Mono.error(new AssertionError("No work was supplied")),
            clock);
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource("fixture", Map.of("billing.renewal.interval", "PT0.05S")));
      context.register(Scheduling.class);
      context.registerBean(RenewalScheduler.class, () -> scheduler);
      context.refresh();
      assertTrue(
          executed.await(10, TimeUnit.SECONDS),
          "Spring must execute two scheduled subscriptions without a manual tick/subscribe");
      var runs = List.copyOf(observed);
      assertTrue(runs.size() >= 2);
      assertEquals(start, runs.get(0));
      assertEquals(start.plusSeconds(1), runs.get(1));
    }
  }
}
