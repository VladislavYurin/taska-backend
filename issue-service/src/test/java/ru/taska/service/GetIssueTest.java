package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.ProjectRole;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;

class GetIssueTest extends IssueServiceImplTest {

    private Set<ProjectRole> allowedRoles;
    private int mockHistorySize;

    @BeforeEach
    void setUp() {
        allowedRoles = Set.of(
                ProjectRole.ADMIN,
                ProjectRole.MEMBER,
                ProjectRole.VIEWER
        );
        mockHistorySize = 50;

        Mockito.lenient().when(issueProperties.allowedRoles().getIssueRoles()).thenReturn(allowedRoles);
        Mockito.lenient().when(issueProperties.card().maxHistorySize()).thenReturn(mockHistorySize);
    }

    private Issue buildIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueNumber(1)
                .issueKey("ABC-1")
                .issueType(IssueType.TASK)
                .summary("Тестовая задача")
                .description("Описание")
                .statusKey("TODO")
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
    @DisplayName("Должен успешно вернуть задачу вместе с историей изменений")
    void shouldReturnIssueWithHistory() {
        Issue issue = buildIssue();
        IssueHistory history = buildHistory();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));

        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles)
                )
                .thenReturn(Mono.empty());

        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.any(Limit.class)))
                .thenReturn(Flux.just(history));

        IssueWithHistory result = issueService.getIssue(
                REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getIssue()).isEqualTo(issue);
        Assertions.assertThat(result.getHistory()).containsExactly(history);

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueProperties.allowedRoles()).getIssueRoles();
        Mockito.verify(issueProperties.card()).maxHistorySize();
        Mockito.verify(issueHistoryRepository).findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.eq(Limit.of(mockHistorySize)));
    }

    @Test
    @DisplayName("Должен вернуть все записи истории отсортированными")
    void shouldReturnAllHistoryEntriesOrdered() {
        Issue issue = buildIssue();
        IssueHistory first = buildHistory();
        IssueHistory second = buildHistory();
        IssueHistory third = buildHistory();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));

        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles)
                )
                .thenReturn(Mono.empty());

        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.any(Limit.class)))
                .thenReturn(Flux.fromIterable(List.of(first, second, third)));

        IssueWithHistory result = issueService.getIssue(
                REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID
        ).block();

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(result.getHistory()).containsExactly(first, second, third);

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueProperties.allowedRoles()).getIssueRoles();
        Mockito.verify(issueProperties.card()).maxHistorySize();
    }

    @Test
    @DisplayName("Должен выбросить исключение NOT_FOUND, если задача не существует в БД")
    void shouldThrowNotFoundWhenIssueDoesNotExist() {
        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.getIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID)
                )
                .expectErrorMatches(ex ->
                        ex instanceof DomainException domainEx &&
                                domainEx.getStatus() == DomainStatus.NOT_FOUND
                )
                .verify();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(projectRoleChecker, issueHistoryRepository);
    }

    @Test
    @DisplayName("Должен пробрасывать ошибку из репозитория истории наверх по потоку")
    void shouldPropagateErrorFromHistoryRepository() {
        Issue issue = buildIssue();

        Mockito.when(issueRepository.findActiveById(ISSUE_ID)).thenReturn(Mono.just(issue));

        Mockito.when(projectRoleChecker.checkProjectRole(
                        REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles)
                )
                .thenReturn(Mono.empty());

        Mockito.when(issueHistoryRepository.findByIssueIdOrderByOccurredAtDesc(Mockito.eq(ISSUE_ID), Mockito.any(Limit.class)))
                .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(issueService.getIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID)
                )
                .expectErrorMatches(ex ->
                        ex instanceof RuntimeException &&
                                ex.getMessage().equals("DB error")
                )
                .verify();

        Mockito.verify(issueRepository).findActiveById(ISSUE_ID);
        Mockito.verify(issueProperties.allowedRoles()).getIssueRoles();
        Mockito.verify(issueProperties.card()).maxHistorySize();
        Mockito.verify(projectRoleChecker).checkProjectRole(REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_USER_ID, allowedRoles);
    }
}
