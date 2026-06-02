package ru.taska.service;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;

class ListIssuesTest extends IssueServiceImplTest {

    private Issue buildIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueType(IssueType.TASK)
                .summary("Задача")
                .statusKey(IssueStatus.TODO)
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .build();
    }

    @Test
    void shouldFailWhenProjectIdIsNull() {
        StepVerifier.create(issueService.listIssues(null, null, null, 0, 10))
                .expectErrorMatches(e -> e instanceof DomainException ex
                        && ex.getStatus() == DomainStatus.INVALID_ARGUMENT)
                .verify();
    }

    @Test
    void shouldReturnIssuesFromRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID, 10, 0L))
               .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID, 0, 10))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).containsExactly(issue);
                })
                .verifyComplete();
    }

    @Test
    void shouldPassNullFiltersToRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 0, 10))
                .assertNext(result -> Assertions.assertThat(result.items()).containsExactly(issue))
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 10, 0L);
        Mockito.verify(issueRepository).countByFilter(PROJECT_ID, null, null);
    }

    @Test
    void shouldReturnEmptyPageWhenRepositoryReturnsEmpty() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 0, 10))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnMultipleIssues() {
        Issue first = buildIssue();
        Issue second = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, IssueStatus.TODO, null))
                .thenReturn(Mono.just(2L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, null, 10, 0L))
                .thenReturn(Flux.just(first, second));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, IssueStatus.TODO, null, 0, 10))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items()).containsExactly(first, second);
                })
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorFromRepository() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 0, 10))
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().equals("DB error"))
                .verify();
    }

    @Test
    void shouldCalculateCorrectOffsetForPage() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(10L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 5, 10L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 2, 5))
                .assertNext(result -> Assertions.assertThat(result.totalCount()).isEqualTo(10))
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 5, 10L);
    }

    @Test
    void shouldFallbackToPageZeroWhenPageIsNegative() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, -1, 10))
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 10, 0L);
    }

    @Test
    void shouldFallbackToDefaultPageSizeWhenPageSizeIsInvalid() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 0, 0))
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L);
    }

    @Test
    void shouldClampToMaxPageSizeWhenPageSizeExceedsLimit() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, MAX_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, 0, Integer.MAX_VALUE))
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, MAX_PAGE_SIZE, 0L);
    }

    @Test
    void shouldUseDefaultsWhenPageAndPageSizeAreNull() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null, null, null))
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L);
    }
}
