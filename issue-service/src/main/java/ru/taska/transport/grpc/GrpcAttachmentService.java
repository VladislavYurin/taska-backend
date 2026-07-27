package ru.taska.transport.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.attachment.v1.AttachmentResponse;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlResponse;
import ru.taska.api.issue.attachment.v1.DeleteAttachmentRequest;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlRequest;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlResponse;
import ru.taska.api.issue.attachment.v1.ListAttachmentsRequest;
import ru.taska.api.issue.attachment.v1.ListAttachmentsResponse;
import ru.taska.exception.DomainException;
import ru.taska.mapper.AttachmentMapper;
import ru.taska.service.AttachmentService;
import validator.GrpcRequestValidators;

import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAttachmentService {

    private final AttachmentService attachmentService;
    private final AttachmentMapper attachmentMapper;

    @TrackMetrics(counter = "issue-service_create-attachment-upload-url_grpc_counter",
            timer = "issue-service_create-attachment-upload-url_grpc_timer")
    public Mono<CreateAttachmentUploadUrlResponse> createAttachmentUploadUrl(
            Mono<CreateAttachmentUploadUrlRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getContentType(), "body.contentType"
                                ),
                                GrpcRequestValidators.requirePositiveOrInvalidArgument(
                                        req.getBody().getSizeBytes(), "body.sizeBytes"
                                )
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "createAttachmentUploadUrl"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();
                            String contentType = t.getT5();
                            long sizeBytes = t.getT6();

                            log.info("[{}][{}] createAttachmentUploadUrl: issueId={}, actorUserId={}, contentType={}, sizeBytes={}",
                                    requestId, nodeId, issueId, actorUserId, contentType, sizeBytes);

                            return attachmentService.createUploadUrl(requestId, nodeId, issueId, actorUserId, contentType, sizeBytes)
                                    .doOnNext(result ->
                                            log.info("[{}][{}] createAttachmentUploadUrl: successfully generated, objectKey={}",
                                                    requestId, nodeId, result.objectKey())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "createAttachmentUploadUrl")
                                    );
                        }))
                .map(attachmentMapper::toUploadUrlResponse);
    }

    @TrackMetrics(counter = "issue-service_confirm-attachment-upload_grpc_counter",
            timer = "issue-service_confirm-attachment-upload_grpc_timer")
    public Mono<AttachmentResponse> confirmAttachmentUpload(
            Mono<ConfirmAttachmentUploadRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getObjectKey(), "body.objectKey"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getFileName(), "body.fileName"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getBody().getContentType(), "body.contentType"
                                ),
                                GrpcRequestValidators.requirePositiveOrInvalidArgument(
                                        req.getBody().getSizeBytes(), "body.sizeBytes"
                                )
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "confirmAttachmentUpload"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();
                            String objectKey = t.getT5();
                            String fileName = t.getT6();
                            String contentType = t.getT7();
                            long sizeBytes = t.getT8();

                            log.info("[{}][{}] confirmAttachmentUpload: issueId={}, actorUserId={}, objectKey={}, fileName={}",
                                    requestId, nodeId, issueId, actorUserId, objectKey, fileName);

                            return attachmentService.confirmUpload(requestId, nodeId, issueId, actorUserId,
                                            objectKey, fileName, contentType, sizeBytes)
                                    .doOnNext(attachment ->
                                            log.info("[{}][{}] confirmAttachmentUpload: successfully confirmed, attachmentId={}",
                                                    requestId, nodeId, attachment.issueAttachment().getId())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "confirmAttachmentUpload")
                                    );
                        }))
                .map(attachmentMapper::toAttachmentResponse);
    }

    @TrackMetrics(counter = "issue-service_list-attachments_grpc_counter",
            timer = "issue-service_list-attachments_grpc_timer")
    public Mono<ListAttachmentsResponse> listAttachments(Mono<ListAttachmentsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getIssueId(), "body.issueId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                )
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "listAttachments"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID issueId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] listAttachments: issueId={}, actorUserId={}",
                                    requestId, nodeId, issueId, actorUserId);

                            return attachmentService.listAttachments(requestId, nodeId, issueId, actorUserId)
                                    .doOnNext(attachments ->
                                            log.info("[{}][{}] listAttachments: found {} attachments",
                                                    requestId, nodeId, attachments.size())
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "listAttachments")
                                    );
                        }))
                .map(attachmentMapper::toListAttachmentsResponse);
    }

    @TrackMetrics(counter = "issue-service_get-attachment-download-url_grpc_counter",
            timer = "issue-service_get-attachment-download-url_grpc_timer")
    public Mono<GetAttachmentDownloadUrlResponse> getAttachmentDownloadUrl(Mono<GetAttachmentDownloadUrlRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getAttachmentId(), "body.attachmentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                )
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "getAttachmentDownloadUrl"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID attachmentId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] getAttachmentDownloadUrl: attachmentId={}, actorUserId={}",
                                    requestId, nodeId, attachmentId, actorUserId);

                            return attachmentService.getDownloadUrl(requestId, nodeId, attachmentId, actorUserId)
                                    .doOnNext(url ->
                                            log.info("[{}][{}] getAttachmentDownloadUrl: successfully generated",
                                                    requestId, nodeId)
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "getAttachmentDownloadUrl")
                                    );
                        }))
                .map(attachmentMapper::toDownloadUrlResponse);
    }

    @TrackMetrics(counter = "issue-service_delete-attachment_grpc_counter",
            timer = "issue-service_delete-attachment_grpc_timer")
    public Mono<Empty> deleteAttachment(Mono<DeleteAttachmentRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getRequestId(), "header.requestId"
                                ),
                                GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                        req.getHeader().getNodeId(), "header.nodeId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getAttachmentId(), "body.attachmentId"
                                ),
                                GrpcRequestValidators.parseUuidOrInvalidArgument(
                                        req.getBody().getActorUserId(), "body.actorUserId"
                                )
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(req.getHeader(), "deleteAttachment"))
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID attachmentId = t.getT3();
                            UUID actorUserId = t.getT4();

                            log.info("[{}][{}] deleteAttachment: attachmentId={}, actorUserId={}",
                                    requestId, nodeId, attachmentId, actorUserId);

                            return attachmentService.deleteAttachment(requestId, nodeId, attachmentId, actorUserId)
                                    .doOnSuccess(v ->
                                            log.info("[{}][{}] deleteAttachment: successfully deleted, attachmentId={}",
                                                    requestId, nodeId, attachmentId)
                                    )
                                    .doOnError(DomainException.class,
                                            logOnError(requestId, nodeId, "deleteAttachment")
                                    );
                        }))
                .thenReturn(Empty.getDefaultInstance());
    }

    private Consumer<Throwable> logValidationError(Header header, String operation) {
        return throwable -> {
            if (throwable instanceof StatusRuntimeException e
                    && e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
                log.error("[{}][{}] {} validation error: {}",
                        header.getRequestId(), header.getNodeId(), operation, e.getStatus().getDescription());
            }
        };
    }

    private Consumer<Throwable> logOnError(String requestId, String nodeId, String operation) {
        return throwable -> {
            if (throwable instanceof DomainException e) {
                log.error("[{}][{}] {} failed: status={}, message={}",
                        requestId, nodeId, operation, e.getStatus(), e.getMessage());
            }
        };
    }
}
