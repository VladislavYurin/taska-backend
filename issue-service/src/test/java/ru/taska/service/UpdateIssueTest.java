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
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

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

        Mockito.when(outboxEventService.saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(AggregateType.class),
                        Mockito.any(UUID.class), Mockito.eq(EventType.ISSUE_UPDATED), Mockito.any(JsonNode.class)))
                .thenReturn(Mono.empty());
        Mockito.when(issueHistoryService.saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID),
                        Mockito.any(UUID.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(IssueEventType.UPDATED), Mockito.any(JsonNode.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                        REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                        newSummary, newDescription, newPriority, STORY_POINTS,
                        START_DATE, DUE_DATE, ORIGINAL_ESTIMATE_MINUTES,
                        REMAINING_ESTIMATE_MINUTES))
                .assertNext(result -> {
                    Assertions.assertThat(result.getSummary()).isEqualTo(newSummary);
                    Assertions.assertThat(result.getDescription()).isEqualTo(newDescription);
                    Assertions.assertThat(result.getPriority()).isEqualTo(newPriority);
                    Assertions.assertThat(result.getVersion()).isEqualTo(2);
                    Assertions.assertThat(result.getStoryPoints()).isEqualTo(STORY_POINTS);
                    Assertions.assertThat(result.getStartDate()).isEqualTo(START_DATE);
                    Assertions.assertThat(result.getDueDate()).isEqualTo(DUE_DATE);
                    Assertions.assertThat(result.getOriginalEstimateMinutes()).isEqualTo(ORIGINAL_ESTIMATE_MINUTES);
                    Assertions.assertThat(result.getRemainingEstimateMinutes()).isEqualTo(REMAINING_ESTIMATE_MINUTES);
                })
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID),
                Mockito.eq(ACTOR_USER_ID), Mockito.eq(expectedRoles));
        Mockito.verify(payloadSerializer).createIssueUpdatedPayload(Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID),
                Mockito.eq(newSummary), Mockito.eq(newDescription), Mockito.eq(newPriority), Mockito.eq(STORY_POINTS),
                Mockito.eq(START_DATE), Mockito.eq(DUE_DATE), Mockito.eq(ORIGINAL_ESTIMATE_MINUTES), Mockito.eq(REMAINING_ESTIMATE_MINUTES));
        Mockito.verify(issueRepository).save(Mockito.any(Issue.class));
        Mockito.verify(outboxEventService).saveOutboxEvent(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(AggregateType.class),
                Mockito.any(UUID.class), Mockito.eq(EventType.ISSUE_UPDATED), Mockito.any(JsonNode.class));
        Mockito.verify(issueHistoryService).saveIssueHistory(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(UUID.class),
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
                            sameSummary, sameDescription, samePriority, EMPTY_STORY_POINTS,
                            EMPTY_START_DATE, EMPTY_DUE_DATE,
                            EMPTY_ORIGINAL_ESTIMATE_MINUTES, EMPTY_REMAINING_ESTIMATE_MINUTES))
                .expectNext(existingIssue)
                .verifyComplete();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verify(projectRoleChecker).checkProjectRole(Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(PROJECT_ID), Mockito.eq(ACTOR_USER_ID), Mockito.eq(expectedRoles));
        Mockito.verify(payloadSerializer).createIssueUpdatedPayload(Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID), Mockito.eq(sameSummary), Mockito.eq(sameDescription), Mockito.eq(samePriority),
                                                                    Mockito.eq(EMPTY_STORY_POINTS), Mockito.eq(
                        EMPTY_START_DATE), Mockito.eq(EMPTY_DUE_DATE),
                                                                    Mockito.eq(EMPTY_ORIGINAL_ESTIMATE_MINUTES), Mockito.eq(EMPTY_REMAINING_ESTIMATE_MINUTES));

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
                            newSummary, newDescription, newPriority, EMPTY_STORY_POINTS,
                            EMPTY_START_DATE, EMPTY_DUE_DATE,
                            EMPTY_ORIGINAL_ESTIMATE_MINUTES, EMPTY_REMAINING_ESTIMATE_MINUTES))
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
}
