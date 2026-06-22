package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;

import java.util.Set;

class ListIssuesTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER,
                ProjectRole.VIEWER
        );

        Mockito.when(issueProperties.allowedRoles().listIssueRoles()).thenReturn(allowedRoles);
        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles)
                )
                .thenReturn(Mono.empty());
    }

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
    void shouldReturnIssuesFromRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, ASSIGNEE_ID, 10, 0L))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        IssueStatus.TODO, ASSIGNEE_ID, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).containsExactly(issue);
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    void shouldPassNullFiltersToRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 0, 10)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).containsExactly(issue))
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 10, 0L);
        Mockito.verify(issueRepository).countByFilter(PROJECT_ID, null, null);
    }

    @Test
    void shouldReturnEmptyPageWhenRepositoryReturnsEmpty() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    void shouldReturnMultipleIssues() {
        Issue first = buildIssue();
        Issue second = buildIssue();
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, IssueStatus.TODO, null))
                .thenReturn(Mono.just(2L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, IssueStatus.TODO, null, 10, 0L))
                .thenReturn(Flux.just(first, second));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        IssueStatus.TODO, null, 0, 10)
                )
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(2);
                    Assertions.assertThat(result.items()).containsExactly(first, second);
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    void shouldPropagateErrorFromRepository() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 0, 10)
                )
                .expectErrorMatches(ex -> ex instanceof RuntimeException
                        && ex.getMessage().equals("DB error"))
                .verify();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
    }

    @Test
    void shouldCalculateCorrectOffsetForPage() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(10L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 5, 10L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 2, 5)
                )
                .assertNext(result -> Assertions.assertThat(result.totalCount()).isEqualTo(10))
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 5, 10L);
    }

    @Test
    void shouldFallbackToPageZeroWhenPageIsNegative() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, 10, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, -1, 10)
                )
                .assertNext(result ->
                        Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, 10, 0L);
    }

    @Test
    void shouldFallbackToDefaultPageSizeWhenPageSizeIsInvalid() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 0, 0)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L);
    }

    @Test
    void shouldClampToMaxPageSizeWhenPageSizeExceedsLimit() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, MAX_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, 0, Integer.MAX_VALUE)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository)
                .findByFilter(PROJECT_ID, null, null, MAX_PAGE_SIZE, 0L);
    }

    @Test
    void shouldUseDefaultsWhenPageAndPageSizeAreNull() {
        Mockito.when(issueRepository.countByFilter(PROJECT_ID, null, null))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, null, null, null)
                )
                .assertNext(result -> Assertions.assertThat(result.items()).isEmpty())
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );
        Mockito.verify(issueRepository).findByFilter(PROJECT_ID, null, null, DEFAULT_PAGE_SIZE, 0L);
    }
}
