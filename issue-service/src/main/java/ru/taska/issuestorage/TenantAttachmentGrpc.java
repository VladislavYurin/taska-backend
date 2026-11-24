package ru.taska.issuestorage;

import mapper.GrpcExceptionMapper;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.ReactorIssueAttachmentServiceGrpc;
import ru.taska.exception.DomainException;
import ru.taska.security.tenant.RpcContextBridge;

/** Публичные upload/confirm поверх проверенного tenant transport; остальные методы закрыты. */
@GrpcService
public final class TenantAttachmentGrpc
    extends ReactorIssueAttachmentServiceGrpc.IssueAttachmentServiceImplBase {
  private final AttachmentUploads uploads;
  private final AttachmentLifecycle lifecycle;

  public TenantAttachmentGrpc(AttachmentUploads uploads, AttachmentLifecycle lifecycle) {
    this.uploads = uploads;
    this.lifecycle = lifecycle;
  }

  @Override
  public Mono<ru.taska.api.issue.attachment.v1.ListAttachmentsResponse> listAttachments(
      Mono<ru.taska.api.issue.attachment.v1.ListAttachmentsRequest> q) {
    return errors(q.flatMap(r -> lifecycle.list(r.getBody())));
  }

  @Override
  public Mono<ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse>
      getAttachmentDownloadUrl(
          Mono<ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlRequest> q) {
    return errors(q.flatMap(r -> lifecycle.download(r.getBody())));
  }

  @Override
  public Mono<com.google.protobuf.Empty> deleteAttachment(
      Mono<ru.taska.api.issue.attachment.v1.DeleteAttachmentRequest> q) {
    return errors(
        q.flatMap(
            r ->
                lifecycle
                    .delete(r.getBody())
                    .thenReturn(com.google.protobuf.Empty.getDefaultInstance())));
  }

  @Override
  public Mono<ru.taska.api.issue.attachment.v1.ReadAttachmentContentResponse> readAttachmentContent(
      Mono<ru.taska.api.issue.attachment.v1.ReadAttachmentContentRequest> q) {
    return errors(q.flatMap(lifecycle::content));
  }

  @Override
  public Mono<CreateAttachmentUploadUrlResponse> createAttachmentUploadUrl(
      Mono<CreateAttachmentUploadUrlRequest> q) {
    return errors(q.flatMap(r -> uploads.initiate(r.getBody())));
  }

  @Override
  public Mono<AttachmentResponse> confirmAttachmentUpload(Mono<ConfirmAttachmentUploadRequest> q) {
    return errors(q.flatMap(r -> uploads.confirm(r.getBody())));
  }

  private static <T> Mono<T> errors(Mono<T> m) {
    return RpcContextBridge.inbound(m)
        .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
        .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
  }
}
