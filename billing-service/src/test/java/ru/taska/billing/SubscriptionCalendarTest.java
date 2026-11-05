package ru.taska.billing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import ru.taska.billing.SubscriptionDependencies.PaymentSource;
import ru.taska.exception.DomainException;

class SubscriptionCalendarTest {
  @Test
  void original31AnchorSurvivesShortMonthsAndLeapYears() {
    for (int year : new int[] {2024, 2025}) {
      var first = SubscriptionCalendar.initial(Instant.parse(year + "-01-31T12:34:56Z"));
      assertEquals(
          year + "-02-" + (year == 2024 ? "29" : "28") + "T12:34:56Z", first.endsAt().toString());
      var next = SubscriptionCalendar.next(first.endsAt(), first.anchor());
      assertEquals(year + "-03-31T12:34:56Z", next.endsAt().toString());
      assertEquals(31, next.anchor().day());
    }
  }

  @Test
  void yearAndDstBoundariesAlwaysUseUtcAndAnExclusiveEnd() {
    var december = SubscriptionCalendar.initial(Instant.parse("2025-12-31T23:59:59.123456Z"));
    assertEquals("2026-01-31T23:59:59.123456Z", december.endsAt().toString());
    var march = SubscriptionCalendar.initial(Instant.parse("2026-03-29T01:30:00Z"));
    assertEquals("2026-04-29T01:30:00Z", march.endsAt().toString());
    assertTrue(SubscriptionCalendar.active(march, march.startsAt()));
    assertFalse(SubscriptionCalendar.active(march, march.endsAt()));
  }

  @Test
  void timelyRenewalPreservesOldEndAndAnchorButGapCreatesANewMonth() {
    var original = SubscriptionCalendar.initial(Instant.parse("2026-01-31T12:00:00Z"));
    var early =
        SubscriptionCalendar.settled(
            PaymentSource.PSP,
            Instant.parse("2026-02-20T13:00:00Z"),
            null,
            null,
            true,
            original.endsAt(),
            original.anchor());
    assertEquals(original.endsAt(), early.startsAt());
    assertEquals("2026-03-31T12:00:00Z", early.endsAt().toString());
    var gap =
        SubscriptionCalendar.settled(
            PaymentSource.PSP,
            Instant.parse("2026-03-03T15:00:00Z"),
            null,
            null,
            true,
            original.endsAt(),
            original.anchor());
    assertEquals("2026-03-03T15:00:00Z", gap.startsAt().toString());
    assertEquals("2026-04-03T15:00:00Z", gap.endsAt().toString());
    assertEquals(3, gap.anchor().day());
  }

  @Test
  void wireUsesReceiptForTimelinessAndConfirmationForFirstOrResumedActivation() {
    Instant received = Instant.parse("2026-02-20T09:00:00Z"),
        confirmed = Instant.parse("2026-03-03T14:00:00Z");
    var original = SubscriptionCalendar.initial(Instant.parse("2026-01-31T12:00:00Z"));
    var timely =
        SubscriptionCalendar.settled(
            PaymentSource.WIRE,
            null,
            received,
            confirmed,
            true,
            original.endsAt(),
            original.anchor());
    assertEquals(original.endsAt(), timely.startsAt());
    var first =
        SubscriptionCalendar.settled(
            PaymentSource.WIRE, null, received, confirmed, false, null, null);
    assertEquals(confirmed, first.startsAt());
    assertEquals("2026-04-03T14:00:00Z", first.endsAt().toString());
    var late =
        SubscriptionCalendar.settled(
            PaymentSource.WIRE,
            null,
            Instant.parse("2026-03-01T09:00:00Z"),
            confirmed,
            true,
            original.endsAt(),
            original.anchor());
    assertEquals(first, late);
  }

  @Test
  void planChangeDoesNotAppendOldRemainingDaysAndInvalidPrecisionIsRejected() {
    var original = SubscriptionCalendar.initial(Instant.parse("2026-01-31T12:00:00Z"));
    Instant payment = Instant.parse("2026-02-02T10:00:00Z");
    var changed =
        SubscriptionCalendar.settled(
            PaymentSource.PSP, payment, null, null, false, original.endsAt(), original.anchor());
    assertEquals(payment, changed.startsAt());
    assertEquals("2026-03-02T10:00:00Z", changed.endsAt().toString());
    assertThrows(DomainException.class, () -> SubscriptionCalendar.initial(payment.plusNanos(1)));
    assertThrows(DomainException.class, () -> new SubscriptionCalendar.Anchor(0, LocalTime.NOON));
    assertThrows(
        DomainException.class,
        () ->
            SubscriptionCalendar.settled(
                PaymentSource.WIRE, null, payment, payment.minusSeconds(1), false, null, null));
  }
}
