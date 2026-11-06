package ru.taska.billing;

import static ru.taska.billing.CatalogErrors.invalid;

import java.time.*;
import ru.taska.billing.SubscriptionDependencies.PaymentSource;

/**
 * UTC arithmetic only. Settlement authority, never a browser timestamp, supplies financial time.
 */
public final class SubscriptionCalendar {
  private SubscriptionCalendar() {}

  public record Anchor(int day, LocalTime timeUtc) {
    public Anchor {
      if (day < 1 || day > 31 || timeUtc == null || timeUtc.getNano() % 1000 != 0) throw invalid();
    }
  }

  public record Period(Instant startsAt, Instant endsAt, Anchor anchor) {}

  private static void time(Instant at) {
    if (at == null || at.getNano() % 1000 != 0) throw invalid();
  }

  public static Period initial(Instant activationAt) {
    time(activationAt);
    var utc = activationAt.atOffset(ZoneOffset.UTC);
    return next(activationAt, new Anchor(utc.getDayOfMonth(), utc.toLocalTime()));
  }

  public static Period next(Instant startsAt, Anchor original) {
    time(startsAt);
    if (original == null) throw invalid();
    var month = YearMonth.from(startsAt.atOffset(ZoneOffset.UTC)).plusMonths(1);
    Instant end =
        month
            .atDay(Math.min(original.day(), month.lengthOfMonth()))
            .atTime(original.timeUtc())
            .toInstant(ZoneOffset.UTC);
    if (!end.isAfter(startsAt)) throw invalid();
    return new Period(startsAt, end, original);
  }

  /** receivedAt determines WIRE timeliness; only confirmedAt starts a first/resumed WIRE month. */
  public static Period settled(
      PaymentSource source,
      Instant succeededAt,
      Instant receivedAt,
      Instant confirmedAt,
      boolean samePlan,
      Instant previousEnd,
      Anchor original) {
    if (source == null) throw invalid();
    Instant activation = source == PaymentSource.PSP ? succeededAt : confirmedAt;
    Instant timely = source == PaymentSource.PSP ? succeededAt : receivedAt;
    time(activation);
    time(timely);
    if (source == PaymentSource.WIRE && confirmedAt.isBefore(receivedAt)) throw invalid();
    if (previousEnd != null) time(previousEnd);
    if (samePlan && previousEnd != null && !timely.isAfter(previousEnd)) {
      if (original == null) throw invalid();
      return next(previousEnd, original);
    }
    return initial(activation);
  }

  public static boolean active(Period period, Instant now) {
    return !now.isBefore(period.startsAt()) && now.isBefore(period.endsAt());
  }
}
