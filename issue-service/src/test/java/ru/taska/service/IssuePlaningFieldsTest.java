package ru.taska.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.ProjectRole;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import tools.jackson.databind.JsonNode;

import java.util.Set;

class PlanningFieldsServiceTest extends IssueServiceImplTest {

    private Set<ProjectRole> createRoles;
    private Set<ProjectRole> updateRoles;

    @BeforeEach
    void setUpRoles() {
        createRoles = Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN);
        updateRoles = Set.of(ProjectRole.MEMBER, ProjectRole.ADMIN);

        Mockito.lenient().when(issueProperties.allowedRoles().createIssueRoles()).thenReturn(createRoles);
        Mockito.lenient().when(issueProperties.allowedRoles().updateIssueRoles()).thenReturn(updateRoles);

        Mockito.lenient().when(issueProperties.idempotencyKeyTtl().ttl()).thenReturn(java.time.Duration.ofHours(24));

        Mockito.lenient().when(idempotencyKeyRepository.findByUserIdAndKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                       org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                       org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                       org.mockito.ArgumentMatchers.anySet()))
               .thenReturn(Mono.empty());
    }

    // ==================== CREATE: без planning fields ====================

    @DisplayName("Создание задачи без planning fields — все поля null")
    @Test
    void createIssue_withoutPlanningFields_success() {
        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                       eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(REPORTER_ID), eq(createRoles)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(grpcProjectServiceClient.getProjectKeyInternal(eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID)))
               .thenReturn(Mono.just("TSK"));
        Mockito.lenient().when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
               .thenReturn(Mono.just(1));
        Mockito.lenient().when(idempotencyKeyRepository.findByUserIdAndKey(any(), any()))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(issueRepository.save(any(Issue.class)))
               .thenAnswer(inv -> Mono.just(((Issue) inv.getArgument(0)).toBuilder().id(UUID.randomUUID()).build()));
        Mockito.lenient().when(issueHistoryService.saveIssueCreateHistory(any(), any(), any(Issue.class)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(outboxEventService.saveOutboxEvent(any(), any(), any(AggregateType.class), any(Issue.class)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(idempotencyKeyRepository.save(any()))
               .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        Mockito.lenient().when(issueAutoWatchService.watchReporterOnCreate(any(), any(), any(Issue.class)))
               .thenReturn(Mono.empty());

        StepVerifier.create(issueService.createIssue(
                            REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.TASK,
                            "Задача без планирования", null, IssuePriority.MEDIUM, REPORTER_ID,
                            null, null, null, null, null))
                    .assertNext(issue -> {
                        Assertions.assertThat(issue.getStoryPoints()).isNull();
                        Assertions.assertThat(issue.getStartDate()).isNull();
                        Assertions.assertThat(issue.getDueDate()).isNull();
                        Assertions.assertThat(issue.getOriginalEstimateMinutes()).isNull();
                        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isNull();
                    })
                    .verifyComplete();
    }

    // ==================== CREATE: с валидными planning fields ====================

    @DisplayName("Создание задачи с валидными planning fields — все поля проставлены")
    @Test
    void createIssue_withValidPlanningFields_success() {
        Mockito.lenient().when(projectRoleChecker.checkProjectRole(
                       eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(REPORTER_ID), eq(createRoles)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(grpcProjectServiceClient.getProjectKeyInternal(eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID)))
               .thenReturn(Mono.just("TSK"));
        Mockito.lenient().when(projectCounterRepository.getNextIssueNumberAndIncrement(PROJECT_ID))
               .thenReturn(Mono.just(1));
        Mockito.lenient().when(idempotencyKeyRepository.findByUserIdAndKey(any(), any()))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(issueRepository.save(any(Issue.class)))
               .thenAnswer(inv -> Mono.just(((Issue) inv.getArgument(0)).toBuilder().id(UUID.randomUUID()).build()));
        Mockito.lenient().when(issueHistoryService.saveIssueCreateHistory(any(), any(), any(Issue.class)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(outboxEventService.saveOutboxEvent(any(), any(), any(AggregateType.class), any(Issue.class)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(idempotencyKeyRepository.save(any()))
               .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        Mockito.lenient().when(issueAutoWatchService.watchReporterOnCreate(any(), any(), any(Issue.class)))
               .thenReturn(Mono.empty());

        StepVerifier.create(issueService.createIssue(
                            REQUEST_ID, NODE_ID, IDEMPOTENCY_KEY_1, PROJECT_ID, IssueType.STORY,
                            "Задача с планированием", "Описание", IssuePriority.HIGH, REPORTER_ID,
                            STORY_POINTS, START_DATE, DUE_DATE,
                            ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES))
                    .assertNext(issue -> {
                        Assertions.assertThat(issue.getStoryPoints()).isEqualByComparingTo(STORY_POINTS);
                        Assertions.assertThat(issue.getStartDate()).isEqualTo(START_DATE);
                        Assertions.assertThat(issue.getDueDate()).isEqualTo(DUE_DATE);
                        Assertions.assertThat(issue.getOriginalEstimateMinutes()).isEqualTo(ORIGINAL_ESTIMATE_MINUTES);
                        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(REMAINING_ESTIMATE_MINUTES);
                    })
                    .verifyComplete();
    }

    // ==================== UPDATE: изменение planning fields ====================

    @DisplayName("Обновление planning fields — все переданные значения применяются")
    @Test
    void updateIssue_updatesPlanningFields() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary("Summary");
        existingIssue.setDescription("Description");
        existingIssue.setPriority(IssuePriority.MEDIUM);
        existingIssue.setVersion(1);
        existingIssue.setStoryPoints(null);
        existingIssue.setStartDate(null);
        existingIssue.setDueDate(null);
        existingIssue.setOriginalEstimateMinutes(null);
        existingIssue.setRemainingEstimateMinutes(null);

        when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        when(projectRoleChecker.checkProjectRole(
                eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(ACTOR_USER_ID), eq(updateRoles)))
                .thenReturn(Mono.empty());
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxEventService.saveOutboxEvent(eq(REQUEST_ID), eq(NODE_ID), any(AggregateType.class),
                                                any(UUID.class), eq(EventType.ISSUE_UPDATED), any(JsonNode.class)))
                .thenReturn(Mono.empty());
        when(issueHistoryService.saveIssueHistory(eq(REQUEST_ID), eq(NODE_ID), any(UUID.class),
                                                  eq(ACTOR_USER_ID), any(), any(JsonNode.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                            REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                            "Summary", "Description", IssuePriority.MEDIUM,
                            STORY_POINTS, START_DATE, DUE_DATE,
                            ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES))
                    .assertNext(issue -> {
                        Assertions.assertThat(issue.getStoryPoints()).isEqualByComparingTo(STORY_POINTS);
                        Assertions.assertThat(issue.getStartDate()).isEqualTo(START_DATE);
                        Assertions.assertThat(issue.getDueDate()).isEqualTo(DUE_DATE);
                        Assertions.assertThat(issue.getOriginalEstimateMinutes()).isEqualTo(ORIGINAL_ESTIMATE_MINUTES);
                        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(REMAINING_ESTIMATE_MINUTES);
                    })
                    .verifyComplete();

        verify(issueRepository).save(any(Issue.class));
        verify(issueHistoryService).saveIssueHistory(eq(REQUEST_ID), eq(NODE_ID), any(UUID.class),
                                                     eq(ACTOR_USER_ID), any(), any(JsonNode.class));
        verify(outboxEventService).saveOutboxEvent(eq(REQUEST_ID), eq(NODE_ID), any(AggregateType.class),
                                                   any(UUID.class), eq(EventType.ISSUE_UPDATED), any(JsonNode.class));
    }

    @DisplayName("Частичное обновление — непереданные planning fields затираются")
    @Test
    void updateIssue_partialUpdate_keepsUnpassedFields() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary("Summary");
        existingIssue.setDescription("Description");
        existingIssue.setPriority(IssuePriority.MEDIUM);
        existingIssue.setVersion(1);
        existingIssue.setStoryPoints(STORY_POINTS);
        existingIssue.setStartDate(START_DATE);
        existingIssue.setDueDate(DUE_DATE);
        existingIssue.setOriginalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES);
        existingIssue.setRemainingEstimateMinutes(REMAINING_ESTIMATE_MINUTES);

        when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        when(projectRoleChecker.checkProjectRole(
                eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(ACTOR_USER_ID), eq(updateRoles)))
                .thenReturn(Mono.empty());
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        Mockito.lenient().when(outboxEventService.saveOutboxEvent(any(), any(), any(AggregateType.class),
                                                                  any(UUID.class), any(EventType.class), any(JsonNode.class)))
               .thenReturn(Mono.empty());
        Mockito.lenient().when(issueHistoryService.saveIssueHistory(any(), any(), any(UUID.class),
                                                                    any(), any(), any(JsonNode.class)))
               .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                            REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                            "Summary", "Description", IssuePriority.MEDIUM,
                            null, null, null, null, null))
                    .assertNext(issue -> {
                        Assertions.assertThat(issue.getStoryPoints()).isNull();
                        Assertions.assertThat(issue.getStartDate()).isNull();
                        Assertions.assertThat(issue.getDueDate()).isNull();
                        Assertions.assertThat(issue.getOriginalEstimateMinutes()).isNull();
                        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isNull();
                    })
                    .verifyComplete();
    }

    @DisplayName("Обновление с невалидным диапазоном дат -> INVALID_ARGUMENT, save не вызывается")
    @Test
    void updateIssue_rejectsInvalidDateRange() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary("Summary");
        existingIssue.setDescription("Description");
        existingIssue.setPriority(IssuePriority.MEDIUM);
        existingIssue.setVersion(1);
        existingIssue.setStartDate(START_DATE);
        existingIssue.setDueDate(DUE_DATE);

        when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        when(projectRoleChecker.checkProjectRole(
                eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(ACTOR_USER_ID), eq(updateRoles)))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                            REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                            "Summary", "Description", IssuePriority.MEDIUM,
                            EMPTY_STORY_POINTS,
                            INVALID_START_DATE_AFTER_DUE, EMPTY_DUE_DATE,
                            EMPTY_ORIGINAL_ESTIMATE_MINUTES, EMPTY_REMAINING_ESTIMATE_MINUTES))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                        Assertions.assertThat(((DomainException) throwable).getStatus()).isEqualTo(DomainStatus.INVALID_ARGUMENT);
                    })
                    .verify();

        verify(issueRepository, never()).save(any());
    }

    // ==================== История содержит изменения planning fields ====================

    @DisplayName("Payload истории содержит old/new значения planning fields при их изменении")
    @Test
    void updateIssue_historyPayloadContainsPlanningFieldChanges() {
        Issue existingIssue = new Issue();
        existingIssue.setId(ISSUE_ID);
        existingIssue.setProjectId(PROJECT_ID);
        existingIssue.setSummary("Summary");
        existingIssue.setDescription("Description");
        existingIssue.setPriority(IssuePriority.MEDIUM);
        existingIssue.setVersion(1);
        existingIssue.setStoryPoints(STORY_POINTS);

        when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(existingIssue));
        when(projectRoleChecker.checkProjectRole(
                eq(REQUEST_ID), eq(NODE_ID), eq(PROJECT_ID), eq(ACTOR_USER_ID), eq(updateRoles)))
                .thenReturn(Mono.empty());
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(outboxEventService.saveOutboxEvent(any(), any(), any(AggregateType.class),
                                                any(UUID.class), any(EventType.class), any(JsonNode.class)))
                .thenReturn(Mono.empty());
        when(issueHistoryService.saveIssueHistory(any(), any(), any(UUID.class),
                                                  any(), any(), any(JsonNode.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(issueService.updateIssue(
                            REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID,
                            "Summary", "Description", IssuePriority.MEDIUM,
                            ANOTHER_STORY_POINTS, EMPTY_START_DATE, EMPTY_DUE_DATE,
                            EMPTY_ORIGINAL_ESTIMATE_MINUTES, EMPTY_REMAINING_ESTIMATE_MINUTES))
                    .assertNext(issue -> Assertions.assertThat(issue).isNotNull())
                    .verifyComplete();

        org.mockito.ArgumentCaptor<JsonNode> payloadCaptor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(issueHistoryService).saveIssueHistory(any(), any(), any(UUID.class), any(), any(), payloadCaptor.capture());

        JsonNode payload = payloadCaptor.getValue();
        Assertions.assertThat(payload.has("oldStoryPoints")).isTrue();
        Assertions.assertThat(payload.has("newStoryPoints")).isTrue();
        Assertions.assertThat(payload.get("newStoryPoints").asDouble()).isEqualTo(ANOTHER_STORY_POINTS.doubleValue());
    }
}