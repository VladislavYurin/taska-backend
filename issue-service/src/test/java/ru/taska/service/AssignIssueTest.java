package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

public class AssignIssueTest extends IssueServiceImplTest {
    @Test
    void assignIssueSuccess() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);

        Issue updatedIssue = new Issue();
        updatedIssue.setId(ISSUE_ID);
        updatedIssue.setAssigneeId(ASSIGNEE_ID);

        IssueHistory history = new IssueHistory();
        OutboxEvent event = new OutboxEvent();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(issueMapper.setIssueAssignee(existingIssue, ASSIGNEE_ID)).thenReturn(updatedIssue);
        Mockito.when(issueRepository.save(updatedIssue)).thenReturn(Mono.just(updatedIssue));
        Mockito.when(issueMapper.buildIssueHistory(updatedIssue, IssueEventType.ASSIGNED, ACTOR_USER_ID))
               .thenReturn(history);
        Mockito.when(issueHistoryRepository.save(history)).thenReturn(Mono.empty());
        Mockito.when(issueMapper.buildOutboxEvent(updatedIssue, "issue", EventType.ISSUE_ASSIGNED))
               .thenReturn(event);
        Mockito.when(outboxEventRepository.save(event)).thenReturn(Mono.empty());
        existingIssue.setAssigneeId(null);

        StepVerifier.create(issueService.assignIssue(ISSUE_ID, ASSIGNEE_ID, ACTOR_USER_ID))
                    .expectNext(updatedIssue)
                    .verifyComplete();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueMapper).setIssueAssignee(existingIssue, ASSIGNEE_ID);
        Mockito.verify(issueRepository).save(updatedIssue);

        ArgumentCaptor<IssueHistory> historyCaptor = ArgumentCaptor.forClass(IssueHistory.class);
        Mockito.verify(issueHistoryRepository).save(historyCaptor.capture());
        Assertions.assertThat(historyCaptor.getValue()).isSameAs(history);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository).save(eventCaptor.capture());
        Assertions.assertThat(eventCaptor.getValue()).isSameAs(event);

        Mockito.verify(issueMapper).buildIssueHistory(updatedIssue, IssueEventType.ASSIGNED, ACTOR_USER_ID);
        Mockito.verify(issueMapper).buildOutboxEvent(updatedIssue, "issue", EventType.ISSUE_ASSIGNED);

        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryRepository, outboxEventRepository, issueMapper);
    }

    @Test
    void assignIssue_AlreadyAssignedToSameUser_ShouldDoNothing() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setAssigneeId(ASSIGNEE_ID);

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(existingIssue));

        StepVerifier.create(issueService.assignIssue(ISSUE_ID, ASSIGNEE_ID, ACTOR_USER_ID))
                    .expectNext(existingIssue)
                    .verifyComplete();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueMapper, Mockito.never()).setIssueAssignee(Mockito.any(), Mockito.any());
        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(issueHistoryRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(outboxEventRepository, Mockito.never()).save(Mockito.any());
        Mockito.verify(issueMapper, Mockito.never()).buildIssueHistory(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(issueMapper, Mockito.never()).buildOutboxEvent(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void assignIssue_IssueDeleted_ShouldNotAssignAndThrowException() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.assignIssue(ISSUE_ID, ASSIGNEE_ID, ACTOR_USER_ID))
                    .expectErrorMatches(throwable -> throwable instanceof DomainException &&
                            ((DomainException) throwable).getStatus() == DomainStatus.NOT_FOUND &&
                            throwable.getMessage().contains("not found"))
                    .verify();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoInteractions(issueHistoryRepository, outboxEventRepository, issueMapper);
        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any());
    }
}
