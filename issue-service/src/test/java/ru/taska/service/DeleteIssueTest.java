package ru.taska.service;

import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.impl.IssueServiceImpl;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class DeleteIssueTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueHistoryRepository issueHistoryRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private IssueMapper issueMapper;

    @InjectMocks
    private IssueServiceImpl issueService;

    private UUID issueId;
    private UUID actorUserId;
    private String requestId;
    private String nodeId;
    private Issue mockIssue;

    @BeforeEach
    public void setUp() {
        issueId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        requestId = "test-request-id";
        nodeId = "test-node-id";

        mockIssue = new Issue();
        mockIssue.setId(issueId);
        mockIssue.setDeletedAt(Instant.now());
    }

    @Test
    public void testDeleteIssue_Success() {
        IssueHistory mockHistory = new IssueHistory();
        OutboxEvent mockOutbox = new OutboxEvent();

        Mockito.when(issueRepository.softDeleteAndReturn(issueId))
               .thenReturn(Mono.just(mockIssue));

        Mockito.when(issueMapper.buildIssueHistory(mockIssue, IssueEventType.DELETED, actorUserId))
               .thenReturn(mockHistory);

        Mockito.when(issueMapper.buildOutboxEvent(mockIssue, "issue", EventType.ISSUE_DELETED))
               .thenReturn(mockOutbox);

        Mockito.when(issueHistoryRepository.save(Mockito.any(IssueHistory.class)))
                .thenReturn(Mono.just(mockHistory));

        Mockito.when(outboxEventRepository.save(Mockito.any(OutboxEvent.class)))
                .thenReturn(Mono.just(mockOutbox));

        Mono<Issue> resultMono = issueService.deleteIssue(requestId, nodeId, issueId, actorUserId);

        StepVerifier.create(resultMono)
                .assertNext(deletedIssue -> {
                    Assertions.assertEquals(issueId, deletedIssue.getId());
                    Assertions.assertNotNull(deletedIssue.getDeletedAt());
                })
                .expectComplete()
                .verify();

        Mockito.verify(issueRepository, Mockito.times(1)).softDeleteAndReturn(Mockito.any(UUID.class));
        Mockito.verify(issueHistoryRepository, Mockito.times(1)).save(mockHistory);
        Mockito.verify(outboxEventRepository, Mockito.times(1)).save(mockOutbox);
    }

    @Test
    public void testDeleteIssue_NotFound_ThrowsDomainException() {
        Mockito.when(issueRepository.softDeleteAndReturn(issueId))
                .thenReturn(Mono.empty());

        Mono<Issue> resultMono = issueService.deleteIssue(requestId, nodeId, issueId, actorUserId);

        StepVerifier.create(resultMono)
                .expectErrorMatches(throwable -> {
                    if (throwable instanceof DomainException) {
                        DomainException exception = (DomainException) throwable;
                        return exception.getStatus() == DomainStatus.NOT_FOUND
                                && exception.getMessage().contains(issueId.toString());
                    }
                    return false;
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any(Issue.class));
        Mockito.verify(issueHistoryRepository, Mockito.never()).save(Mockito.any(IssueHistory.class));
        Mockito.verify(outboxEventRepository, Mockito.never()).save(Mockito.any(OutboxEvent.class));
    }
}