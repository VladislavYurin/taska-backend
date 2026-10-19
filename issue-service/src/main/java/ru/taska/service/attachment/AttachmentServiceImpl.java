package ru.taska.service.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.dto.AttachmentDownloadUrlDto;
import ru.taska.domain.dto.AttachmentDto;
import ru.taska.domain.entity.IssueAttachment;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.dto.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueAttachmentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.storage.client.StorageClient;
import ru.taska.storage.dto.PresignedUploadResult;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

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
    private final AttachmentTransactionExecutor transactionExecutor;

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
                .flatMap(issue -> checkObjectExistsInStorage(requestId, nodeId, objectKey)
                        .then(storageClient.validateAndGetUploadedObjectMetadata(objectKey))
                        .flatMap(metadata -> transactionExecutor.saveAttachment(
                                requestId, nodeId, issue, actorUserId, objectKey, fileName, contentType, metadata)))
                .flatMap(savedAttachment -> storageClient.createPresignedDownloadUrl(savedAttachment.getObjectKey())
                        .map(url -> new AttachmentDto(savedAttachment, url)));
    }

    @Override
    public Flux<AttachmentDto> listAttachments(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    ) {
        return checkUserHasRoleForIssue(requestId, nodeId, issueId, actorUserId,
                issueProperties.allowedRoles().viewAttachmentRoles())
                .thenMany(issueAttachmentRepository.findAllByIssueIdAndDeletedAtIsNull(issueId)
                        .flatMapSequential(attachment ->
                                storageClient.createPresignedDownloadUrl(attachment.getObjectKey())
                                        .map(url -> new AttachmentDto(attachment, url)))
                        );
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
                    return checkUserHasRoleForIssue(requestId, nodeId, attachment.getIssueId(), actorUserId, roles)
                            .flatMap(issue -> transactionExecutor.deleteAttachment(
                                    requestId, nodeId, actorUserId, attachment, issue));
                });
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
     * @return Mono<Issue> с задачей при наличии прав, или ошибка.
     */
    private Mono<Issue> checkUserHasRoleForIssue(
            String requestId, String nodeId, UUID issueId, UUID actorUserId,
            Set<ProjectRole> allowedRoles) {
        return findActiveIssue(requestId, nodeId, issueId)
                .flatMap(issue -> projectRoleChecker.checkProjectRole(
                        requestId, nodeId, issue.getProjectId(), actorUserId, allowedRoles
                ).thenReturn(issue));
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
