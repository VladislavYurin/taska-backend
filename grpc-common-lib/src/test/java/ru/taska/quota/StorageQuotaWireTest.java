package ru.taska.quota;

import static org.junit.jupiter.api.Assertions.*;
import static ru.taska.quota.StorageQuotaProtocol.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.DomainException;

class StorageQuotaWireTest {
  private Reservation reservation() {
    return new Reservation(
        UUID.randomUUID(),
        new Request(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            20000000,
            Instant.parse("2026-01-01T00:00:00Z")),
        UUID.randomUUID(),
        4,
        ProjectQuotaProtocol.State.ACTIVE,
        2,
        null,
        false,
        20000000,
        7,
        UUID.randomUUID());
  }

  @Test
  void roundTripPreservesFrozenIdentityAndMeasuredBytes() {
    var r = reservation();
    assertEquals(r, StorageQuotaWire.decode(StorageQuotaWire.encode(r)));
    var receipt = new Receipt(r, OwnerState.MATERIALIZED, 19000000L);
    assertEquals(receipt, StorageQuotaWire.decode(StorageQuotaWire.encode(receipt)));
    var page = new ManifestPage(r.tenant(), 3, List.of(receipt), null, true);
    assertEquals(page, StorageQuotaWire.decode(StorageQuotaWire.encode(page)));
  }

  @Test
  void malformedNumericAndUnboundParentsCannotBecomeAuthority() {
    var b = StorageQuotaWire.encode(reservation()).toBuilder();
    for (String invalid : List.of("0", "-1", "01", "9223372036854775808", "2147483648.0"))
      assertThrows(
          DomainException.class, () -> StorageQuotaWire.decode(b.setQuantity(invalid).build()));
    var other = StorageQuotaWire.encode(reservation()).toBuilder();
    var missing = other.clearIssueId().build();
    assertThrows(DomainException.class, () -> StorageQuotaWire.decode(missing));
    var wrongKind = other.setKind(QuotaReservation.KindValue.KIND_PROJECT).build();
    assertThrows(DomainException.class, () -> StorageQuotaWire.decode(wrongKind));
  }

  @Test
  void impossibleActualAndForeignManifestFailClosed() {
    var r = reservation();
    assertThrows(
        IllegalArgumentException.class, () -> new Receipt(r, OwnerState.CONFIRMED, 20000001L));
    var page =
        StorageQuotaWire.encode(
                new ManifestPage(
                    r.tenant(), 1, List.of(new Receipt(r, OwnerState.CONFIRMED, 1L)), null, true))
            .toBuilder()
            .setTenantId(UUID.randomUUID().toString())
            .build();
    assertThrows(DomainException.class, () -> StorageQuotaWire.decode(page));
  }
}
