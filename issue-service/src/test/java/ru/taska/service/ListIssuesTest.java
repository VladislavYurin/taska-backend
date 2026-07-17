package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectRole;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

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
        Mockito.lenient().when(issueProperties.list().defaultPageSize()).thenReturn(10);
        Mockito.lenient().when(issueProperties.list().maxPageSize()).thenReturn(50);
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
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(REPORTER_ID)
                .build();
    }

    @Test
    @DisplayName("Должен успешно возвращать задачи из репозитория")
    void shouldReturnIssuesFromRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "TODO", ASSIGNEE_ID, 0, 10)
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
    @DisplayName("Должен корректно передавать null-фильтры в репозиторий")
    void shouldPassNullFiltersToRepository() {
        Issue issue = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(1L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
        Mockito.verify(issueRepository).countByFilter(eq(PROJECT_ID), any(), any());
    }

    @Test
    @DisplayName("Должен возвращать пустую страницу, если репозиторий пуст")
    void shouldReturnEmptyPageWhenRepositoryReturnsEmpty() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
    @DisplayName("Должен корректно возвращать множественные задачи")
    void shouldReturnMultipleIssues() {
        Issue first = buildIssue();
        Issue second = buildIssue();
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(2L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
                .thenReturn(Flux.just(first, second));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "TODO", null, 0, 10)
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
    @DisplayName("Должен пробрасывать ошибку из репозитория наверх по потоку")
    void shouldPropagateErrorFromRepository() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
    @DisplayName("Должен рассчитывать корректный сдвиг (offset) для переданной страницы")
    void shouldCalculateCorrectOffsetForPage() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(10L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен сбрасывать страницу в ноль, если передано отрицательное значение")
    void shouldFallbackToPageZeroWhenPageIsNegative() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен откатываться на дефолтный размер страницы, если передан невалидный размер")
    void shouldFallbackToDefaultPageSizeWhenPageSizeIsInvalid() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен ограничивать размер страницы максимальным лимитом, если передано слишком большое значение")
    void shouldClampToMaxPageSizeWhenPageSizeExceedsLimit() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
                .findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("Должен использовать значения по умолчанию, если параметры страницы и ее размера равны null")
    void shouldUseDefaultsWhenPageAndPageSizeAreNull() {
        Mockito.when(issueRepository.countByFilter(eq(PROJECT_ID), any(), any()))
                .thenReturn(Mono.just(0L));
        Mockito.when(issueRepository.findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong()))
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
        Mockito.verify(issueRepository).findByFilter(eq(PROJECT_ID), any(), any(), anyInt(), anyLong());
    }

    @Test
    void shouldReturnIssuesWithAllStatusesWhenStatusKeyIsNull() {
        Issue firstIssue = buildIssue();
        Issue secondIssue = buildIssue();
        Issue thirdIssue = buildIssue();

        firstIssue.setStatusKey("TODO");
        secondIssue.setStatusKey("IN_PROGRESS");
        thirdIssue.setStatusKey("DONE");

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        isNull(),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(3L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        isNull(),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.just(firstIssue, secondIssue, thirdIssue));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        null, ASSIGNEE_ID, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(3);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getStatusKey)
                            .containsExactly("TODO", "IN_PROGRESS", "DONE");
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                isNull(),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                isNull(),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }

    @Test
    void shouldReturnOnlyIssuesWithRequestedArbitraryStatusKey() {
        Issue issue = buildIssue();
        issue.setStatusKey("IN_REVIEW");

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        eq("IN_REVIEW"),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(1L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        eq("IN_REVIEW"),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.just(issue));

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        "IN_REVIEW", ASSIGNEE_ID, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isEqualTo(1);
                    Assertions.assertThat(result.items()).containsExactly(issue);
                    Assertions.assertThat(result.items())
                            .extracting(Issue::getStatusKey)
                            .containsExactly("IN_REVIEW");
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                eq("IN_REVIEW"),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                eq("IN_REVIEW"),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }

    @Test
    void shouldReturnEmptyPageWhenStatusKeyIsUnknownButValidString() {
        String unknownStatusKey = "READY_FOR_TESTING";

        Mockito.when(issueRepository.countByFilter(
                        eq(PROJECT_ID),
                        eq(unknownStatusKey),
                        eq(ASSIGNEE_ID)
                ))
                .thenReturn(Mono.just(0L));

        Mockito.when(issueRepository.findByFilter(
                        eq(PROJECT_ID),
                        eq(unknownStatusKey),
                        eq(ASSIGNEE_ID),
                        anyInt(),
                        anyLong()
                ))
                .thenReturn(Flux.empty());

        StepVerifier.create(issueService.listIssues(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID,
                        unknownStatusKey, ASSIGNEE_ID, 0, 10
                ))
                .assertNext(result -> {
                    Assertions.assertThat(result.totalCount()).isZero();
                    Assertions.assertThat(result.items()).isEmpty();
                })
                .verifyComplete();

        Mockito.verify(issueProperties.allowedRoles()).listIssueRoles();
        Mockito.verify(projectRoleChecker).checkProjectRole(
                REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles
        );

        Mockito.verify(issueRepository).countByFilter(
                eq(PROJECT_ID),
                eq(unknownStatusKey),
                eq(ASSIGNEE_ID)
        );

        Mockito.verify(issueRepository).findByFilter(
                eq(PROJECT_ID),
                eq(unknownStatusKey),
                eq(ASSIGNEE_ID),
                anyInt(),
                anyLong()
        );
    }
}
