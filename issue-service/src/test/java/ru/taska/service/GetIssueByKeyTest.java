package ru.taska.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.UUID;
import java.util.function.Function;

public class GetIssueByKeyTest extends IssueServiceImplTest {
    private String issueKey;
    private UUID actorUserId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        issueKey = "API-12";
        actorUserId = UUID.randomUUID();
        projectId = UUID.randomUUID();
    }

    private Issue buildIssue(String issueKey, UUID projectId) {
        return Issue.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .issueNumber(12)
                .issueKey(issueKey)
                .issueType(IssueType.TASK)
                .summary("Test issue")
                .statusKey("TODO")
                .priority(IssuePriority.MEDIUM)
                .reporterId(UUID.randomUUID())
                .version(1)
                .build();
    }

    @Nested
    @DisplayName("Позитивные сценарии резолва задачи по ключу")
    class PositiveCases {

        @Test
        @DisplayName("Возвращает задачу, если ключ найден и доступ разрешён")
        void shouldReturnIssueWhenKeyFoundAndAccessGranted() {
            Issue issue = buildIssue(issueKey, projectId);
            Mono<Issue> lookup = Mono.just(issue);

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(issueKey)).thenReturn(lookup);
            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueKey), Mockito.eq(actorUserId),
                            Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any()))
                    .thenReturn(Mono.just(issue));

            Mono<Issue> result = issueService.getIssueByKey(REQUEST_ID, NODE_ID, issueKey, actorUserId);

            StepVerifier.create(result)
                    .assertNext(found -> {
                        Assertions.assertEquals(issue.getId(), found.getId());
                        Assertions.assertEquals(issueKey, found.getIssueKey());
                        Assertions.assertEquals(projectId, found.getProjectId());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Передаёт в guard именно issueKey как identifier, а не issueId")
        void shouldPassIssueKeyAsIdentifierToGuard() {
            Issue issue = buildIssue(issueKey, projectId);

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(issueKey)).thenReturn(Mono.just(issue));
            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueKey), Mockito.eq(actorUserId),
                            Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any()))
                    .thenReturn(Mono.just(issue));

            StepVerifier.create(issueService.getIssueByKey(REQUEST_ID, NODE_ID, issueKey, actorUserId))
                    .expectNextCount(1)
                    .verifyComplete();

            Mockito.verify(issueAccessGuard).verifyReadAccess(
                    Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueKey), Mockito.eq(actorUserId),
                    Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any());
        }

        @Test
        @DisplayName("Корректно извлекает projectId через Issue::getProjectId, передаваемый в guard")
        void shouldExtractProjectIdCorrectly() {
            Issue issue = buildIssue(issueKey, projectId);

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(issueKey)).thenReturn(Mono.just(issue));

            ArgumentCaptor<Function<Issue, UUID>> extractorCaptor = ArgumentCaptor.forClass(Function.class);

            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueKey), Mockito.eq(actorUserId),
                            Mockito.any(), extractorCaptor.capture()))
                    .thenReturn(Mono.just(issue));

            StepVerifier.create(issueService.getIssueByKey(REQUEST_ID, NODE_ID, issueKey, actorUserId))
                    .expectNextCount(1)
                    .verifyComplete();

            Assertions.assertEquals(projectId, extractorCaptor.getValue().apply(issue));
        }
    }

    @Nested
    @DisplayName("Граничные сценарии")
    class EdgeCases {

        @Test
        @DisplayName("Пробрасывает NOT_FOUND, если задача с таким ключом не найдена")
        void shouldPropagateNotFoundWhenKeyDoesNotExist() {
            String missingKey = "API-999";
            DomainException notFound = new DomainException(DomainStatus.NOT_FOUND, "Issue not found: " + missingKey);

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(missingKey)).thenReturn(Mono.empty());
            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(missingKey), Mockito.eq(actorUserId),
                            Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any()))
                    .thenReturn(Mono.error(notFound));

            Mono<Issue> result = issueService.getIssueByKey(REQUEST_ID, NODE_ID, missingKey, actorUserId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == notFound)
                    .verify();
        }

        @Test
        @DisplayName("Пробрасывает FORBIDDEN, если актор не имеет роли в проекте найденной задачи")
        void shouldPropagateForbiddenWhenAccessDenied() {
            Issue issue = buildIssue(issueKey, projectId);
            DomainException forbidden = new DomainException(DomainStatus.PERMISSION_DENIED, "Permission denied");

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(issueKey)).thenReturn(Mono.just(issue));
            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(issueKey), Mockito.eq(actorUserId),
                            Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any()))
                    .thenReturn(Mono.error(forbidden));

            Mono<Issue> result = issueService.getIssueByKey(REQUEST_ID, NODE_ID, issueKey, actorUserId);

            StepVerifier.create(result)
                    .expectErrorMatches(error -> error == forbidden)
                    .verify();
        }

        @Test
        @DisplayName("Резолвит задачу по ключу независимо от регистра, в котором он передан")
        void shouldResolveIssueRegardlessOfKeyCase() {
            String lowerCaseKey = "api-12";
            Issue issue = buildIssue("API-12", projectId);

            Mockito.when(issueRepository.findActiveByKeyIgnoreCase(lowerCaseKey)).thenReturn(Mono.just(issue));
            Mockito.when(issueAccessGuard.verifyReadAccess(
                            Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(lowerCaseKey), Mockito.eq(actorUserId),
                            Mockito.any(), ArgumentMatchers.<Function<Issue, UUID>>any()))
                    .thenReturn(Mono.just(issue));

            StepVerifier.create(issueService.getIssueByKey(REQUEST_ID, NODE_ID, lowerCaseKey, actorUserId))
                    .assertNext(found -> Assertions.assertEquals("API-12", found.getIssueKey()))
                    .verifyComplete();

            Mockito.verify(issueRepository).findActiveByKeyIgnoreCase(lowerCaseKey);
        }
    }
}
