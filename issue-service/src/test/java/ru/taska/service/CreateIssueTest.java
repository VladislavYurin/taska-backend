package ru.taska.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.OutboxEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateIssueTest extends IssueServiceImplTest {

    @BeforeEach
    void setUp() {
        when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1));
        when(issueRepository.save(any()))
                .thenAnswer(invocation -> {
                    Issue issue = invocation.getArgument(0);
                    return Mono.just(issue.toBuilder().id(UUID.randomUUID()).build());
                });
        when(issueHistoryRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((IssueHistory) invocation.getArgument(0)));
        when(outboxEventRepository.save(any()))
                .thenAnswer(invocation -> Mono.just((OutboxEvent) invocation.getArgument(0)));
    }

    @Test
    void shouldCallProjectCounterOnIssueCreation() {
        issueService.createIssue(
                PROJECT_ID, IssueType.TASK, "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        verify(projectCounterRepository, times(1)).getNextIssueNumberAndIncrement(PROJECT_ID);
    }

    @Test
    void shouldAssignIssueNumberFromCounter() {
        int nextIssueNumber = 5;
        when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(nextIssueNumber));

        Issue result = issueService.createIssue(
                PROJECT_ID, IssueType.BUG, "Ошибка", "Описание", IssuePriority.HIGH, REPORTER_ID
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.getIssueNumber()).isEqualTo(nextIssueNumber);
    }

    @Test
    void shouldIncrementCounterForEachCreatedIssue() {
        when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1))
                .thenReturn(Mono.just(2));

        Issue first = issueService.createIssue(
                PROJECT_ID, IssueType.TASK, "Задача 1", null, IssuePriority.LOW, REPORTER_ID
        ).block();
        Issue second = issueService.createIssue(
                PROJECT_ID, IssueType.TASK, "Задача 2", null, IssuePriority.LOW, REPORTER_ID
        ).block();

        verify(projectCounterRepository, times(2)).getNextIssueNumberAndIncrement(PROJECT_ID);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getIssueNumber()).isEqualTo(1);
        assertThat(second.getIssueNumber()).isEqualTo(2);
    }

    @Test
    void shouldSaveOutboxEventOnIssueCreation() {
        issueService.createIssue(
                PROJECT_ID, IssueType.TASK, "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(captor.capture());

        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getAggregateType()).isEqualTo("issue");
        assertThat(savedEvent.getEventType()).isEqualTo("IssueCreated");
        assertThat(savedEvent.getAggregateId()).isNotNull();
    }
}
