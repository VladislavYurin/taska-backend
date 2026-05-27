package ru.taska.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListIssuesTest extends IssueServiceImplTest {

    private Issue buildIssue(IssueStatus status, UUID assigneeId) {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueType(IssueType.TASK)
                .summary("Задача")
                .statusKey(status)
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .assigneeId(assigneeId)
                .build();
    }

    @Test
    void shouldReturnIssuesFromRepository() {
        Issue issue = buildIssue(IssueStatus.TODO, ASSIGNEE_ID);
        when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID))
                .expectNext(issue)
                .verifyComplete();
    }

    @Test
    void shouldPassNullStatusAndNullAssigneeIdToRepository() {
        Issue issue = buildIssue(IssueStatus.IN_PROGRESS, ASSIGNEE_ID);
        when(issueRepository.findByFilter(PROJECT_ID, null, null))
                .thenReturn(Flux.just(issue));

        issueService.listIssues(PROJECT_ID, null, null).blockLast();

        verify(issueRepository).findByFilter(PROJECT_ID, null, null);
    }

    @Test
    void shouldReturnEmptyFluxWhenRepositoryReturnsEmpty() {
        when(issueRepository.findByFilter(PROJECT_ID, null, null))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null))
                .verifyComplete();
    }

    @Test
    void shouldReturnMultipleIssues() {
        Issue first = buildIssue(IssueStatus.TODO, null);
        Issue second = buildIssue(IssueStatus.TODO, null);
        when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, null))
                .thenReturn(Flux.just(first, second));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, IssueStatus.TODO, null))
                .expectNext(first, second)
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorFromRepository() {
        when(issueRepository.findByFilter(PROJECT_ID, null, null))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.listIssues(PROJECT_ID, null, null))
                .expectErrorMatches(ex ->
                        ex instanceof RuntimeException &&
                                ex.getMessage().equals("DB error")
                )
                .verify();
    }
}
