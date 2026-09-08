package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.config.props.IssueProperties;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueComment;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.PageResult;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueCommentRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.service.impl.CommentServiceImpl;
import ru.taska.transport.grpc.project.ProjectRoleChecker;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для CommentServiceImpl")
class CommentServiceImplTest {

    @Mock
    private IssueProperties issueProperties;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueCommentRepository commentRepository;

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private ProjectRoleChecker projectRoleChecker;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CommentServiceImpl commentService;

    private UUID projectId;
    private UUID issueId;
    private UUID actorUserId;
    private UUID commentId;
    private String requestId;
    private String nodeId;
    private String body;
    private Issue issue;
    private IssueComment comment;
    private IssueHistory issueHistory;
    private OutboxEvent outboxEvent;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        issueId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        commentId = UUID.randomUUID();
        requestId = "req-123";
        nodeId = "node-123";
        body = "Test comment body";

        // AllowedRoles: полный набор ролей из IssueProperties
        IssueProperties.AllowedRoles allowedRoles = new IssueProperties.AllowedRoles(
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // createIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // assignIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // updateIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // deleteIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // issueTransitionRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // getIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // listIssueRoles

                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // createIssueLinksRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // deleteIssueLinksRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // listIssueLinksRoles

                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // uploadAttachmentRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // viewAttachmentRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // deleteOwnAttachmentRoles
                Set.of(ProjectRole.ADMIN),                     // deleteAttachmentRoles

                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // commentRoles

                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // watchIssueRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // listWatchersRoles
                Set.of(ProjectRole.ADMIN), // manageWatchersRoles
                /// роли для labels
                Set.of(ProjectRole.ADMIN), // createProjectLabelRoles
                Set.of(ProjectRole.ADMIN), // updateProjectLabelRoles
                Set.of(ProjectRole.ADMIN), // deleteProjectLabelRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // listProjectLabelRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // addIssueLabelRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER), // removeIssueLabelRoles
                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER, ProjectRole.VIEWER), // listIssueLabelRoles

