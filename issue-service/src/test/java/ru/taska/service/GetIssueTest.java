package ru.taska.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;

import java.util.List;
import java.util.UUID;

class GetIssueTest extends IssueServiceImplTest {

    @Value("${issue.card.max-history-size}")
    private int issueCardMaxHistorySize;

    private Issue buildIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("ABC-1")
                .issueType(IssueType.TASK)
                .summary("Тестовая задача")
                .description("Описание")
                .statusKey(IssueStatus.TODO)
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .build();
    }

    private IssueHistory buildHistory() {
        return IssueHistory.builder()
                .id(UUID.randomUUID())
                .issueId(ISSUE_ID)
                .eventType(IssueEventType.CREATED)
                .actorUserId(REPORTER_ID)
                .build();
    }

    @Test
    void shouldReturnIssueWithHistory() {
        Issue issue = buildIssue();
        IssueHistory history = buildHistory();

        Mockito.when(issueRepository.findByIdAndDeletedAtIsNull(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(ISSUE_ID, Limit.of(issueCardMaxHistorySize)))
                .thenReturn(Flux.just(history));

        IssueWithHistory result = issueService.getIssue(ISSUE_ID).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getIssue()).isEqualTo(issue);
        Assertions.assertThat(result.getHistory()).containsExactly(history);
    }

    @Test
    void shouldReturnAllHistoryEntriesOrdered() {
        Issue issue = buildIssue();
        IssueHistory first = buildHistory();
        IssueHistory second = buildHistory();
        IssueHistory third = buildHistory();

        Mockito.when(issueRepository.findByIdAndDeletedAtIsNull(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(ISSUE_ID, Limit.of(issueCardMaxHistorySize)))
                .thenReturn(Flux.fromIterable(List.of(first, second, third)));

        IssueWithHistory result = issueService.getIssue(ISSUE_ID).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getHistory()).containsExactly(first, second, third);
    }

    @Test
    void shouldThrowNotFoundWhenIssueDoesNotExist() {
        Mockito.when(issueRepository.findByIdAndDeletedAtIsNull(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.getIssue(ISSUE_ID))
                .expectErrorMatches(ex ->
                        ex instanceof DomainException domainEx &&
                                domainEx.getStatus() == DomainStatus.NOT_FOUND
                )
                .verify();
    }

    @Test
    void shouldPropagateErrorFromHistoryRepository() {
        Issue issue = buildIssue();

        Mockito.when(issueRepository.findByIdAndDeletedAtIsNull(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(ISSUE_ID, Limit.of(issueCardMaxHistorySize)))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.getIssue(ISSUE_ID))
                .expectErrorMatches(ex ->
                        ex instanceof RuntimeException &&
                                ex.getMessage().equals("DB error")
                )
                .verify();
    }
}
