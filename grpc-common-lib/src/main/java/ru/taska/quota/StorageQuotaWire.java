package ru.taska.quota;

import static ru.taska.quota.ProjectQuotaWire.*;

import java.util.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.quota.ProjectQuotaProtocol.State;
import ru.taska.quota.StorageQuotaProtocol.*;

public final class StorageQuotaWire {
  private StorageQuotaWire() {}

  public static QuotaReservation encode(Reservation r) {
    var b =
        QuotaReservation.newBuilder()
            .setId(r.id().toString())
            .setOperationId(r.operation().toString())
            .setTenantId(r.tenant().toString())
            .setKind(QuotaReservation.KindValue.KIND_STORAGE_BYTES)
            .setProjectId(r.project().toString())
            .setIssueId(r.issue().toString())
            .setQuantity(Long.toString(r.bytes()))
            .setState(QuotaReservation.StateValue.valueOf("STATE_" + r.state()))
            .setExpiresAt(r.expiresAt().toString())
            .setVersion(Long.toString(r.version()))
            .setFence(Long.toString(r.fence()))
            .setResourceId(r.resource().toString())
            .setCancelRequested(r.cancelRequested())
            .setMaximumAttachmentBytes(Long.toString(r.maximumAttachmentBytes()))
            .setPolicyVersion(Long.toString(r.policyVersion()))
            .setOriginalActorUserId(r.actor().toString());
    if (r.actualBytes() != null) b.setActualQuantity(Long.toString(r.actualBytes()));
    return b.build();
  }

  public static Reservation decode(QuotaReservation r) {
    try {
      if (r.getKind() != QuotaReservation.KindValue.KIND_STORAGE_BYTES
          || !r.hasProjectId()
          || !r.hasIssueId()
          || !r.hasMaximumAttachmentBytes()
          || !r.hasPolicyVersion()
          || !r.hasOriginalActorUserId()) throw invalid();
      return new Reservation(
          uuid(r.getTenantId()),
          new Request(
              uuid(r.getOperationId()),
              uuid(r.getResourceId()),
              uuid(r.getProjectId()),
              uuid(r.getIssueId()),
              number(r.getQuantity()),
              instant(r.getExpiresAt())),
          uuid(r.getId()),
          number(r.getFence()),
          State.valueOf(r.getState().name().substring(6)),
          number(r.getVersion()),
          r.hasActualQuantity() ? number(r.getActualQuantity()) : null,
          r.getCancelRequested(),
          number(r.getMaximumAttachmentBytes()),
          number(r.getPolicyVersion()),
          uuid(r.getOriginalActorUserId()));
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static StorageQuotaOwnerReceipt encode(Receipt r) {
    var b =
        StorageQuotaOwnerReceipt.newBuilder()
            .setReservation(encode(r.reservation()))
            .setState(StorageQuotaOwnerReceipt.StateValue.valueOf("STATE_" + r.state()));
    if (r.actualBytes() != null) b.setActualBytes(Long.toString(r.actualBytes()));
    return b.build();
  }

  public static Receipt decode(StorageQuotaOwnerReceipt r) {
    try {
      return new Receipt(
          decode(r.getReservation()),
          OwnerState.valueOf(r.getState().name().substring(6)),
          r.hasActualBytes() ? number(r.getActualBytes()) : null);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public static StorageQuotaManifestPage encode(ManifestPage p) {
    var b =
        StorageQuotaManifestPage.newBuilder()
            .setTenantId(p.tenant().toString())
            .setVersion(Long.toString(p.version()))
            .setComplete(p.complete())
            .addAllItems(p.items().stream().map(StorageQuotaWire::encode).toList());
    if (p.next() != null) b.setNext(p.next().toString());
    return b.build();
  }

  public static ManifestPage decode(StorageQuotaManifestPage p) {
    try {
      var tenant = uuid(p.getTenantId());
      var items = p.getItemsList().stream().map(StorageQuotaWire::decode).toList();
      if (items.stream().anyMatch(r -> !tenant.equals(r.reservation().tenant()))
          || items.stream().map(r -> r.reservation().resource()).distinct().count() != items.size())
        throw invalid();
      return new ManifestPage(
          tenant,
          number(p.getVersion()),
          items,
          p.hasNext() ? uuid(p.getNext()) : null,
          p.getComplete());
    } catch (RuntimeException e) {
      throw invalid();
    }
  }
}
