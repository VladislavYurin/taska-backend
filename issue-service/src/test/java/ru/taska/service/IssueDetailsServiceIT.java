package ru.taska.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.AbstractIT;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueComment;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.aggregate.IssueDetailsAggregate;
import ru.taska.domain.projection.IssueLinkDetail;
import ru.taska.domain.projection.TargetIssue;
import ru.taska.domain.dto.UserSummary;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueCommentRepository;
import ru.taska.repository.IssueLinkRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.attachment.AttachmentService;
import ru.taska.transport.grpc.profile.GrpcAuthServiceClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Rollback(false)
public class IssueDetailsServiceIT extends AbstractIT {
    @Autowired
    private IssueDetailsService issueDetailsService;

    @MockitoBean
    private LabelService labelService;
    @MockitoBean
    private IssueWatcherService watcherService;
    @MockitoBean
    private AttachmentService attachmentService;
    @MockitoBean
    private IssueHistoryService issueHistoryService;
    @MockitoBean
    private GrpcAuthServiceClient grpcAuthServiceClient;
    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;

    @Autowired
    private IssueRepository issueRepository;
    @Autowired
    private IssueCommentRepository issueCommentRepository;
    @Autowired
    private IssueWatcherRepository issueWatcherRepository;
    @Autowired
    private IssueLinkRepository issueLinkRepository;

    private static final String REQUEST_ID = "req-it-1";
    private static final String NODE_ID = "node-it-1";

    private UUID projectId;
    private UUID issueId;
    private UUID reporterId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        issueId = UUID.randomUUID();
        reporterId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();

        issueId = insertIssue("Test", 1).getId();

        Mockito.lenient().when(labelService.getLabels(
                        ArgumentMatchers.eq(REQUEST_ID), ArgumentMatchers.eq(NODE_ID),
                        ArgumentMatchers.any()))
                .thenReturn(Flux.empty());
        Mockito.lenient().when(watcherService.listIssueWatchers(REQUEST_ID, NODE_ID, issueId, actorUserId))
                .thenReturn(Flux.empty());
        Mockito.lenient().when(attachmentService.listAttachments(REQUEST_ID, NODE_ID, issueId, actorUserId))
                .thenReturn(Flux.empty());
        Mockito.lenient().when(issueHistoryService.getHistory(REQUEST_ID, NODE_ID, issueId))
                .thenReturn(Flux.empty());

        UserSummary reporterSummary = new UserSummary(reporterId, "test user", "avatar.png");

