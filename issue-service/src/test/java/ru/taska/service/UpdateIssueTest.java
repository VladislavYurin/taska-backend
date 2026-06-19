package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.OutboxEvent;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.IssueHistoryRepository;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.OutboxEventRepository;
import ru.taska.service.impl.IssueServiceImpl;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class UpdateIssueTest   {

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

    private static final String REQUEST_ID = "req-123";
    private static final String NODE_ID = "node-1";
    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final UUID ACTOR_USER_ID = UUID.randomUUID();
    private static final String ISSUE_AGGREGATE_TYPE = "issue";

    @Test
    void updateIssue_Success() {
        ReflectionTestUtils.setField(issueService, "objectMapper", new ObjectMapper());

        String oldSummary = "Old Summary";
        String oldDescription = "Old Description";
        IssuePriority oldPriority = IssuePriority.LOW;

        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setSummary(oldSummary);
        existingIssue.setDescription(oldDescription);
        existingIssue.setPriority(oldPriority);
        existingIssue.setVersion(1);

        Issue updatedIssue = new Issue();
        updatedIssue.setId(ISSUE_ID);
        updatedIssue.setSummary(newSummary);
        updatedIssue.setDescription(newDescription);
        updatedIssue.setPriority(newPriority);
        updatedIssue.setVersion(2);

        IssueHistory history = new IssueHistory();
        OutboxEvent event = new OutboxEvent();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(issueMapper.buildIssueHistory(existingIssue, IssueEventType.UPDATED, ACTOR_USER_ID))
               .thenReturn(history);
        Mockito.when(issueMapper.buildOutboxEvent(existingIssue, ISSUE_AGGREGATE_TYPE, IssueEventType.UPDATED))
               .thenReturn(event);

        Mockito.when(issueRepository.save(existingIssue)).thenReturn(Mono.just(updatedIssue));
        Mockito.when(issueHistoryRepository.save(history)).thenReturn(Mono.empty());
        Mockito.when(outboxEventRepository.save(event)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority))
                .expectNext(updatedIssue)
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueRepository).save(existingIssue);

        ArgumentCaptor<IssueHistory> historyCaptor = ArgumentCaptor.forClass(IssueHistory.class);
        Mockito.verify(issueHistoryRepository).save(historyCaptor.capture());
        Assertions.assertThat(historyCaptor.getValue()).isSameAs(history);
        Assertions.assertThat(historyCaptor.getValue().getPayload()).isNotNull();

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository).save(eventCaptor.capture());
        Assertions.assertThat(eventCaptor.getValue()).isSameAs(event);

        Mockito.verify(issueMapper).buildIssueHistory(existingIssue, IssueEventType.UPDATED, ACTOR_USER_ID);
        Mockito.verify(issueMapper).buildOutboxEvent(existingIssue, ISSUE_AGGREGATE_TYPE, IssueEventType.UPDATED);

        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper);
    }

    @Test
    void updateIssue_NotFound() {
        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(exception.getMessage()).contains("Issue with id: " + ISSUE_ID + " was not found");
                })
                .verify();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper);
    }
}