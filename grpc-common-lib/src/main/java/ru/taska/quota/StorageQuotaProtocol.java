package ru.taska.quota;

import java.time.Instant;
import java.util.*;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.QuotaUsage;
import ru.taska.quota.ProjectQuotaProtocol.State;

/** Primary attachment accounting only; global-account avatars never enter this protocol. */
public final class StorageQuotaProtocol {
  private StorageQuotaProtocol() {}

  public record Request(
      UUID operation, UUID resource, UUID project, UUID issue, long bytes, Instant expiresAt) {
    public Request {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(resource);
      Objects.requireNonNull(project);
      Objects.requireNonNull(issue);
      Objects.requireNonNull(expiresAt);
      if (bytes <= 0) throw new IllegalArgumentException();
    }
  }

  public record Reservation(
      UUID tenant,
      Request request,
      UUID id,
      long fence,
      State state,
      long version,
      Long actualBytes,
      boolean cancelRequested,
      long maximumAttachmentBytes,
      long policyVersion,
      UUID actor) {
    public Reservation {
      Objects.requireNonNull(actor);
      Objects.requireNonNull(tenant);
      Objects.requireNonNull(request);
      Objects.requireNonNull(id);
      Objects.requireNonNull(state);
      if (maximumAttachmentBytes <= 0
          || policyVersion <= 0
          || request.bytes() > maximumAttachmentBytes
          || fence <= 0
          || version <= 0
          || (actualBytes != null && (actualBytes <= 0 || actualBytes > request.bytes())))
        throw new IllegalArgumentException();
    }

    public UUID operation() {
      return request.operation();
    }

    public UUID resource() {
      return request.resource();
    }

    public UUID project() {
      return request.project();
    }

    public UUID issue() {
      return request.issue();
    }

    public long bytes() {
      return request.bytes();
    }

    public Instant expiresAt() {
      return request.expiresAt();
    }

    public boolean sameIdentity(Reservation r) {
      return tenant.equals(r.tenant)
          && request.equals(r.request)
          && id.equals(r.id)
          && fence == r.fence
          && maximumAttachmentBytes == r.maximumAttachmentBytes
          && policyVersion == r.policyVersion
          && actor.equals(r.actor);
    }
  }

  public enum OwnerState {
    MATERIALIZED,
    CONFIRMED,
    CANCELLED,
    DELETED
  }

  public record Receipt(Reservation reservation, OwnerState state, Long actualBytes) {
    public Receipt {
      Objects.requireNonNull(reservation);
      Objects.requireNonNull(state);
      if ((state == OwnerState.MATERIALIZED
              || state == OwnerState.CONFIRMED
              || state == OwnerState.DELETED)
          && (actualBytes == null || actualBytes <= 0 || actualBytes > reservation.bytes()))
        throw new IllegalArgumentException();
    }
  }

  public record ManifestPage(
      UUID tenant, long version, List<Receipt> items, UUID next, boolean complete) {
    public ManifestPage {
      Objects.requireNonNull(tenant);
      items = List.copyOf(items);
      if (version <= 0 || items.size() > 100 || (!complete && next == null))
        throw new IllegalArgumentException();
    }
  }

  public interface Authority {
    Mono<Reservation> reserve(Request request);

    Mono<Reservation> get(UUID operation);

    Mono<Reservation> commit(Reservation r);

    Mono<Reservation> release(Reservation r);
  }

  public interface Owner {
    Mono<Receipt> inspect(Reservation r);

    Mono<Receipt> cancel(Reservation r);

    Mono<Receipt> activate(Reservation r);

    Mono<ManifestPage> manifests(UUID tenant, UUID after, long version);
  }

  @FunctionalInterface
  public interface UsageSource {
    Mono<QuotaUsage> current(UUID tenant);
  }
}
