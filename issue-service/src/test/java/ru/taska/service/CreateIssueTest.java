package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;

import java.util.UUID;

class CreateIssueTest extends IssueServiceImplTest {

    @BeforeEach
    void setUp() {
        Mockito.when(projectServiceClient.getProjectKey(REQUEST_ID, PROJECT_ID))
                .thenReturn(Mono.just("TSK"));
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1));
        Mockito.when(issueRepository.save(Mockito.any()))
                .thenAnswer(invocation -> {
                    Issue issue = invocation.getArgument(0);
                    return Mono.just(issue.toBuilder().id(UUID.randomUUID()).build());
                });
        Mockito.when(issueHistoryRepository.save(Mockito.any()))
                .thenAnswer(invocation -> Mono.just((IssueHistory) invocation.getArgument(0)));
        Mockito.when(outboxEventRepository.save(Mockito.any()))
                .thenAnswer(invocation -> Mono.just((OutboxEvent) invocation.getArgument(0)));
    }

    @Test
    void shouldCallProjectCounterOnIssueCreation() {
        issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.TASK, "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        Mockito.verify(projectCounterRepository, Mockito.times(1)).getNextIssueNumberAndIncrement(PROJECT_ID);
    }

    @Test
    void shouldAssignIssueNumberFromCounter() {
        int nextIssueNumber = 5;
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(nextIssueNumber));

        Issue result = issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.BUG, "Ошибка", "Описание", IssuePriority.HIGH, REPORTER_ID
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getIssueNumber()).isEqualTo(nextIssueNumber);
    }

    @Test
    void shouldIncrementCounterForEachCreatedIssue() {
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(1))
                .thenReturn(Mono.just(2));

        Issue first = issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.TASK, "Задача 1", null, IssuePriority.LOW, REPORTER_ID
        ).block();
        Issue second = issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.TASK, "Задача 2", null, IssuePriority.LOW, REPORTER_ID
        ).block();

        Mockito.verify(projectCounterRepository, Mockito.times(2)).getNextIssueNumberAndIncrement(PROJECT_ID);
        Assertions.assertThat(first).isNotNull();
        Assertions.assertThat(second).isNotNull();
        Assertions.assertThat(first.getIssueNumber()).isEqualTo(1);
        Assertions.assertThat(second.getIssueNumber()).isEqualTo(2);
    }

    @Test
    void shouldAssignCorrectIssueKey() {
        Mockito.when(projectServiceClient.getProjectKey(REQUEST_ID, PROJECT_ID))
                .thenReturn(Mono.just("ABC"));
        Mockito.when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
                .thenReturn(Mono.just(7));

        Issue result = issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.TASK, "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getIssueKey()).isEqualTo("ABC-7");
    }

    @Test
    void shouldSaveOutboxEventOnIssueCreation() {
        issueService.createIssue(
                REQUEST_ID, PROJECT_ID, IssueType.TASK, "Тестовая задача", null, IssuePriority.MEDIUM, REPORTER_ID
        ).block();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        Mockito.verify(outboxEventRepository, Mockito.times(1)).save(captor.capture());

        OutboxEvent savedEvent = captor.getValue();
        Assertions.assertThat(savedEvent.getAggregateType()).isEqualTo("issue");
        Assertions.assertThat(savedEvent.getEventType()).isEqualTo(String.valueOf(EventType.ISSUE_CREATED.getValue()));
        Assertions.assertThat(savedEvent.getAggregateId()).isNotNull();
    }
}
