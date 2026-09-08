package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueComment;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.PageResult;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueCommentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.CommentService;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final int INIT_VERSION = 1;

    private final IssueProperties issueProperties;
    private final IssueRepository issueRepository;
    private final IssueCommentRepository commentRepository;
    private final IssueHistoryService issueHistoryService;
    private final OutboxEventService outboxEventService;
    private final ProjectRoleChecker projectRoleChecker;
    private final ObjectMapper objectMapper;

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    @Override
    @Transactional
    public Mono<IssueComment> addComment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID authorUserId,
            String body
    ) {
        return findIssueWithLock(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    UUID projectId = issue.getProjectId();

                    return checkPermissions(requestId, nodeId, projectId, authorUserId)
                            .thenReturn(issue);
                })
                .flatMap(issue ->
                        buildAndSaveComment(
                                requestId,
                                nodeId,
                                issueId,
                                issue.getProjectId(),
                                authorUserId,
                                body
                        )
                                .flatMap(savedComment ->
                                        saveHistoryAndOutbox(
                                                requestId,
                                                nodeId,
                                                issue,
                                                authorUserId,
                                                IssueEventType.COMMENT_CREATED,
                                                EventType.COMMENT_CREATED,
                                                createCommentPayload(savedComment.getId(), authorUserId, body)
                                        ).thenReturn(savedComment)
                                )
                )
                .doOnSuccess(comment -> {
                    assert comment != null;
                    log.info("[{}][{}] Comment added: {}", requestId, nodeId, comment.getId());
                })
                .doOnError(e ->
                        log.error("[{}][{}] Failed to add comment: {}", requestId, nodeId, e.getMessage())
                );
    }

    @Override
    @Transactional
    public Mono<IssueComment> updateComment(
            String requestId,
            String nodeId,
//            UUID projectId,
            UUID issueId,
            UUID commentId,
            UUID actorUserId,
            String body
    ) {
        log.debug("[{}][{}] Updating comment: {}", requestId, nodeId, commentId);

        return findIssueWithLock(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    UUID projectId = issue.getProjectId();

                    return checkPermissions(requestId, nodeId, projectId, actorUserId)
                            .thenReturn(issue);
                })
                .flatMap(issue ->
                        findActiveComment(requestId, nodeId, commentId, issueId, actorUserId)
                                .flatMap(comment -> {
                                    int currentVersion = comment.getVersion();
                                    String oldBody = comment.getBody();

                                    return commentRepository.updateWithVersionCheckAndAuthor(
                                                    commentId, body, currentVersion, actorUserId
                                            )
                                            .switchIfEmpty(Mono.error(new DomainException(
                                                    DomainStatus.FAILED_PRECONDITION,
                                                    "Comment was modified by another user. Please refresh and try again."
                                            )))
                                            .flatMap(savedComment -> {
                                                ObjectNode payload = createUpdatePayload(
                                                        commentId, oldBody, body, actorUserId
                                                );
                                                return saveHistoryAndOutbox(
                                                        requestId,
                                                        nodeId,
                                                        issue,
                                                        actorUserId,
                                                        IssueEventType.COMMENT_UPDATED,
                                                        EventType.COMMENT_UPDATED,
                                                        payload
                                                ).thenReturn(savedComment);
                                            });
                                })
                )
                .doOnSuccess(comment ->
                        log.info("[{}][{}] Comment updated: {}", requestId, nodeId, commentId)
                )
                .doOnError(e ->
                        log.error("[{}][{}] Failed to update comment: {}", requestId, nodeId, e.getMessage())
                );
    }

    @Override
    @Transactional
    public Mono<IssueComment> deleteComment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID commentId,
            UUID actorUserId
    ) {
        log.debug("[{}][{}] Deleting comment: {}", requestId, nodeId, commentId);

        return findIssueWithLock(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    UUID projectId = issue.getProjectId();  // ✅ Из базы данных!
                    return checkPermissions(requestId, nodeId, projectId, actorUserId)
                            .thenReturn(issue);
                })
                .flatMap(issue ->
                        findActiveComment(requestId, nodeId, commentId, issueId, actorUserId)
                                .flatMap(comment -> {
                                    int currentVersion = comment.getVersion();

                                    return commentRepository.softDeleteWithVersionCheck(
                                                    commentId, currentVersion
                                            )
                                            .switchIfEmpty(Mono.error(new DomainException(
                                                    DomainStatus.NOT_FOUND,
                                                    "Comment not found or already deleted"
                                            )))
                                            .flatMap(deletedComment -> {
                                                ObjectNode payload = createDeletePayload(
                                                        commentId, actorUserId, comment.getBody()
                                                );
                                                return saveHistoryAndOutbox(
                                                        requestId,
                                                        nodeId,
                                                        issue,
                                                        actorUserId,
                                                        IssueEventType.COMMENT_DELETED,
                                                        EventType.COMMENT_DELETED,
                                                        payload
                                                ).thenReturn(deletedComment);
                                            });
                                })
                )
                .doOnSuccess(comment ->
                        log.info("[{}][{}] Comment deleted: {}", requestId, nodeId, commentId)
                )
                .doOnError(e ->
                        log.error("[{}][{}] Failed to delete comment v2: {}", requestId, nodeId, e.getMessage())
                );
    }

    @Override
    public Mono<PageResult<IssueComment>> listComments(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            Integer page,
            Integer pageSize
    ) {
        int resolvedPage = validatePage(page);
        int resolvedPageSize = validatePageSize(pageSize);
        long offset = (long) resolvedPage * resolvedPageSize;

        log.debug("[{}][{}] Listing comments for issue: {}, page={}, size={}",
                requestId, nodeId, issueId, resolvedPage, resolvedPageSize);

        return findIssue(requestId, nodeId, issueId)
                .flatMap(issue -> {
                    UUID projectId = issue.getProjectId();  // ✅ Из базы данных!
                    return checkPermissions(requestId, nodeId, projectId, actorUserId)
                            .thenReturn(issue);
                })
                .then(Mono.zip(
                        commentRepository.countActiveByIssueId(issueId),
                        commentRepository.findActiveByIssueIdOrderByCreatedAtDesc(issueId)
                                .skip(offset)
                                .take(resolvedPageSize)
                                .collectList()
                ))
                .map(t -> new PageResult<>(t.getT2(), t.getT1()))
                .doOnSuccess(result -> {
                    assert result != null;
                    log.info("[{}][{}] Found {} comments for issue {}",
                            requestId, nodeId, result.totalCount(), issueId);
                })
                .doOnError(e ->
                        log.error("[{}][{}] Failed to pagination comments: {}", requestId, nodeId, e.getMessage())
                );
    }

    // ==================== ПРИВАТНЫЕ МЕТОДЫ ====================

    /**
     * Проверяет права пользователя в проекте.
     */
    private Mono<Void> checkPermissions(String requestId, String nodeId, UUID projectId, UUID userId) {
        Set<ProjectRole> allowedRoles = issueProperties.allowedRoles().commentRoles();
        return projectRoleChecker.checkProjectRole(requestId, nodeId, projectId, userId, allowedRoles);
    }

    /**
     * Находит задачу по ID. Если не найдена — выбрасывает исключение.
     */
    private Mono<Issue> findIssue(String requestId, String nodeId, UUID issueId) {
        return issueRepository.findActiveById(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue not found: {}", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }));
    }

    /**
     * Находит задачу по ID с блокировкой. Если не найдена — выбрасывает исключение.
     */
    private Mono<Issue> findIssueWithLock(String requestId, String nodeId, UUID issueId) {
        return issueRepository.findActiveByIdForUpdate(issueId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Issue not found: {}", requestId, nodeId, issueId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Issue not found"));
                }));
    }

    /**
     * Находит активный комментарий по ID с проверкой принадлежности к задаче и авторства.
     */
    private Mono<IssueComment> findActiveComment(String requestId, String nodeId, UUID commentId,
                                                 UUID issueId, UUID actorUserId) {
        return commentRepository.findActiveById(commentId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[{}][{}] Comment not found: {}", requestId, nodeId, commentId);
                    return Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Comment not found"));
                }))
                .filter(comment -> comment.getIssueId().equals(issueId))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] Comment {} does not belong to issue {}", requestId, nodeId, commentId, issueId);
                    return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION,
                            "Comment does not belong to this issue"));
                }))
                .filter(comment -> actorUserId == null || comment.getAuthorUserId().equals(actorUserId))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[{}][{}] User {} is not the author of comment {}", requestId, nodeId, actorUserId, commentId);
                    return Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,
                            "Only the author can modify this comment"));
                }));
    }

    /**
     * Создает и сохраняет комментарий.
     */
    private Mono<IssueComment> buildAndSaveComment(String requestId, String nodeId, UUID issueId,
                                                   UUID projectId, UUID authorUserId, String body) {
        IssueComment comment = IssueComment.builder()
                .issueId(issueId)
                .projectId(projectId)
                .authorUserId(authorUserId)
                .body(body)
                .version(INIT_VERSION)
                .build();

        log.debug("[{}][{}] Saving comment...", requestId, nodeId);
        return commentRepository.save(comment)
                .doOnSuccess(c -> {
                    assert c != null;
                    log.debug("[{}][{}] Comment saved: {}", requestId, nodeId, c.getId());
                });
    }

    /**
     * Сохраняет историю и outbox событие.
     */
    private Mono<Void> saveHistoryAndOutbox(String requestId, String nodeId, Issue issue,
                                            UUID actorUserId, IssueEventType issueEventType,
                                            EventType outboxEventType, ObjectNode payload) {
        return issueHistoryService.saveIssueHistory(requestId, nodeId, issue.getId(), actorUserId, issueEventType, payload)
                .doOnSuccess(h -> {
                    assert h != null;
                    log.debug("[{}][{}] History saved: {}", requestId, nodeId, h.getId());
                })
                .then(outboxEventService.saveOutboxEvent(requestId, nodeId, AggregateType.ISSUE, issue.getId(), outboxEventType, payload))
                .doOnSuccess(e -> {
                    assert e != null;
                    log.debug("[{}][{}] Outbox saved: {}", requestId, nodeId, e.getId());
                })
                .then();
    }

    /**
     * Создает payload для создания комментария.
     */
    private ObjectNode createCommentPayload(UUID commentId, UUID authorUserId, String body) {
        return objectMapper.valueToTree(Map.of(
                "commentId", commentId.toString(),
                "authorUserId", authorUserId.toString(),
                "body", body
        ));
    }

    /**
     * Создает payload для обновления комментария.
     */
    private ObjectNode createUpdatePayload(UUID commentId, String oldBody, String newBody, UUID actorUserId) {
        return objectMapper.valueToTree(Map.of(
                "commentId", commentId.toString(),
                "oldBody", oldBody,
                "newBody", newBody,
                "actorUserId", actorUserId.toString()
        ));
    }

    /**
     * Создает payload для удаления комментария.
     */
    private ObjectNode createDeletePayload(UUID commentId, UUID actorUserId, String body) {
        return objectMapper.valueToTree(Map.of(
                "commentId", commentId.toString(),
                "actorUserId", actorUserId.toString(),
                "body", body
        ));
    }

    private int validatePage(Integer page) {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    private int validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return issueProperties.pagination().defaultPageSize();
        }
        return Math.min(pageSize, issueProperties.pagination().maxPageSize());
    }
}