                Set.of(ProjectRole.ADMIN, ProjectRole.MEMBER)   // searchIssueRoles

        );

        lenient().when(issueProperties.allowedRoles()).thenReturn(allowedRoles);

        // Настройка IssueProperties.Pagination
        IssueProperties.Pagination paginationConfig = new IssueProperties.Pagination(10, 50);
        lenient().when(issueProperties.pagination()).thenReturn(paginationConfig);

        // Создание задачи
        issue = Issue.builder()
                .id(issueId)
                .projectId(projectId)
                .summary("Test issue")
                .description("Test description")
                .statusKey("TODO")
                .reporterId(actorUserId)
                .build();

        // Создание комментария
        comment = IssueComment.builder()
                .id(commentId)
                .issueId(issueId)
                .projectId(projectId)
                .authorUserId(actorUserId)
                .body(body)
                .version(1)
                .createdAt(Instant.now())
                .build();

        // Создание истории
        issueHistory = IssueHistory.builder()
                .id(UUID.randomUUID())
                .issueId(issueId)
                .eventType(IssueEventType.COMMENT_CREATED)
                .actorUserId(actorUserId)
                .occurredAt(Instant.now())
                .build();

        // Создание outbox события
        outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("ISSUE")
                .aggregateId(issueId)
                .eventType("COMMENT_CREATED")
                .build();
    }

    // ==================== ТЕСТЫ ДЛЯ addComment ====================

    @Test
    @DisplayName("addComment: должен успешно добавить комментарий")
    void addComment_success() {
        // Arrange
        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.save(any(IssueComment.class)))
                .thenReturn(Mono.just(comment));

        when(issueHistoryService.saveIssueHistory(anyString(), anyString(), any(UUID.class), any(UUID.class), any(IssueEventType.class), any()))
                .thenReturn(Mono.just(issueHistory));

        when(outboxEventService.saveOutboxEvent(anyString(), anyString(), any(AggregateType.class), any(), any(EventType.class), any()))
                .thenReturn(Mono.just(outboxEvent));

        ObjectNode mockObjectNode = new ObjectMapper().createObjectNode();
        when(objectMapper.valueToTree(any())).thenReturn(mockObjectNode);

        // Act
        Mono<IssueComment> result = commentService.addComment(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                body
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(savedComment -> savedComment.getId().equals(commentId))
                .verifyComplete();

        verify(projectRoleChecker).checkProjectRole(
                eq(requestId), eq(nodeId), eq(projectId), eq(actorUserId), any(Set.class)
        );
        verify(issueRepository).findActiveByIdForUpdate(issueId);
        verify(commentRepository).save(any(IssueComment.class));
    }

    @Test
    @DisplayName("addComment: должен выбросить ошибку, если задача не найдена")
    void addComment_issueNotFound() {
        // Arrange
        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.empty());

        // Act
        Mono<IssueComment> result = commentService.addComment(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                body
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assert error instanceof DomainException;
                    DomainException ex = (DomainException) error;
                    assert ex.getStatus() == DomainStatus.NOT_FOUND;
                    assert ex.getMessage().contains("Issue not found");
                })
                .verify();
    }

    // ==================== ТЕСТЫ ДЛЯ updateComment ====================

    @Test
    @DisplayName("updateComment: должен успешно обновить комментарий")
    void updateComment_success() {
        // Arrange
        String newBody = "Updated comment body";
        IssueComment updatedComment = IssueComment.builder()
                .id(commentId)
                .issueId(issueId)
                .projectId(projectId)
                .authorUserId(actorUserId)
                .body(newBody)
                .version(2)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.findActiveById(commentId))
                .thenReturn(Mono.just(comment));

        when(commentRepository.updateWithVersionCheckAndAuthor(
                eq(commentId), eq(newBody), eq(1), eq(actorUserId)))
                .thenReturn(Mono.just(updatedComment));

        when(issueHistoryService.saveIssueHistory(anyString(), anyString(), any(UUID.class), any(UUID.class), any(IssueEventType.class), any()))
                .thenReturn(Mono.just(issueHistory));

        when(outboxEventService.saveOutboxEvent(anyString(), anyString(), any(AggregateType.class), any(), any(EventType.class), any()))
                .thenReturn(Mono.just(outboxEvent));

        ObjectNode mockObjectNode = new ObjectMapper().createObjectNode();
        when(objectMapper.valueToTree(any())).thenReturn(mockObjectNode);

        // Act
        Mono<IssueComment> result = commentService.updateComment(
                requestId,
                nodeId,
                issueId,
                commentId,
                actorUserId,
                newBody
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(savedComment -> savedComment.getBody().equals(newBody))
                .verifyComplete();

        verify(issueRepository).findActiveByIdForUpdate(issueId);
        verify(commentRepository).updateWithVersionCheckAndAuthor(
                eq(commentId), eq(newBody), eq(1), eq(actorUserId)
        );
    }

    @Test
    @DisplayName("updateComment: должен выбросить ошибку при конфликте версий")
    void updateComment_versionConflict() {
        // Arrange
        String newBody = "Updated comment body";

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.findActiveById(commentId))
                .thenReturn(Mono.just(comment));

        when(commentRepository.updateWithVersionCheckAndAuthor(
                eq(commentId), eq(newBody), eq(1), eq(actorUserId)))
                .thenReturn(Mono.empty());

        // Act
        Mono<IssueComment> result = commentService.updateComment(
                requestId,
                nodeId,
                issueId,
                commentId,
                actorUserId,
                newBody
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assert error instanceof DomainException;
                    DomainException ex = (DomainException) error;
                    assert ex.getStatus() == DomainStatus.FAILED_PRECONDITION;
                    assert ex.getMessage().contains("Comment was modified by another user");
                })
                .verify();
    }

    @Test
    @DisplayName("updateComment: должен выбросить ошибку, если комментарий не найден")
    void updateComment_commentNotFound() {
        // Arrange
        String newBody = "Updated comment body";

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.findActiveById(commentId))
                .thenReturn(Mono.empty());

        // Act
        Mono<IssueComment> result = commentService.updateComment(
                requestId,
                nodeId,
                issueId,
                commentId,
                actorUserId,
                newBody
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assert error instanceof DomainException;
                    DomainException ex = (DomainException) error;
                    assert ex.getStatus() == DomainStatus.NOT_FOUND;
                    assert ex.getMessage().contains("Comment not found");
                })
                .verify();
    }

    // ==================== ТЕСТЫ ДЛЯ deleteComment ====================

    @Test
    @DisplayName("deleteComment: должен успешно удалить комментарий")
    void deleteComment_success() {
        // Arrange
        IssueComment deletedComment = IssueComment.builder()
                .id(commentId)
                .issueId(issueId)
                .projectId(projectId)
                .authorUserId(actorUserId)
                .body(body)
                .version(2)
                .deletedAt(Instant.now())
                .build();

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.findActiveById(commentId))
                .thenReturn(Mono.just(comment));

        when(commentRepository.softDeleteWithVersionCheck(eq(commentId), eq(1)))
                .thenReturn(Mono.just(deletedComment));

        when(issueHistoryService.saveIssueHistory(anyString(), anyString(), any(UUID.class), any(UUID.class), any(IssueEventType.class), any()))
                .thenReturn(Mono.just(issueHistory));

        when(outboxEventService.saveOutboxEvent(anyString(), anyString(), any(AggregateType.class), any(), any(EventType.class), any()))
                .thenReturn(Mono.just(outboxEvent));

        ObjectNode mockObjectNode = new ObjectMapper().createObjectNode();
        when(objectMapper.valueToTree(any())).thenReturn(mockObjectNode);

        // Act
        Mono<IssueComment> result = commentService.deleteComment(
                requestId,
                nodeId,
                issueId,
                commentId,
                actorUserId
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(deleted -> deleted.getDeletedAt() != null)
                .verifyComplete();

        verify(issueRepository).findActiveByIdForUpdate(issueId);
        verify(commentRepository).softDeleteWithVersionCheck(eq(commentId), eq(1));
    }

    @Test
    @DisplayName("deleteComment: должен выбросить ошибку, если комментарий не принадлежит пользователю")
    void deleteComment_notAuthor() {
        // Arrange
        UUID otherUserId = UUID.randomUUID();

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveByIdForUpdate(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.findActiveById(commentId))
                .thenReturn(Mono.just(comment));

        // Act
        Mono<IssueComment> result = commentService.deleteComment(
                requestId,
                nodeId,
                issueId,
                commentId,
                otherUserId
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assert error instanceof DomainException;
                    DomainException ex = (DomainException) error;
                    assert ex.getStatus() == DomainStatus.PERMISSION_DENIED;
                    assert ex.getMessage().contains("Only the author can modify this comment");
                })
                .verify();
    }

    // ==================== ТЕСТЫ ДЛЯ listComments ====================

    @Test
    @DisplayName("listComments: должен успешно получить список комментариев")
    void listComments_success() {
        // Arrange
        int page = 0;
        int pageSize = 10;

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveById(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.countActiveByIssueId(issueId))
                .thenReturn(Mono.just(1L));

        when(commentRepository.findActiveByIssueIdOrderByCreatedAtDesc(issueId))
                .thenReturn(Flux.just(comment));

        // Act
        Mono<PageResult<IssueComment>> result = commentService.listComments(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                page,
                pageSize
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(pageResult -> pageResult.totalCount() == 1)
                .verifyComplete();

        verify(issueRepository).findActiveById(issueId);
        verify(projectRoleChecker).checkProjectRole(
                eq(requestId), eq(nodeId), eq(projectId), eq(actorUserId), any(Set.class)
        );
        verify(commentRepository).countActiveByIssueId(issueId);
        verify(commentRepository).findActiveByIssueIdOrderByCreatedAtDesc(issueId);
    }

    @Test
    @DisplayName("listComments: должен использовать значения по умолчанию для пагинации")
    void listComments_defaultPagination() {
        // Arrange
        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveById(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.countActiveByIssueId(issueId))
                .thenReturn(Mono.just(0L));

        when(commentRepository.findActiveByIssueIdOrderByCreatedAtDesc(issueId))
                .thenReturn(Flux.empty());

        // Act
        Mono<PageResult<IssueComment>> result = commentService.listComments(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                null,
                null
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(pageResult -> pageResult.totalCount() == 0)
                .verifyComplete();

        verify(commentRepository).findActiveByIssueIdOrderByCreatedAtDesc(issueId);
    }

    @Test
    @DisplayName("listComments: должен ограничивать pageSize максимальным значением")
    void listComments_maxPageSize() {
        // Arrange
        int page = 0;
        int pageSize = 100;

        when(projectRoleChecker.checkProjectRole(anyString(), anyString(), any(UUID.class), any(UUID.class), any(Set.class)))
                .thenReturn(Mono.empty());

        when(issueRepository.findActiveById(issueId))
                .thenReturn(Mono.just(issue));

        when(commentRepository.countActiveByIssueId(issueId))
                .thenReturn(Mono.just(0L));

        when(commentRepository.findActiveByIssueIdOrderByCreatedAtDesc(issueId))
                .thenReturn(Flux.empty());

        // Act
        Mono<PageResult<IssueComment>> result = commentService.listComments(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                page,
                pageSize
        );

        // Assert
        StepVerifier.create(result)
                .expectNextMatches(pageResult -> pageResult.totalCount() == 0)
                .verifyComplete();
    }

    @Test
    @DisplayName("listComments: должен выбросить ошибку, если задача не найдена")
    void listComments_issueNotFound() {
        // Arrange
        when(issueRepository.findActiveById(issueId))
                .thenReturn(Mono.empty());

        when(commentRepository.countActiveByIssueId(issueId))
                .thenReturn(Mono.just(0L));

        when(commentRepository.findActiveByIssueIdOrderByCreatedAtDesc(issueId))
                .thenReturn(Flux.empty());

        // Act
        Mono<PageResult<IssueComment>> result = commentService.listComments(
                requestId,
                nodeId,
                issueId,
                actorUserId,
                0,
                10
        );

        // Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(error -> {
                    assert error instanceof DomainException;
                    DomainException ex = (DomainException) error;
                    assert ex.getStatus() == DomainStatus.NOT_FOUND;
                    assert ex.getMessage().contains("Issue not found");
                })
                .verify();
    }
}