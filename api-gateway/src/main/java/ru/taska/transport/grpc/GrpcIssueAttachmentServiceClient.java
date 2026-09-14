package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequest;
import ru.taska.api.issue.attachment.v1.ConfirmAttachmentUploadRequestBody;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequest;
import ru.taska.api.issue.attachment.v1.CreateAttachmentUploadUrlRequestBody;
import ru.taska.api.issue.attachment.v1.DeleteAttachmentRequest;
import ru.taska.api.issue.attachment.v1.DeleteAttachmentRequestBody;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlRequest;
import ru.taska.api.issue.attachment.v1.GetAttachmentDownloadUrlRequestBody;
import ru.taska.api.issue.attachment.v1.ListAttachmentsRequest;
import ru.taska.api.issue.attachment.v1.ListAttachmentsRequestBody;
import ru.taska.api.issue.attachment.v1.ReactorIssueAttachmentServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.ConfirmAttachmentUploadRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlResponseDto;
import ru.taska.domain.dto.GetAttachmentDownloadUrlResponseDto;
import ru.taska.domain.dto.IssueAttachmentDto;
import ru.taska.domain.dto.IssueAttachmentsResponseDto;
import ru.taska.mapper.IssueAttachmentMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент для взаимодействия с issue-service в части работы с вложениями.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcIssueAttachmentServiceClient {

    private final ReactorIssueAttachmentServiceGrpc.ReactorIssueAttachmentServiceStub attachmentServiceStub;
    private final IssueAttachmentMapper attachmentMapper;
    private final GrpcClientProperties properties;

    /**
     * Запрос списка вложений, прикрепленных к указанной задаче.
     */
    public Mono<IssueAttachmentsResponseDto> listAttachments(
            String issueId,
            GatewayContext context
    ) {
        log.info("[{}] Calling listAttachments", context.requestId());

        return dynamicStub().listAttachments(
                        ListAttachmentsRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(ListAttachmentsRequestBody.newBuilder()
                                        .setActorUserId(context.userContext().userId())
                                        .setIssueId(issueId)
                                        .build()
                                )
                                .build()
                )
                .map(attachmentMapper::toIssueAttachmentsResponseDto);
    }

    /**
     * Запрос presigned URL для прямой загрузки файла в S3.
     */
    public Mono<CreateAttachmentUploadUrlResponseDto> createAttachmentUploadUrl(
            String issueId,
            Mono<CreateAttachmentUploadUrlRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling createAttachmentUploadUrl", context.requestId());

        return request.flatMap(requestDto ->
                dynamicStub().createAttachmentUploadUrl(
                                CreateAttachmentUploadUrlRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(CreateAttachmentUploadUrlRequestBody.newBuilder()
                                                .setContentType(requestDto.getContentType())
                                                .setSizeBytes(requestDto.getSizeBytes())
                                                .setActorUserId(context.userContext().userId())
                                                .setIssueId(issueId)
                                                .build()
                                        )
                                        .build()
                        )
                        .map(attachmentMapper::toCreateAttachmentUploadUrlResponseDto)
        );
    }

    /**
     * Подтверждение загрузки файла в S3 и создание записи о вложении.
     */
    public Mono<IssueAttachmentDto> confirmAttachmentUpload(
            String issueId,
            Mono<ConfirmAttachmentUploadRequestDto> request,
            GatewayContext context
    ) {
        log.info("[{}] Calling confirmAttachmentUpload", context.requestId());

        return request.flatMap(requestDto ->
                dynamicStub().confirmAttachmentUpload(
                                ConfirmAttachmentUploadRequest.newBuilder()
                                        .setHeader(buildGrpcHeader(context))
                                        .setBody(ConfirmAttachmentUploadRequestBody.newBuilder()
                                                .setIssueId(issueId)
                                                .setActorUserId(context.userContext().userId())
                                                .setContentType(requestDto.getContentType())
                                                .setObjectKey(requestDto.getObjectKey())
                                                .setFileName(requestDto.getFileName())
                                                .build()
                                        )
                                        .build()
                        )
                        .map(attachmentMapper::toIssueAttachmentDto)
        );
    }

    /**
     * Запрос presigned URL для скачивания файла из S3.
     */
    public Mono<GetAttachmentDownloadUrlResponseDto> getAttachmentDownloadUrl(
            String attachmentId,
            GatewayContext context
    ) {
        log.info("[{}] Calling getAttachmentDownloadUrl", context.requestId());

        return dynamicStub().getAttachmentDownloadUrl(
                        GetAttachmentDownloadUrlRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(GetAttachmentDownloadUrlRequestBody.newBuilder()
                                        .setActorUserId(context.userContext().userId())
                                        .setAttachmentId(attachmentId)
                                        .build()
                                )
                                .build()
                )
                .map(attachmentMapper::toGetAttachmentDownloadUrlResponseDto);
    }

    /**
     * Мягкое удаление вложения.
     */
    public Mono<Void> deleteAttachment(
            String attachmentId,
            GatewayContext context
    ) {
        log.info("[{}] Calling deleteAttachment", context.requestId());

        return dynamicStub().deleteAttachment(
                        DeleteAttachmentRequest.newBuilder()
                                .setHeader(buildGrpcHeader(context))
                                .setBody(DeleteAttachmentRequestBody.newBuilder()
                                        .setActorUserId(context.userContext().userId())
                                        .setAttachmentId(attachmentId)
                                        .build()
                                )
                                .build()
                )
                .then();
    }

    /*
     * Возвращает gRPC stub с динамически настроенным временем ожидания (deadline).
     */
    private ReactorIssueAttachmentServiceGrpc.ReactorIssueAttachmentServiceStub dynamicStub() {
        return attachmentServiceStub.withDeadlineAfter(properties.issueService().deadlineDuration().toMillis(), TimeUnit.MILLISECONDS);
    }

    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }
}
