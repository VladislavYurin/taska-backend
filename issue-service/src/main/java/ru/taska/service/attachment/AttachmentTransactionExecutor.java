package ru.taska.service.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueEventType;
import ru.taska.event.EventType;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.storage.dto.StoredObjectMetadata;
import ru.taska.util.PayloadSerializer;

import java.time.Instant;
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
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, issueId,
                                    EventType.ATTACHMENT_ADDED, payload))
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
        var deletedAt = Instant.now();
        return issueAttachmentRepository.softDelete(attachment.getId(), deletedAt)
                .flatMap(rowsUpdated -> {
                    if (rowsUpdated == 0) {
                        log.warn("[{}][{}] Attachment already deleted: attachmentId={}",
                                requestId, nodeId, attachment.getId());
                        return Mono.empty();
                    }
                    attachment.setDeletedAt(deletedAt);
                    var payload = payloadSerializer.createAttachmentDeletedPayload(attachment, actorUserId);
                    return issueHistoryService.saveIssueHistory(requestId, nodeId, attachment.getIssueId(),
                                    actorUserId, IssueEventType.ATTACHMENT_DELETED, payload)
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, attachment.getIssueId(),
                                    EventType.ATTACHMENT_DELETED, payload));
                })
                .then();
    }
}
