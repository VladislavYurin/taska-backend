package ru.taska.issuestorage;

import com.google.protobuf.ByteString;
import java.time.*;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.*;
import ru.taska.capability.Action;
import ru.taska.exception.*;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.storage.versioned.AttachmentContentObjects;

public final class AttachmentLifecycle {
  private final AttachmentLifecycleStore store;
  private final AttachmentLifecyclePolicy policy;
  private final AttachmentContentObjects objects;

  public AttachmentLifecycle(
      AttachmentLifecycleStore store,
      AttachmentLifecyclePolicy policy,
      AttachmentContentObjects objects) {
    this.store = store;
    this.policy = policy;
    this.objects = objects;
  }

  public Mono<ListAttachmentsResponse> list(ListAttachmentsRequestBody q) {
    UUID p = id(q.getProjectId()), i = id(q.getIssueId());
    return actor(q.getActorUserId())
        .then(policy.authorize(Action.ATTACHMENT_READ, p, i))
        .flatMap(
            permit ->
                store
                    .list(p, i)
                    .map(
                        rows -> {
                          check(permit);
                          return ListAttachmentsResponse.newBuilder()
                              .addAllAttachments(
                                  rows.stream()
                                      .map(AttachmentLifecycleStore.Item::response)
                                      .toList())
                              .build();
                        }));
  }

  public Mono<GetAttachmentDownloadUrlResponse> download(GetAttachmentDownloadUrlRequestBody q) {
    UUID p = id(q.getProjectId()), i = id(q.getIssueId()), a = id(q.getAttachmentId());
    return actor(q.getActorUserId())
        .then(policy.authorize(Action.ATTACHMENT_READ, p, i))
        .flatMap(
            permit ->
                store
                    .item(p, i, a, true)
                    .map(
                        row -> {
                          check(permit);
                          return GetAttachmentDownloadUrlResponse.newBuilder()
                              .setUrl(row.downloadPath())
                              .setChecksum(row.checksum())
                              .build();
                        }));
  }

  public Mono<Void> delete(DeleteAttachmentRequestBody q) {
    UUID p = id(q.getProjectId()), i = id(q.getIssueId()), a = id(q.getAttachmentId());
    return actor(q.getActorUserId())
        .then(policy.authorize(Action.ATTACHMENT_DELETE, p, i))
        .flatMap(permit -> store.item(p, i, a, false).flatMap(row -> store.accept(row, permit)));
  }

  public Mono<ReadAttachmentContentResponse> content(ReadAttachmentContentRequest q) {
    UUID p = id(q.getProjectId()), i = id(q.getIssueId()), a = id(q.getAttachmentId());
    if (q.getMetadataOnly()
        ? (q.getOffset() != 0 || q.getLength() != 0)
        : (q.getOffset() < 0
            || q.getLength() < 1
            || q.getLength() > AttachmentContentObjects.MAX_CHUNK
            || !q.getExpectedChecksum().matches("[0-9a-f]{64}"))) return Mono.error(invalid());
    return actor(q.getActorUserId())
        .then(policy.authorize(Action.ATTACHMENT_READ, p, i))
        .flatMap(
            permit ->
                store
                    .item(p, i, a, true)
                    .flatMap(
                        row -> {
                          check(permit);
                          var response =
                              ReadAttachmentContentResponse.newBuilder()
                                  .setTotalSize(row.size())
                                  .setFileName(row.filename())
                                  .setContentType(row.mime())
                                  .setChecksum(row.checksum())
                                  .setOffset(q.getOffset());
                          Mono<byte[]> data;
                          if (q.getMetadataOnly())
                            data =
                                objects
                                    .head(row.key(), row.version(), row.size())
                                    .thenReturn(new byte[0]);
                          else {
                            if (!row.checksum().equals(q.getExpectedChecksum())
                                || q.getOffset() >= row.size()
                                || q.getLength() > row.size() - q.getOffset())
                              return Mono.error(invalid());
                            data =
                                objects.read(
                                    row.key(),
                                    row.version(),
                                    row.size(),
                                    q.getOffset(),
                                    q.getLength());
                          }
                          Duration until = Duration.between(Instant.now(), permit.until());
                          if (until.isNegative() || until.isZero())
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.UNAVAILABLE, "ISSUE_AUTHORITY_EXPIRED"));
                          return data.timeout(
                                  until.compareTo(Duration.ofSeconds(5)) < 0
                                      ? until
                                      : Duration.ofSeconds(5))
                              .map(
                                  bytes -> {
                                    check(permit);
                                    return response.setData(ByteString.copyFrom(bytes)).build();
                                  });
                        }));
  }

  private static void check(TenantIssueAccess.Permit p) {
    if (!Instant.now().isBefore(p.until()))
      throw new DomainException(DomainStatus.UNAVAILABLE, "ISSUE_AUTHORITY_EXPIRED");
  }

  private static Mono<Void> actor(String actor) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                actor.equals(c.actorId())
                    ? Mono.empty()
                    : Mono.error(
                        new DomainException(DomainStatus.PERMISSION_DENIED, "ACTOR_MISMATCH")));
  }

  static UUID id(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static DomainException invalid() {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_ATTACHMENT_REQUEST");
  }
}
