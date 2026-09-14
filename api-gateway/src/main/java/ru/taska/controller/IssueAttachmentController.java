package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.AttachmentsApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.ConfirmAttachmentUploadRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlRequestDto;
import ru.taska.domain.dto.CreateAttachmentUploadUrlResponseDto;
import ru.taska.domain.dto.IssueAttachmentsResponseDto;
import ru.taska.domain.dto.GetAttachmentDownloadUrlResponseDto;
import ru.taska.domain.dto.IssueAttachmentDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcIssueAttachmentServiceClient;

import java.util.UUID;


/**
 * REST-контроллер для работы с вложениями у задач.
 * Делегирует обработку запросов {@link GatewayRequestExecutor}
 * и взаимодействие с issue-сервисом через {@link GrpcIssueAttachmentServiceClient}.
 *
 * projectId в пути используется только для REST-иерархии/читаемости URL и не участвует в авторизации.
 */
@RestController
@RequiredArgsConstructor
public class IssueAttachmentController implements AttachmentsApi {
    private final GatewayRequestExecutor executor;
    private final GrpcIssueAttachmentServiceClient attachmentClient;

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<IssueAttachmentsResponseDto>> listAttachments(
            UUID projectId,
            UUID issueId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                attachmentClient.listAttachments(issueId.toString(), context)
                        .map(ResponseEntity::ok));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<CreateAttachmentUploadUrlResponseDto>> createAttachmentUploadUrl(
            UUID projectId,
            UUID issueId,
            Mono<CreateAttachmentUploadUrlRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                attachmentClient.createAttachmentUploadUrl(issueId.toString(), request, context)
                        .map(ResponseEntity::ok));

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<IssueAttachmentDto>> confirmAttachmentUpload(
            UUID projectId,
            UUID issueId,
            Mono<ConfirmAttachmentUploadRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                attachmentClient.confirmAttachmentUpload(issueId.toString(), request, context)
                        .map(responseBody ->
                                ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
                        ));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<GetAttachmentDownloadUrlResponseDto>> getAttachmentDownloadUrl(
            UUID projectId,
            UUID issueId,
            UUID attachmentId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                attachmentClient.getAttachmentDownloadUrl(attachmentId.toString(), context)
                        .map(ResponseEntity::ok));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<Void>> deleteAttachment(
            UUID projectId,
            UUID issueId,
            UUID attachmentId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                attachmentClient.deleteAttachment(attachmentId.toString(), context)
                        .thenReturn(ResponseEntity.noContent().build()));
    }
}
