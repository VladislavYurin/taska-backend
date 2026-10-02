package ru.taska.service.worklog;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorklogExecutor Unit Tests")
class WorklogExecutorTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private WorklogRepository worklogRepository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private PayloadSerializer payloadSerializer;

    private WorklogExecutor worklogExecutor;

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKLOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

    private JsonNode mockPayload;

    @BeforeEach
    void setUp() {
        worklogExecutor = new WorklogExecutor(
                issueRepository, issueHistoryService, worklogRepository, outboxEventService, payloadSerializer);
        mockPayload = Mockito.mock(JsonNode.class);
    }

    private Issue baseIssue(Integer timeSpent, Integer remainingEstimate) {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .version(1)
                .timeSpentMinutes(timeSpent)
                .remainingEstimateMinutes(remainingEstimate)
                .build();
    }

    // ===== executeAdd =====

    @Test
    @DisplayName("executeAdd: должен увеличить timeSpentMinutes на spentMinutes")
    void shouldIncreaseTimeSpentMinutesOnAdd() {
        Issue issue = baseIssue(60, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), "comment");

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getTimeSpentMinutes()).isEqualTo(90);
    }

    @Test
    @DisplayName("executeAdd: должен уменьшить remainingEstimateMinutes, но не ниже 0")
    void shouldDecreaseRemainingEstimateNotBelowZeroOnAdd() {
        Issue issue = baseIssue(0, 20);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(0);
    }

    @Test
    @DisplayName("executeAdd: не должен трогать remainingEstimateMinutes если оно null")
    void shouldNotTouchRemainingEstimateWhenNullOnAdd() {
        Issue issue = baseIssue(0, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isNull();
    }

    @Test
    @DisplayName("executeAdd: должен записать историю WORKLOG_ADDED и outbox-событие")
    void shouldSaveHistoryAndOutboxOnAdd() {
        Issue issue = baseIssue(0, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Mockito.verify(issueHistoryService).saveIssueHistory(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID), Mockito.eq(ACTOR_ID),
                Mockito.eq(IssueEventType.WORKLOG_ADDED), Mockito.eq(mockPayload));
        Mockito.verify(outboxEventService).saveOutboxEvent(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(AggregateType.ISSUE),
                Mockito.eq(ACTOR_ID), Mockito.eq(EventType.WORKLOG_ADDED), Mockito.eq(mockPayload));
    }

    @Test
    @DisplayName("executeAdd: должен пропагировать ошибку при сбое записи истории")
    void shouldPropagateErrorOnHistorySaveFailureOnAdd() {
        Issue issue = baseIssue(0, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new RuntimeException("history save failed")));
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectErrorMessage("history save failed")
                .verify();
    }

    @Test
    @DisplayName("executeAdd: должен пропагировать ошибку при сбое записи outbox")
    void shouldPropagateErrorOnOutboxSaveFailureOnAdd() {
        Issue issue = baseIssue(0, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new RuntimeException("outbox save failed")));

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectErrorMessage("outbox save failed")
                .verify();
    }

    @Test
    @DisplayName("executeAdd: регрессия — сохраняемый worklog должен иметь непустую версию (баг NPE в мапере)")
    void shouldPersistNonNullVersionToPreventMapperNpe() {
        Issue issue = baseIssue(0, null);
        CreateWorklogDto dto = new CreateWorklogDto(30, LocalDate.now(), null);
        ArgumentCaptor<Worklog> worklogCaptor = ArgumentCaptor.forClass(Worklog.class);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(worklogCaptor.capture()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogAddedPayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeAdd(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        // NB: до фикса баги NPE (WorklogMapper.toWorklogProto) это поле было null,
        // т.к. version — DB-side DEFAULT, который Spring Data R2DBC не подставляет обратно в объект.
        Assertions.assertThat(worklogCaptor.getValue().getVersion()).isNotNull();
    }

    // ===== executeUpdate =====

    @Test
    @DisplayName("executeUpdate: должен пересчитать timeSpentMinutes при увеличении spentMinutes")
    void shouldRecalculateTimeSpentOnIncreaseOnUpdate() {
        Issue issue = baseIssue(100, 50);
        Worklog worklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        UpdateWorklogDto dto = new UpdateWorklogDto(50, null, null); // +20

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogUpdatePayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getTimeSpentMinutes()).isEqualTo(120);
        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("executeUpdate: должен пересчитать timeSpentMinutes при уменьшении spentMinutes")
    void shouldRecalculateTimeSpentOnDecreaseOnUpdate() {
        Issue issue = baseIssue(100, 10);
        Worklog worklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(50).workDate(LocalDate.now()).version(1)
                .build();
        UpdateWorklogDto dto = new UpdateWorklogDto(20, null, null); // -30

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogUpdatePayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getTimeSpentMinutes()).isEqualTo(70);
        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(40);
    }

    @Test
    @DisplayName("executeUpdate: не должен уводить timeSpentMinutes ниже 0")
    void shouldClampTimeSpentAtZeroOnUpdate() {
        Issue issue = baseIssue(5, null);
        Worklog worklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(100).workDate(LocalDate.now()).version(1)
                .build();
        UpdateWorklogDto dto = new UpdateWorklogDto(10, null, null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogUpdatePayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getTimeSpentMinutes()).isZero();
    }

    @Test
    @DisplayName("executeUpdate: должен выбросить NOT_FOUND если worklog не принадлежит issue")
    void shouldThrowNotFoundWhenWorklogNotBelongsToIssueOnUpdate() {
        Issue issue = baseIssue(100, 50);
        Worklog foreignWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(OTHER_ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        UpdateWorklogDto dto = new UpdateWorklogDto(50, null, null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.just(foreignWorklog));

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue doesnt belong to worklog");
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any(Issue.class));
        Mockito.verify(worklogRepository, Mockito.never()).save(Mockito.any(Worklog.class));
    }

    @Test
    @DisplayName("executeUpdate: должен выбросить NOT_FOUND если worklog не найден")
    void shouldThrowNotFoundWhenWorklogMissingOnUpdate() {
        Issue issue = baseIssue(100, 50);
        UpdateWorklogDto dto = new UpdateWorklogDto(50, null, null);

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();
    }

    @Test
    @DisplayName("executeUpdate: должен записать историю WORKLOG_UPDATED и outbox-событие")
    void shouldSaveHistoryAndOutboxOnUpdate() {
        Issue issue = baseIssue(100, 50);
        Worklog worklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        UpdateWorklogDto dto = new UpdateWorklogDto(null, null, "updated comment");

        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.findActiveByIdForUpdate(WORKLOG_ID)).thenReturn(Mono.just(worklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.save(Mockito.any(Worklog.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        Mockito.when(payloadSerializer.createWorklogUpdatePayload(Mockito.any())).thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeUpdate(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID, dto))
                .expectNextCount(1)
                .verifyComplete();

        Mockito.verify(issueHistoryService).saveIssueHistory(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID), Mockito.eq(ACTOR_ID),
                Mockito.eq(IssueEventType.WORKLOG_UPDATED), Mockito.eq(mockPayload));
        Mockito.verify(outboxEventService).saveOutboxEvent(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(AggregateType.ISSUE),
                Mockito.eq(ACTOR_ID), Mockito.eq(EventType.WORKLOG_UPDATED), Mockito.eq(mockPayload));
    }

    // ===== executeDelete =====

    @Test
    @DisplayName("executeDelete: должен вернуть spentMinutes в remainingEstimateMinutes")
    void shouldReturnSpentMinutesToRemainingEstimateOnDelete() {
        Issue issue = baseIssue(50, 50);
        Worklog activeWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        Worklog deletedWorklog = activeWorklog.toBuilder().deletedAt(Instant.now()).version(2).build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(activeWorklog));
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.softDelete(WORKLOG_ID)).thenReturn(Mono.just(deletedWorklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(payloadSerializer.createWorklogDeletedPayload(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getRemainingEstimateMinutes()).isEqualTo(80);
    }

    @Test
    @DisplayName("executeDelete: должен уменьшить timeSpentMinutes, но не ниже 0")
    void shouldDecreaseTimeSpentNotBelowZeroOnDelete() {
        Issue issue = baseIssue(20, null);
        Worklog activeWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        Worklog deletedWorklog = activeWorklog.toBuilder().deletedAt(Instant.now()).version(2).build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(activeWorklog));
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.softDelete(WORKLOG_ID)).thenReturn(Mono.just(deletedWorklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(payloadSerializer.createWorklogDeletedPayload(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID))
                .expectNextCount(1)
                .verifyComplete();

        Assertions.assertThat(issue.getTimeSpentMinutes()).isEqualTo(0);
    }

    @Test
    @DisplayName("executeDelete: должен выполнить soft-delete (worklog не пропадает физически)")
    void shouldSoftDeleteWorklog() {
        Issue issue = baseIssue(50, null);
        Worklog activeWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        Worklog deletedWorklog = activeWorklog.toBuilder().deletedAt(Instant.now()).version(2).build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(activeWorklog));
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.softDelete(WORKLOG_ID)).thenReturn(Mono.just(deletedWorklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(payloadSerializer.createWorklogDeletedPayload(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID))
                .assertNext(result -> Assertions.assertThat(result.getDeletedAt()).isNotNull())
                .verifyComplete();

        Mockito.verify(worklogRepository).softDelete(WORKLOG_ID);
        Mockito.verify(worklogRepository, Mockito.never()).deleteById(Mockito.any(UUID.class));
    }

    @Test
    @DisplayName("executeDelete: должен выбросить NOT_FOUND если worklog не принадлежит issue")
    void shouldThrowNotFoundWhenWorklogNotBelongsToIssueOnDelete() {
        Worklog foreignWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(OTHER_ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(foreignWorklog));

        StepVerifier.create(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    DomainException ex = (DomainException) error;
                    Assertions.assertThat(ex.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    Assertions.assertThat(ex.getMessage()).contains("Issue doesnt belong to worklog");
                })
                .verify();

        Mockito.verify(issueRepository, Mockito.never()).findActiveByIdForUpdate(Mockito.any());
        Mockito.verify(worklogRepository, Mockito.never()).softDelete(Mockito.any());
    }

    @Test
    @DisplayName("executeDelete: должен записать историю WORKLOG_DELETED и outbox-событие")
    void shouldSaveHistoryAndOutboxOnDelete() {
        Issue issue = baseIssue(50, null);
        Worklog activeWorklog = Worklog.builder()
                .id(WORKLOG_ID).issueId(ISSUE_ID).projectId(PROJECT_ID)
                .spentMinutes(30).workDate(LocalDate.now()).version(1)
                .build();
        Worklog deletedWorklog = activeWorklog.toBuilder().deletedAt(Instant.now()).version(2).build();

        Mockito.when(worklogRepository.findActiveById(WORKLOG_ID)).thenReturn(Mono.just(activeWorklog));
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
        Mockito.when(worklogRepository.softDelete(WORKLOG_ID)).thenReturn(Mono.just(deletedWorklog));
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.just(issue));
        Mockito.when(payloadSerializer.createWorklogDeletedPayload(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(mockPayload);
        Mockito.when(issueHistoryService.saveIssueHistory(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        Mockito.when(outboxEventService.saveOutboxEvent(
                        Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(worklogExecutor.executeDelete(REQUEST_ID, NODE_ID, ISSUE_ID, WORKLOG_ID, ACTOR_ID))
                .expectNextCount(1)
                .verifyComplete();

        Mockito.verify(issueHistoryService).saveIssueHistory(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID), Mockito.eq(ACTOR_ID),
                Mockito.eq(IssueEventType.WORKLOG_DELETED), Mockito.eq(mockPayload));
        Mockito.verify(outboxEventService).saveOutboxEvent(
                Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(AggregateType.ISSUE),
                Mockito.eq(ACTOR_ID), Mockito.eq(EventType.WORKLOG_DELETED), Mockito.eq(mockPayload));
    }
}