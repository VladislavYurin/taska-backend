package ru.taska.service.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueEventType;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.storage.dto.StoredObjectMetadata;
import ru.taska.util.PayloadSerializer;

import java.util.UUID;

/**
 * Выполняет транзакционные операции с вложениями (сохранение/удаление в БД, история, outbox).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentTransactionExecutor {

    private final IssueAttachmentRepository issueAttachmentRepository;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    @Transactional
    public Mono<IssueAttachment> saveAttachment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            String objectKey,
            String fileName,
            String contentType,
            StoredObjectMetadata metadata
    ) {
        return issueAttachmentRepository.save(
                        IssueAttachment.createNewAttachment(issueId, actorUserId, objectKey, fileName,
                                contentType, metadata.sizeBytes(), metadata.checksum())
                )
                .flatMap(savedAttachment -> {
                    var payload = payloadSerializer.createAttachmentUploadedPayload(savedAttachment);
                    return issueHistoryService.saveIssueHistory(requestId, nodeId, issueId, actorUserId,
                                    IssueEventType.ATTACHMENT_UPLOADED, payload)
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE,
                                    issueId, EventType.ATTACHMENT_ADDED, payload))
                            .thenReturn(savedAttachment);
                });
    }

    @Transactional
    public Mono<Void> deleteAttachment(
            String requestId,
            String nodeId,
            UUID actorUserId,
            IssueAttachment attachment
    ) {
        return issueAttachmentRepository.softDelete(attachment.getId())
                .flatMap(deleted -> {
                    var payload = payloadSerializer.createAttachmentDeletedPayload(deleted, actorUserId);
                    return issueHistoryService.saveIssueHistory(requestId, nodeId, deleted.getIssueId(),
                                    actorUserId, IssueEventType.ATTACHMENT_DELETED, payload)
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE,
                                    deleted.getIssueId(), EventType.ATTACHMENT_DELETED, payload));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Attachment already deleted (race condition): attachmentId={}",
                            requestId, nodeId, attachment.getId());
                    return Mono.empty();
                }))
                .then();
    }
}