        Mockito.lenient().when(grpcAuthServiceClient.getUserProfiles(
                        ArgumentMatchers.anySet(), ArgumentMatchers.eq(REQUEST_ID), ArgumentMatchers.eq(NODE_ID)))
                .thenReturn(Mono.just(Map.of(reporterId, reporterSummary)));

        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                        ArgumentMatchers.eq(REQUEST_ID), ArgumentMatchers.eq(NODE_ID),
                        ArgumentMatchers.eq(projectId), ArgumentMatchers.any(),
                        ArgumentMatchers.anySet()))
                .thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        issueRepository.deleteAll().block();
        issueCommentRepository.deleteAll().block();
        issueWatcherRepository.deleteAll().block();
        issueLinkRepository.deleteAll().block();
    }

    @Test
    @DisplayName("Должен корректно возвращать id автора задачи")
    void shouldReturnCorrectReporterId() {
        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> {
                    Assertions.assertEquals(reporterId, aggregate.issueCore().issue().getReporterId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Не должен падать с 'Parameter commentCount must not be null' у задачи без комментариев и без наблюдателей")
    void shouldNotFailWhenIssueHasNoCommentsAndNoWatchers() {
        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> {
                    Assertions.assertEquals(0L, aggregate.issueCore().commentCount());
                    Assertions.assertFalse(aggregate.issueCore().isWatching());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Корректно считает commentCount при наличии нескольких комментариев")
    void shouldCountCommentsCorrectlyWhenCommentsExist() {
        insertComment(UUID.randomUUID());
        insertComment(UUID.randomUUID());
        insertComment(UUID.randomUUID());

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> Assertions.assertEquals(3L, aggregate.issueCore().commentCount()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Корректно считает commentCount при наличии удаленных комментариев")
    void shouldCountCommentsCorrectlyWhenCommentsWasDeleted() {
        IssueComment comment = insertComment(UUID.randomUUID());
        insertComment(UUID.randomUUID());
        insertComment(UUID.randomUUID());

        comment.setDeletedAt(Instant.now());
        issueCommentRepository.save(comment).block();

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> Assertions.assertEquals(2L, aggregate.issueCore().commentCount()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Корректно определяет isWatching=true, если актор в числе наблюдателей задачи")
    void shouldReportIsWatchingTrueWhenActorIsWatcher() {
        insertWatcher(actorUserId);

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> Assertions.assertTrue(aggregate.issueCore().isWatching()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Корректно определяет isWatching=false, если у задачи есть наблюдатели, но актор не среди них")
    void shouldReportIsWatchingFalseWhenActorIsNotWatcher() {
        insertWatcher(UUID.randomUUID());

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> Assertions.assertFalse(aggregate.issueCore().isWatching()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Пробрасывает NOT_FOUND, если задача с указанным id отсутствует в БД")
    void shouldPropagateNotFoundWhenIssueDoesNotExist() {
        UUID missingIssueId = UUID.randomUUID();

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, missingIssueId, actorUserId);

        StepVerifier.create(result)
                .expectErrorMatches(error ->
                        error instanceof DomainException domainException
                                && domainException.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("Корректно возвращает связаную задачу")
    void shouldReturnLinkedIssuesCorrectly() {
        UUID newIssueId = insertIssue("second issue", 2).getId();

        insertIssueLink(newIssueId);

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, issueId, actorUserId);

        StepVerifier.create(result)
                .assertNext(aggregate -> {
                    IssueLinkDetail issueLinkDetail = aggregate.links().items().getFirst();

                    IssueLink link = issueLinkDetail.link();
                    Assertions.assertEquals(issueId, link.getSourceIssueId());
                    Assertions.assertEquals(newIssueId, link.getTargetIssueId());

                    TargetIssue target = issueLinkDetail.target();
                    Assertions.assertNotNull(target);
                    Assertions.assertEquals(newIssueId, target.id());
                    Assertions.assertEquals(projectId, target.projectId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Пробрасывает NOT_FOUND, если задача с указанным id есть в БД, но задан deleted_at")
    void shouldPropagateNotFoundWhenIssueWasDeleted() {
        Issue newIssue = insertIssue("second issue", 2);

        newIssue.setDeletedAt(Instant.now());
        issueRepository.save(newIssue).block();

        Mono<IssueDetailsAggregate> result =
                issueDetailsService.getIssueDetails(REQUEST_ID, NODE_ID, newIssue.getId(), actorUserId);

        StepVerifier.create(result)
                .expectErrorMatches(error ->
                        error instanceof DomainException domainException
                                && domainException.getStatus() == DomainStatus.NOT_FOUND)
                .verify();
    }

    // ---------- сидинг тестовых данных напрямую через DatabaseClient ----------

    private Issue insertIssue(String key, int number) {
        Issue issue = Issue.builder()
                .projectId(projectId)
                .reporterId(reporterId)
                .issueType(IssueType.TASK)
                .issueNumber(number)
                .issueKey(key)
                .summary("TestSummary")
                .priority(IssuePriority.HIGH)
                .createdAt(Instant.now())
                .statusKey("TODO")
                .version(1)
                .timeSpentMinutes(3)
                .build();
        return issueRepository.save(issue).block();
    }

    private IssueComment insertComment(UUID authorId) {
        IssueComment issueComment = IssueComment.builder()
                .issueId(issueId)
                .authorUserId(authorId)
                .body("Test comment")
                .projectId(projectId)
                .createdAt(Instant.now())
                .version(1)
                .build();
        return issueCommentRepository.save(issueComment).block();
    }

    private void insertWatcher(UUID userId) {
        IssueWatcher issueWatcher = IssueWatcher.builder()
                .issueId(issueId)
                .projectId(projectId)
                .userId(userId)
                .createdBy(userId)
                .createdAt(Instant.now())
                .build();
        issueWatcherRepository.save(issueWatcher).block();
    }

    private void insertIssueLink(UUID targetIssueId) {
        IssueLink link = IssueLink.builder()
                .linkType(IssueLinkType.BLOCKS)
                .sourceIssueId(issueId)
                .targetIssueId(targetIssueId)
                .createdBy(actorUserId)
                .projectId(projectId)
                .build();

        issueLinkRepository.save(link).block();
    }
}
