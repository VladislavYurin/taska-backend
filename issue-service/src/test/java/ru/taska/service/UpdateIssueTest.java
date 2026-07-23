package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.ProjectRole;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

public class UpdateIssueTest extends IssueServiceImplTest {
    private Set<ProjectRole> expectedRoles;

    @BeforeEach
    void setUpProps() {
        expectedRoles = Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN);

        Mockito.lenient()
                .when(issueProperties.allowedRoles().updateIssueRoles())
                .thenReturn(expectedRoles);
    }

    @DisplayName("Успешное обновление полей задачи")
    @Test
    void updateIssue_Success() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary("Old Summary");
        existingIssue.setDescription("Old Description");
        existingIssue.setPriority(IssuePriority.LOW);
        existingIssue.setVersion(1);

        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));

        Mockito.when(projectRoleChecker.checkProjectRole(
                Mockito.eq(REQUEST_ID),
                Mockito.eq(NODE_ID),
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID),
                Mockito.eq(expectedRoles)
        )).thenReturn(Mono.empty());

        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenAnswer(inv -> Mono.just((Issue) inv.getArgument(0)));

        Mockito.when(outboxEventService.saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.any(Issue.class), Mockito.eq(EventType.ISSUE_UPDATED), Mockito.any(JsonNode.class)))
                .thenReturn(Mono.empty());
        Mockito.when(issueHistoryService.saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.UPDATED), Mockito.any(JsonNode.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority,
                        null, null, null, null, null))
                .assertNext(result -> {
                    Assertions.assertThat(result.getSummary()).isEqualTo(newSummary);
                    Assertions.assertThat(result.getDescription()).isEqualTo(newDescription);
                    Assertions.assertThat(result.getPriority()).isEqualTo(newPriority);
                    Assertions.assertThat(result.getVersion()).isEqualTo(2);
                })
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(expectedRoles));
        Mockito.verify(payloadSerializer).createIssueUpdatedPayload(Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID),
                Mockito.eq(newSummary), Mockito.eq(newDescription), Mockito.eq(newPriority),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(issueRepository).save(Mockito.any(Issue.class));
        Mockito.verify(outboxEventService).saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(Issue.class),
                Mockito.eq(EventType.ISSUE_UPDATED), Mockito.any(JsonNode.class));
        Mockito.verify(issueHistoryService).saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(Issue.class),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.UPDATED), Mockito.any(JsonNode.class));

        Mockito.verifyNoMoreInteractions(issueRepository, issueHistoryService, outboxEventService, projectRoleChecker);
    }

    @DisplayName("Возврат задачи без изменений, если переданные поля совпадают с текущими")
    @Test
    void updateIssue_return_NoChanges() {
        String sameSummary = "Same Summary";
        String sameDescription = "Same Description";
        IssuePriority samePriority = IssuePriority.MEDIUM;

        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary(sameSummary);
        existingIssue.setDescription(sameDescription);
        existingIssue.setPriority(samePriority);
        existingIssue.setVersion(1);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));

        Mockito.when(projectRoleChecker.checkProjectRole(
                Mockito.eq(REQUEST_ID),
                Mockito.eq(NODE_ID),
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID),
                Mockito.eq(expectedRoles)
        )).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        sameSummary, sameDescription, samePriority,
                        null, null, null, null, null))
                .expectNext(existingIssue)
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.eq(expectedRoles));
        Mockito.verify(payloadSerializer).createIssueUpdatedPayload(Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(sameSummary), Mockito.eq(sameDescription), Mockito.eq(samePriority),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.verifyNoMoreInteractions(issueRepository, projectRoleChecker);
        Mockito.verifyNoInteractions(issueHistoryService, outboxEventService);
    }

    @DisplayName("Выбрасывание ошибки, если задачи нет или она отмечена удаленной")
    @Test
    void updateIssue_NotFound_ThrowsDomainException() {
        String newSummary = "New Summary";
        String newDescription = "New Description";
        IssuePriority newPriority = IssuePriority.HIGH;

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority,
                        null, null, null, null, null))
                .expectErrorSatisfies(throwable -> {
                    Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                    DomainException exception = (DomainException) throwable;
                    Assertions.assertThat(exception.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(exception.getMessage()).contains("was not found");
                })
                .verify();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(projectRoleChecker, issueHistoryService, outboxEventService);
    }

    @DisplayName("Успешная запись планировочных полей")
    @Test
    void updatePlanningFields() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setVersion(1);

        Double newStoryPoints = 8.0;
        Instant newStartDate = Instant.now();
        Instant newDueDate = newStartDate.plusSeconds(3600);
        Long newOriginalEstimate = 60L;
        Long newRemainingEstimate = 40L;

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(projectRoleChecker.checkProjectRole(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenAnswer(inv -> Mono.just((Issue) inv.getArgument(0)));
        Mockito.when(outboxEventService.saveOutboxEvent(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(issueHistoryService.saveIssueHistory(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        null, null, null,
                        newStoryPoints, newStartDate, newDueDate, newOriginalEstimate, newRemainingEstimate))
                .assertNext(result -> {
                    Assertions.assertThat(result.getStoryPoints()).isEqualTo(newStoryPoints);
                    Assertions.assertThat(result.getStartDate()).isEqualTo(newStartDate);
                    Assertions.assertThat(result.getDueDate()).isEqualTo(newDueDate);
                    Assertions.assertThat(result.getOriginalEstimateMinutes()).isEqualTo(newOriginalEstimate);
                    Assertions.assertThat(result.getRemainingEstimateMinutes()).isEqualTo(newRemainingEstimate);
                })
                .verifyComplete();
    }

    @DisplayName("Успешная удаление планировочных полей")
    @Test
    void clearNullablePlanningFields() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setStoryPoints(5.0);
        existingIssue.setStartDate(Instant.now());
        existingIssue.setVersion(1);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(projectRoleChecker.checkProjectRole(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        null, null, null,
                        null, null, null, null, null))
                .assertNext(result -> {
                    Assertions.assertThat(result.getStoryPoints()).isEqualTo(5.0);
                    Assertions.assertThat(result.getStartDate()).isNotNull();
                })
                .verifyComplete();
    }

    @DisplayName("Должен сохранить в историю планировочные поля")
    @Test
    void historyContainsPlanningFieldChanges() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setVersion(1);

        Double newStoryPoints = 12.5;

        tools.jackson.databind.node.ObjectNode mockPayload = objectMapper.createObjectNode();
        mockPayload.put("storyPoints", newStoryPoints);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        Mockito.when(projectRoleChecker.checkProjectRole(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenAnswer(inv -> Mono.just((Issue) inv.getArgument(0)));
        Mockito.when(outboxEventService.saveOutboxEvent(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Mono.empty());

        Mockito.doReturn(mockPayload)
                .when(payloadSerializer).createIssueUpdatedPayload(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.when(issueHistoryService.saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.UPDATED), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        null, null, null,
                        newStoryPoints, null, null, null, null))
                .expectNextCount(1)
                .verifyComplete();

        Mockito.verify(issueHistoryService).saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.UPDATED), Mockito.any());
    }
}