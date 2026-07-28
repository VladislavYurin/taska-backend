package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.AttachmentDownloadUrlDto;
import ru.taska.domain.AttachmentDto;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.AttachmentService;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import ru.taska.util.PayloadSerializer;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private final IssueProperties issueProperties;
    private final IssueRepository issueRepository;
    private final IssueAttachmentRepository issueAttachmentRepository;
    private final ProjectRoleChecker projectRoleChecker;
    private final StorageClient storageClient;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final PayloadSerializer payloadSerializer;

    @Override
    public Mono<PresignedUploadResult> createUploadUrl(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            String contentType,
            long sizeBytes
    ) {
        return checkUserHasRoleForIssue(requestId, nodeId, issueId, actorUserId,
                issueProperties.allowedRoles().uploadAttachmentRoles())
                .then(storageClient.createPresignedUploadUrl(contentType, sizeBytes));
    }

    @Override
    @Transactional
    public Mono<AttachmentDto> confirmUpload(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            String objectKey,
            String fileName,
            String contentType
    ) {
        return checkUserHasRoleForIssue(requestId, nodeId, issueId, actorUserId,
                issueProperties.allowedRoles().uploadAttachmentRoles())
                .then(checkObjectExistsInStorage(requestId, nodeId, objectKey))
                .then(storageClient.validateAndGetUploadedObjectMetadata(objectKey))
                .flatMap(metadata -> issueAttachmentRepository.save(
                        IssueAttachment.createNewAttachment(issueId, actorUserId, objectKey, fileName,
                                contentType, metadata.sizeBytes(), metadata.checksum())
                ))
                .flatMap(savedAttachment -> {
                    var payload = payloadSerializer.createAttachmentUploadedPayload(savedAttachment);
                    return issueHistoryService.saveIssueHistory(requestId, nodeId, issueId, actorUserId,
                                    IssueEventType.ATTACHMENT_UPLOADED, payload)
                            .then(outboxEventService.saveOutboxEvent(requestId, nodeId, issueId,
                                    EventType.ATTACHMENT_ADDED, payload))
                            .then(storageClient.createPresignedDownloadUrl(savedAttachment.getObjectKey())
                                    .map(url -> new AttachmentDto(savedAttachment, url)));
                });
    }

    @Override
    public Mono<List<AttachmentDto>> listAttachments(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return checkUserHasRoleForIssue(requestId, nodeId, issueId, actorUserId,
                issueProperties.allowedRoles().viewAttachmentRoles())
                .then(issueAttachmentRepository.findByIssueIdAndDeletedAtIsNull(issueId)
                        .flatMap(attachment ->
                                storageClient.createPresignedDownloadUrl(attachment.getObjectKey())
                                        .map(url -> new AttachmentDto(attachment, url)))
                        .collectList());
    }

    @Override
    public Mono<AttachmentDownloadUrlDto> getDownloadUrl(
            String requestId,
            String nodeId,
            UUID attachmentId,
            UUID actorUserId
    ) {
        return findActiveAttachment(requestId, nodeId, attachmentId)
                .flatMap(issueAttachment -> checkUserHasRoleForIssue(requestId, nodeId, issueAttachment.getIssueId(),
                        actorUserId, issueProperties.allowedRoles().viewAttachmentRoles()).thenReturn(issueAttachment))
                .flatMap(issueAttachment -> storageClient.createPresignedDownloadUrl(issueAttachment.getObjectKey())
                        .map(url -> new AttachmentDownloadUrlDto(url, issueAttachment.getChecksum())));
    }

    @Override
    @Transactional
    public Mono<Void> deleteAttachment(
            String requestId,
            String nodeId,
            UUID attachmentId,
            UUID actorUserId
    ) {
        return issueAttachmentRepository.findByIdAndDeletedAtIsNull(attachmentId)
                .flatMap(attachment -> {
                    var roles = attachment.getUploadedBy().equals(actorUserId)
                            ? issueProperties.allowedRoles().deleteOwnAttachmentRoles()
                            : issueProperties.allowedRoles().deleteAttachmentRoles();
                    return findActiveIssue(requestId, nodeId, attachment.getIssueId())
                            .flatMap(issue -> projectRoleChecker.checkProjectRole(
                                    requestId, nodeId, issue.getProjectId(), actorUserId, roles
                            ))
                            .thenReturn(attachment);
                })
                .flatMap(attachment -> softDelete(attachment)
                        .then(Mono.defer(() -> {
                            var payload = payloadSerializer.createAttachmentDeletedPayload(attachment, actorUserId);
                            return issueHistoryService.saveIssueHistory(requestId, nodeId, attachment.getIssueId(),
                                            actorUserId, IssueEventType.ATTACHMENT_DELETED, payload)
                                    .then(outboxEventService.saveOutboxEvent(requestId, nodeId, attachment.getIssueId(),
                                            EventType.ATTACHMENT_DELETED, payload));
                        })).then());
    }

    /**
     * Находит активное вложение по идентификатору.
     *
     * @param requestId    идентификатор запроса.
     * @param nodeId       идентификатор узла.
     * @param attachmentId идентификатор вложения.
     * @return Mono с найденным вложением или ошибка NOT_FOUND.
     */
    private Mono<IssueAttachment> findActiveAttachment(String requestId, String nodeId, UUID attachmentId) {
        return issueAttachmentRepository.findByIdAndDeletedAtIsNull(attachmentId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Attachment not found: attachmentId={}", requestId, nodeId, attachmentId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Attachment not found"));
                }));
    }

    /**
     * Выполняет мягкое удаление вложения.
     *
     * @param attachment вложение для удаления.
     * @return Mono<Void> сигнализирующий о завершении операции.
     */
    private Mono<Void> softDelete(IssueAttachment attachment) {
        return Mono.defer(() -> {
            attachment.setDeletedAt(Instant.now());
            return issueAttachmentRepository.save(attachment).then();
        });
    }

    /**
     * Проверяет существование объекта в хранилище.
     *
     * @param requestId идентификатор запроса.
     * @param nodeId    идентификатор узла.
     * @param objectKey ключ объекта в хранилище.
     * @return Mono, завершающийся успешно если объект найден, иначе ошибка NOT_FOUND.
     */
    private Mono<Void> checkObjectExistsInStorage(String requestId, String nodeId, String objectKey) {
        return storageClient.objectExists(objectKey)
                .flatMap(exists -> {
                    if (!exists) {
                        log.warn("[{}][{}] Object not found in storage: objectKey={}", requestId, nodeId, objectKey);
                        return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Object not found in storage"));
                    }
                    return Mono.empty();
                });
    }

    /**
     * Проверяет что задача существует и пользователь имеет одну из допустимых ролей в проекте.
     *
     * @param requestId    идентификатор запроса.
     * @param nodeId       идентификатор узла.
     * @param issueId      идентификатор задачи.
     * @param actorUserId  идентификатор пользователя.
     * @param allowedRoles набор допустимых ролей для выполнения операции.
     * @return Mono<Void>, завершающийся успешно при наличии прав, или ошибка.
     */
    private Mono<Void> checkUserHasRoleForIssue(
            String requestId, String nodeId, UUID issueId, UUID actorUserId,
            Set<ProjectRole> allowedRoles) {
        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue -> projectRoleChecker.checkProjectRole(
                        requestId, nodeId, issue.getProjectId(), actorUserId,
                        allowedRoles
                ))
                .then();
    }

    /**
     * Находит активную задачу по идентификатору.
     *
     * @param requestId идентификатор запроса.
     * @param nodeId    идентификатор узла.
     * @param issueId   идентификатор задачи.
     * @return Mono с найденной задачей или ошибка NOT_FOUND.
     */
    private Mono<Issue> findActiveIssue(String requestId, String nodeId, UUID issueId) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue not found: issueId={}", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }));
    }
}
