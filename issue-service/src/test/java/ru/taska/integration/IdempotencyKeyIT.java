package ru.taska.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.IdempotencyKey;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IdempotencyKeyRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.IssueService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class IdempotencyKeyIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final String IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000006";
    private static final String SUMMARY = "Тестовая задача";

    @BeforeEach
    void setUp() {
        Mockito.when(projectServiceStub.checkProjectMemberRole(Mockito.any(CheckProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                        .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                        .setIsMember(true)
                        .setProjectExists(true)
                        .build()));

        Mockito.when(projectServiceStub.getProject(Mockito.any(GetProjectRequest.class)))
                .thenReturn(Mono.just(ProjectResponse.newBuilder()
                        .setProjectKey("TSK")
                        .build()));

        outboxEventRepository.deleteAll()
                .then(idempotencyKeyRepository.deleteAll())
                .then(issueRepository.deleteAll())
                .block();
    }

    @Test
    void shouldSaveIdempotencyKeyAndReturnCreatedIssue() {
        StepVerifier.create(issueService.createIssue(
                        REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                        SUMMARY, null, null, IssuePriority.MEDIUM, REPORTER_ID,
                        0.0, Instant.now(), Instant.now(), 0L))
                .assertNext(result -> {
                    Assertions.assertNotNull(result.getId());
                    Assertions.assertEquals(SUMMARY, result.getSummary());
                })
                .verifyComplete();

        Assertions.assertEquals(1L, issueRepository.count().block());
        Assertions.assertEquals(1L, outboxEventRepository.count().block());

        IdempotencyKey saved = idempotencyKeyRepository.findByUserIdAndKey(REPORTER_ID, IDEMPOTENCY_KEY).block();
        Assertions.assertNotNull(saved);
        Assertions.assertEquals(IDEMPOTENCY_KEY, saved.getKey());
        Assertions.assertEquals(REPORTER_ID, saved.getUserId());
        Assertions.assertNotNull(saved.getRequestHash());
        Assertions.assertNotNull(saved.getResponse());
        Assertions.assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void shouldReturnPreviousResponseOnSameKeyAndBody() {

        Issue first = issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                SUMMARY, null, null, IssuePriority.MEDIUM, REPORTER_ID,
                0.0, Instant.now(), Instant.now(), 0L).block();
        Assertions.assertNotNull(first);

        long issuesAfterFirst = issueRepository.count().block();
        long outboxAfterFirst = outboxEventRepository.count().block();

        StepVerifier.create(issueService.createIssue(
                        REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                        SUMMARY, null, null, IssuePriority.MEDIUM, REPORTER_ID,
                        0.0, Instant.now(), Instant.now(), 0L))
                .assertNext(result -> {
                    Assertions.assertEquals(first.getId(), result.getId());
                    Assertions.assertEquals(SUMMARY, result.getSummary());
                })
                .verifyComplete();

        Assertions.assertEquals(issuesAfterFirst, issueRepository.count().block());
        Assertions.assertEquals(outboxAfterFirst, outboxEventRepository.count().block());
    }

    @Test
    void shouldThrowFailedPreconditionOnSameKeyDifferentBody() {
        issueService.createIssue(
                REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                SUMMARY, null, null,  IssuePriority.MEDIUM, REPORTER_ID,
                0.0, Instant.now(), Instant.now(), 0L).block();

        long issuesAfterFirst = issueRepository.count().block();

        StepVerifier.create(issueService.createIssue(
                        REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                        "Другое описание из-за которого вернёт ошибку", null,
                        null, IssuePriority.MEDIUM, REPORTER_ID,
                        0.0, Instant.now(), Instant.now(), 0L))
                .expectErrorSatisfies(error -> {
                    DomainException ex = Assertions.assertInstanceOf(DomainException.class, error);
                    Assertions.assertEquals(DomainStatus.FAILED_PRECONDITION, ex.getStatus());
                })
                .verify();

        Assertions.assertEquals(issuesAfterFirst, issueRepository.count().block());
    }

    @Test
    void shouldCreateOnlyOneIssueWhenConcurrentRequestsWithSameKey() {
        int parallelism = 5;

        List<String> results = Flux.merge(
                Flux.range(0, parallelism)
                        .map(i -> issueService.createIssue(
                                        REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY, PROJECT_ID, IssueType.TASK,
                                        SUMMARY, null, null, IssuePriority.MEDIUM, REPORTER_ID,
                                0.0, Instant.now(), Instant.now(), 0L)
                                .map(issue -> "OK")
                                .onErrorResume(e -> Mono.just("ERR:" + e.getClass().getSimpleName()))
                                .subscribeOn(Schedulers.boundedElastic()))
        ).collectList().block();

        Assertions.assertEquals(1L, issueRepository.count().block());
        Assertions.assertEquals(1L, idempotencyKeyRepository.count().block());
        Assertions.assertEquals(1L, outboxEventRepository.count().block());

        Assertions.assertNotNull(results);
        Assertions.assertEquals(parallelism, results.size());
        Assertions.assertTrue(results.contains("OK"));
    }
}
