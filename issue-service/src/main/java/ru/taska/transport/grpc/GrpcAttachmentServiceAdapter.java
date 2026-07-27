package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.DeleteAttachmentRequest;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlRequest;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse;
import ru.taska.api.issue.attachment.v1.ListAttachmentsRequest;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.api.issue.attachment.v1.ReactorIssueAttachmentServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class GrpcAttachmentServiceAdapter extends ReactorIssueAttachmentServiceGrpc.IssueAttachmentServiceImplBase {

    private final GrpcAttachmentService grpcAttachmentService;

    @Override
    public Mono<CreateAttachmentUploadUrlResponse> createAttachmentUploadUrl(
            Mono<CreateAttachmentUploadUrlRequest> request) {
        return grpcAttachmentService.createAttachmentUploadUrl(request)
                .transform(GrpcExceptionHandler.withErrorHandling("createAttachmentUploadUrl"));
    }

    @Override
    public Mono<AttachmentResponse> confirmAttachmentUpload(
            Mono<ConfirmAttachmentUploadRequest> request) {
        return grpcAttachmentService.confirmAttachmentUpload(request)
                .transform(GrpcExceptionHandler.withErrorHandling("confirmAttachmentUpload"));
    }

    @Override
    public Mono<ListAttachmentsResponse> listAttachments(Mono<ListAttachmentsRequest> request) {
        return grpcAttachmentService.listAttachments(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listAttachments"));
    }

    @Override
    public Mono<GetAttachmentDownloadUrlResponse> getAttachmentDownloadUrl(
            Mono<GetAttachmentDownloadUrlRequest> request) {
        return grpcAttachmentService.getAttachmentDownloadUrl(request)
                .transform(GrpcExceptionHandler.withErrorHandling("getAttachmentDownloadUrl"));
    }

    @Override
    public Mono<Empty> deleteAttachment(Mono<DeleteAttachmentRequest> request) {
        return grpcAttachmentService.deleteAttachment(request)
                .transform(GrpcExceptionHandler.withErrorHandling("deleteAttachment"));
    }
}
