package ru.taska.quota;

import java.time.Instant;
import java.util.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.exception.*;
import ru.taska.quota.ProjectQuotaProtocol.*;

public final class ProjectQuotaWire {
  private ProjectQuotaWire() {}

  public static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.equals(new UUID(0, 0)))
        throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static long number(String value) {
    try {
      if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
      return Long.parseLong(value);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static Instant instant(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static QuotaReservation encode(Reservation r) {
    return QuotaReservation.newBuilder()
        .setId(r.id().toString())
        .setOperationId(r.operation().toString())
        .setTenantId(r.tenant().toString())
        .setKind(QuotaReservation.KindValue.KIND_PROJECT)
        .setQuantity("1")
        .setState(QuotaReservation.StateValue.valueOf("STATE_" + r.state().name()))
        .setExpiresAt(r.expiresAt().toString())
        .setVersion(Long.toString(r.version()))
        .setFence(Long.toString(r.fence()))
        .setCancelRequested(r.cancelRequested())
        .setResourceId(r.resource().toString())
        .build();
  }

  public static Reservation decode(QuotaReservation r) {
    if (r.getKind() != QuotaReservation.KindValue.KIND_PROJECT
        || !"1".equals(r.getQuantity())
        || r.hasProjectId()) throw invalid();
    try {
      return new Reservation(
          uuid(r.getTenantId()),
          uuid(r.getOperationId()),
          uuid(r.getResourceId()),
          uuid(r.getId()),
          number(r.getFence()),
          instant(r.getExpiresAt()),
          State.valueOf(r.getState().name().substring(6)),
          number(r.getVersion()),
          r.getCancelRequested());
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static ProjectQuotaOwnerReceipt encode(Receipt r) {
    return ProjectQuotaOwnerReceipt.newBuilder()
        .setReservation(encode(r.reservation()))
        .setState(ProjectQuotaOwnerReceipt.StateValue.valueOf("STATE_" + r.state().name()))
        .build();
  }

  public static Receipt decode(ProjectQuotaOwnerReceipt r) {
    try {
      return new Receipt(
          decode(r.getReservation()), OwnerState.valueOf(r.getState().name().substring(6)));
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static ProjectQuotaOwnerInventory encode(Inventory i) {
    return ProjectQuotaOwnerInventory.newBuilder()
        .setTenantId(i.tenant().toString())
        .addAllResourceIds(i.resources().stream().map(UUID::toString).sorted().toList())
        .setComplete(true)
        .build();
  }

  public static Inventory decode(ProjectQuotaOwnerInventory i) {
    if (!i.getComplete() || i.getResourceIdsCount() > 10000) throw invalid();
    var ids = i.getResourceIdsList().stream().map(ProjectQuotaWire::uuid).toList();
    if (new HashSet<>(ids).size() != ids.size()) throw invalid();
    return new Inventory(uuid(i.getTenantId()), new HashSet<>(ids));
  }

  public static DomainException invalid() {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_PROJECT_QUOTA");
  }
}
