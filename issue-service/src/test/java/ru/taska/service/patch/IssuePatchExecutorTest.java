package ru.taska.service.patch;

import nullable.NullableField;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssueHistory;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.OutboxEvent;
import ru.taska.domain.PatchIssueResult;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.IssueHistoryService;
import ru.taska.service.OutboxEventService;
import ru.taska.service.watcher.IssueAutoWatchService;
import ru.taska.util.PayloadSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class IssuePatchExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private IssueWatcherRepository issueWatcherRepository;

    @Spy
    private PayloadSerializer payloadSerializer = new PayloadSerializer(OBJECT_MAPPER);

    @Mock
    private IssueHistoryService issueHistoryService;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private IssueAutoWatchService issueAutoWatchService;

    @InjectMocks
    private IssuePatchExecutor executor;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID CURRENT_ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID NEW_ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final List<UUID> WATCHER_IDS = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-000000000008"));
    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "issue-service";
    private static final int VERSION = 3;
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final String SUMMARY = "summary";
    private static final String DESCRIPTION = "description";
    private static final BigDecimal STORY_POINTS = new BigDecimal("3.00");
    private static final LocalDate START_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 10);
    private static final int ORIGINAL_ESTIMATE_MINUTES = 120;

    private static final String SAVE = "save";
    private static final String OUTBOX_UPDATED = "outbox " + EventType.ISSUE_UPDATED;
    private static final String HISTORY_UPDATED = "history " + IssueEventType.UPDATED;
    private static final String HISTORY_ASSIGNED = "history " + IssueEventType.ASSIGNED;
    private static final String OUTBOX_ASSIGNED = "outbox " + EventType.ISSUE_ASSIGNED;
    private static final String WATCH_ASSIGNEE = "watchAssigneeOnAssign";

    /**
     * Записи в порядке подписки на них. Методы сервисов в цепочке вызываются заранее,
     * а запись происходит только при подписке, поэтому порядок и факт записи проверяются по этому списку.
     */
    private final List<String> writes = new ArrayList<>();

    /**
     * Payload, переданный в каждую запись истории и outbox.
     */
    private final Map<String, JsonNode> payloads = new HashMap<>();

    @Test
    @DisplayName("Должен выбросить NOT_FOUND и ничего не писать, если задача не найдена под блокировкой")
    void executePatch_shouldThrowNotFound_whenIssueNotFound() {
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(executePatch(patch().summary("new summary").build()))
                .expectErrorSatisfies(error -> {
                    Assertions.assertThat(error).isInstanceOf(DomainException.class);
                    Assertions.assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                })
                .verify();

        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(issueWatcherRepository, outboxEventService, issueHistoryService,
                issueAutoWatchService);
    }

    @Test
    @DisplayName("При изменении версии после проверок должен вернуть задачу под блокировкой с versionConflict=true без изменений")
    void executePatch_shouldReturnVersionConflict_whenLockedVersionDiffers() {
        var lockedIssue = lockedIssue().version(VERSION + 1).build();
        var lockedSnapshot = lockedIssue.toBuilder().build();
        stubLockedIssue(lockedIssue);

        StepVerifier.create(executePatch(patch().summary("new summary").assigneeId(NEW_ASSIGNEE_ID).build()))
                .assertNext(result -> {
                    Assertions.assertThat(result.versionConflict()).isTrue();
                    Assertions.assertThat(result.issue()).isSameAs(lockedIssue);
                })
                .verifyComplete();

        Assertions.assertThat(lockedIssue).isEqualTo(lockedSnapshot);
        Mockito.verify(issueRepository).findActiveByIdForUpdate(ISSUE_ID);
        Mockito.verifyNoMoreInteractions(issueRepository);
        Mockito.verifyNoInteractions(issueWatcherRepository, payloadSerializer, outboxEventService,
                issueHistoryService, issueAutoWatchService);
    }

    static Stream<Arguments> noOpPatches() {
        return Stream.of(
                Arguments.argumentSet("пустой патч", patch().build()),
                Arguments.argumentSet("те же значения, что уже в задаче, включая исполнителя", patch()
                        .summary(SUMMARY)
                        .priority(IssuePriority.MEDIUM)
                        .description(DESCRIPTION)
                        .assigneeId(CURRENT_ASSIGNEE_ID)
                        .storyPoints(new BigDecimal("3"))
                        .startDate(START_DATE)
                        .dueDate(DUE_DATE)
                        .originalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES)
                        .build()),
                Arguments.argumentSet("очистка поля, которое и так null", patch().remainingEstimateMinutes(null).build())
        );
    }

    @ParameterizedTest
    @MethodSource("noOpPatches")
    @DisplayName("Патч без фактических изменений должен вернуть исходную задачу без сохранения и событий")
    void executePatch_shouldNotSave_whenPatchChangesNothing(IssuePatch patch) {
        var lockedIssue = lockedIssue().build();
        stubLockedIssue(lockedIssue);
        stubWatchers();

        StepVerifier.create(executePatch(patch))
                .assertNext(result -> {
                    Assertions.assertThat(result.versionConflict()).isFalse();
                    Assertions.assertThat(result.issue()).isSameAs(lockedIssue);
                    Assertions.assertThat(result.issue().getVersion()).isEqualTo(VERSION);
                    Assertions.assertThat(result.issue().getUpdatedAt()).isEqualTo(UPDATED_AT);
                })
                .verifyComplete();

        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any(Issue.class));
        Mockito.verifyNoInteractions(outboxEventService, issueHistoryService, issueAutoWatchService);
    }

    @Test
    @DisplayName("Изменение всех полей без смены исполнителя: патч применяется к строке под блокировкой, пишется только UPDATED")
    void executePatch_shouldSaveAndWriteUpdated_whenFieldsChangedWithoutAssignee() {
        var lockedIssue = lockedIssue().build();
        stubLockedIssue(lockedIssue);
        stubWatchers();
        stubSave();
        stubOutbox(EventType.ISSUE_UPDATED);
        stubHistory(IssueEventType.UPDATED);

        var newStartDate = START_DATE.plusDays(1);
        var newDueDate = DUE_DATE.plusDays(1);
        var patch = patch()
                .summary("new summary")
                .priority(IssuePriority.HIGH)
                .description("new description")
                .storyPoints(new BigDecimal("5.00"))
                .startDate(newStartDate)
                .dueDate(newDueDate)
                .originalEstimateMinutes(240)
                .remainingEstimateMinutes(60)
                .build();

        StepVerifier.create(executePatch(patch))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isFalse())
                .verifyComplete();

        var savedIssue = captureSavedIssue();
        assertSavedIssue(savedIssue, lockedIssue.toBuilder()
                .summary("new summary")
                .priority(IssuePriority.HIGH)
                .description("new description")
                .storyPoints(new BigDecimal("5.00"))
                .startDate(newStartDate)
                .dueDate(newDueDate)
                .originalEstimateMinutes(240)
                .remainingEstimateMinutes(60)
                .version(VERSION + 1)
                .build());
        Mockito.verify(payloadSerializer).createIssueUpdatedPayload(
                lockedIssue, ACTOR_USER_ID, CURRENT_ASSIGNEE_ID,
                "new summary", "new description", IssuePriority.HIGH,
                new BigDecimal("5.00"), newStartDate, newDueDate, 240, 60,
                WATCHER_IDS);

        Assertions.assertThat(writes).containsExactly(SAVE, OUTBOX_UPDATED, HISTORY_UPDATED);
        Assertions.assertThat(payloads.get(OUTBOX_UPDATED)).isNotEmpty().isSameAs(payloads.get(HISTORY_UPDATED));
        Mockito.verify(payloadSerializer, Mockito.never()).createIssueAssignedPayload(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyList());
        Mockito.verifyNoInteractions(issueAutoWatchService);
    }

    @Test
    @DisplayName("Очистка поля со значением должна сохранить null, увеличить версию и записать UPDATED")
    void executePatch_shouldSaveNull_whenFieldCleared() {
        var lockedIssue = lockedIssue().build();
        stubLockedIssue(lockedIssue);
        stubWatchers();
        stubSave();
        stubOutbox(EventType.ISSUE_UPDATED);
        stubHistory(IssueEventType.UPDATED);

        StepVerifier.create(executePatch(patch().description(null).build()))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isFalse())
                .verifyComplete();

        assertSavedIssue(captureSavedIssue(), lockedIssue.toBuilder()
                .description(null)
                .version(VERSION + 1)
                .build());
        Assertions.assertThat(writes).containsExactly(SAVE, OUTBOX_UPDATED, HISTORY_UPDATED);
        Assertions.assertThat(payloads.get(HISTORY_UPDATED).get("newDescription").isNull()).isTrue();
    }

    static Stream<Arguments> assigneeChanges() {
        return Stream.of(
                Arguments.argumentSet("смена исполнителя", CURRENT_ASSIGNEE_ID, NEW_ASSIGNEE_ID),
                Arguments.argumentSet("снятие исполнителя", CURRENT_ASSIGNEE_ID, null),
                Arguments.argumentSet("назначение задаче без исполнителя", null, NEW_ASSIGNEE_ID)
        );
    }

    @ParameterizedTest
    @MethodSource("assigneeChanges")
    @DisplayName("Смена только исполнителя должна сохранить задачу и записать ASSIGNED с автоподпиской без UPDATED")
    void executePatch_shouldWriteAssignedOnly_whenOnlyAssigneeChanged(UUID previousAssigneeId, UUID newAssigneeId) {
        var lockedIssue = lockedIssue().assigneeId(previousAssigneeId).build();
        stubLockedIssue(lockedIssue);
        stubWatchers();
        stubSave();
        stubHistory(IssueEventType.ASSIGNED);
        stubOutbox(EventType.ISSUE_ASSIGNED);
        stubWatchAssignee(Mono.empty());

        StepVerifier.create(executePatch(patch().assigneeId(newAssigneeId).build()))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isFalse())
                .verifyComplete();

        var savedIssue = captureSavedIssue();
        assertSavedIssue(savedIssue, lockedIssue.toBuilder()
                .assigneeId(newAssigneeId)
                .version(VERSION + 1)
                .build());

        Assertions.assertThat(writes).containsExactly(SAVE, HISTORY_ASSIGNED, OUTBOX_ASSIGNED, WATCH_ASSIGNEE);
        Mockito.verify(payloadSerializer).createIssueAssignedPayload(
                previousAssigneeId, newAssigneeId, ACTOR_USER_ID, WATCHER_IDS);
        Assertions.assertThat(payloads.get(HISTORY_ASSIGNED)).isSameAs(payloads.get(OUTBOX_ASSIGNED));
        Mockito.verify(issueAutoWatchService).watchAssigneeOnAssign(REQUEST_ID, NODE_ID, savedIssue, ACTOR_USER_ID);
    }

    @Test
    @DisplayName("Смена полей и исполнителя: один save, сначала UPDATED, затем ASSIGNED и автоподписка; наблюдатели в обоих payload")
    void executePatch_shouldWriteUpdatedThenAssigned_whenFieldsAndAssigneeChanged() {
        stubLockedIssue(lockedIssue().build());
        stubWatchers();
        stubSave();
        stubOutbox(EventType.ISSUE_UPDATED);
        stubHistory(IssueEventType.UPDATED);
        stubHistory(IssueEventType.ASSIGNED);
        stubOutbox(EventType.ISSUE_ASSIGNED);
        stubWatchAssignee(Mono.empty());

        StepVerifier.create(executePatch(patch().summary("new summary").assigneeId(NEW_ASSIGNEE_ID).build()))
                .assertNext(result -> Assertions.assertThat(result.versionConflict()).isFalse())
                .verifyComplete();

        Mockito.verify(issueRepository).save(Mockito.any(Issue.class));
        Assertions.assertThat(writes).containsExactly(
                SAVE, OUTBOX_UPDATED, HISTORY_UPDATED, HISTORY_ASSIGNED, OUTBOX_ASSIGNED, WATCH_ASSIGNEE);

        JsonNode expectedWatcherIds = OBJECT_MAPPER.valueToTree(WATCHER_IDS);
        var updatedPayload = payloads.get(HISTORY_UPDATED);
        Assertions.assertThat(updatedPayload.get("assigneeId").asString()).isEqualTo(NEW_ASSIGNEE_ID.toString());
        Assertions.assertThat(updatedPayload.get("watcherIds")).isEqualTo(expectedWatcherIds);
        Assertions.assertThat(payloads.get(HISTORY_ASSIGNED).get("watcherIds")).isEqualTo(expectedWatcherIds);
    }

    @Test
    @DisplayName("Ошибка чтения наблюдателей должна пробрасываться без сохранения и событий")
    void executePatch_shouldPropagateError_whenWatchersLookupFails() {
        var expectedException = new RuntimeException("watchers lookup failed");
        stubLockedIssue(lockedIssue().build());
        Mockito.when(issueWatcherRepository.findUserIdsByIssueId(ISSUE_ID)).thenReturn(Flux.error(expectedException));

        StepVerifier.create(executePatch(patch().summary("new summary").build()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Mockito.verify(issueRepository, Mockito.never()).save(Mockito.any(Issue.class));
        Mockito.verifyNoInteractions(outboxEventService, issueHistoryService, issueAutoWatchService);
    }

    @Test
    @DisplayName("Ошибка save должна пробрасываться без событий и автоподписки")
    void executePatch_shouldPropagateError_whenSaveFails() {
        var expectedException = new RuntimeException("save failed");
        stubLockedIssue(lockedIssue().build());
        stubWatchers();
        Mockito.when(issueRepository.save(Mockito.any(Issue.class))).thenReturn(Mono.error(expectedException));

        StepVerifier.create(executePatch(patch().summary("new summary").assigneeId(NEW_ASSIGNEE_ID).build()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Mockito.verifyNoInteractions(outboxEventService, issueHistoryService, issueAutoWatchService);
    }

    @Test
    @DisplayName("Ошибка outbox UPDATED должна пробрасываться без записи истории и ASSIGNED")
    void executePatch_shouldPropagateError_whenUpdatedOutboxFails() {
        var expectedException = new RuntimeException("outbox failed");
        stubLockedIssue(lockedIssue().build());
        stubWatchers();
        stubSave();
        stubOutbox(EventType.ISSUE_UPDATED, Mono.error(expectedException));
        stubHistory(IssueEventType.UPDATED);

        StepVerifier.create(executePatch(patch().summary("new summary").assigneeId(NEW_ASSIGNEE_ID).build()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Assertions.assertThat(writes).containsExactly(SAVE, OUTBOX_UPDATED);
        Mockito.verify(payloadSerializer, Mockito.never()).createIssueAssignedPayload(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyList());
        Mockito.verifyNoInteractions(issueAutoWatchService);
    }

    @Test
    @DisplayName("Ошибка истории ASSIGNED должна пробрасываться без outbox ASSIGNED и автоподписки")
    void executePatch_shouldPropagateError_whenAssignedHistoryFails() {
        var expectedException = new RuntimeException("history failed");
        stubLockedIssue(lockedIssue().build());
        stubWatchers();
        stubSave();
        stubHistory(IssueEventType.ASSIGNED, Mono.error(expectedException));
        stubOutbox(EventType.ISSUE_ASSIGNED);
        stubWatchAssignee(Mono.empty());

        StepVerifier.create(executePatch(patch().assigneeId(NEW_ASSIGNEE_ID).build()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Assertions.assertThat(writes).containsExactly(SAVE, HISTORY_ASSIGNED);
    }

    @Test
    @DisplayName("Ошибка автоподписки исполнителя должна пробрасываться")
    void executePatch_shouldPropagateError_whenWatchAssigneeFails() {
        var expectedException = new RuntimeException("auto watch failed");
        stubLockedIssue(lockedIssue().build());
        stubWatchers();
        stubSave();
        stubHistory(IssueEventType.ASSIGNED);
        stubOutbox(EventType.ISSUE_ASSIGNED);
        stubWatchAssignee(Mono.error(expectedException));

        StepVerifier.create(executePatch(patch().assigneeId(NEW_ASSIGNEE_ID).build()))
                .expectErrorSatisfies(error -> Assertions.assertThat(error).isSameAs(expectedException))
                .verify();

        Assertions.assertThat(writes).containsExactly(SAVE, HISTORY_ASSIGNED, OUTBOX_ASSIGNED, WATCH_ASSIGNEE);
    }

    private Mono<PatchIssueResult> executePatch(IssuePatch patch) {
        return executor.executePatch(REQUEST_ID, NODE_ID, ISSUE_ID, ACTOR_USER_ID, VERSION, patch);
    }

    private void stubLockedIssue(Issue issue) {
        Mockito.when(issueRepository.findActiveByIdForUpdate(ISSUE_ID)).thenReturn(Mono.just(issue));
    }

    private void stubWatchers() {
        Mockito.when(issueWatcherRepository.findUserIdsByIssueId(ISSUE_ID)).thenReturn(Flux.fromIterable(WATCHER_IDS));
    }

    private void stubSave() {
        Mockito.when(issueRepository.save(Mockito.any(Issue.class)))
                .thenAnswer(invocation -> recordWrite(SAVE, Mono.just(invocation.<Issue>getArgument(0))));
    }

    private void stubOutbox(EventType type) {
        stubOutbox(type, Mono.empty());
    }

    // lenient: какие из методов цепочки будут вызваны заранее, зависит от реализации, а не от проверяемого поведения
    private void stubOutbox(EventType type, Mono<OutboxEvent> result) {
        var write = "outbox " + type;
        Mockito.lenient().when(outboxEventService.saveOutboxEvent(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(AggregateType.ISSUE),
                        Mockito.eq(ISSUE_ID), Mockito.eq(type), Mockito.any(JsonNode.class)))
                .thenAnswer(invocation -> {
                    payloads.put(write, invocation.getArgument(5));
                    return recordWrite(write, result);
                });
    }

    private void stubHistory(IssueEventType type) {
        stubHistory(type, Mono.empty());
    }

    private void stubHistory(IssueEventType type, Mono<IssueHistory> result) {
        var write = "history " + type;
        Mockito.lenient().when(issueHistoryService.saveIssueHistory(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.eq(ISSUE_ID),
                        Mockito.eq(ACTOR_USER_ID), Mockito.eq(type), Mockito.any(JsonNode.class)))
                .thenAnswer(invocation -> {
                    payloads.put(write, invocation.getArgument(5));
                    return recordWrite(write, result);
                });
    }

    private void stubWatchAssignee(Mono<Void> result) {
        Mockito.lenient().when(issueAutoWatchService.watchAssigneeOnAssign(
                        Mockito.eq(REQUEST_ID), Mockito.eq(NODE_ID), Mockito.any(Issue.class), Mockito.eq(ACTOR_USER_ID)))
                .thenReturn(recordWrite(WATCH_ASSIGNEE, result));
    }

    private <T> Mono<T> recordWrite(String write, Mono<T> result) {
        return Mono.defer(() -> {
            writes.add(write);
            return result;
        });
    }

    private Issue captureSavedIssue() {
        var captor = ArgumentCaptor.forClass(Issue.class);
        Mockito.verify(issueRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * Сохранённая задача должна совпадать с ожидаемой во всех полях, кроме {@code updatedAt},
     * а {@code updatedAt} — быть новее прежнего.
     */
    private static void assertSavedIssue(Issue savedIssue, Issue expected) {
        Assertions.assertThat(savedIssue).usingRecursiveComparison().ignoringFields("updatedAt").isEqualTo(expected);
        Assertions.assertThat(savedIssue.getUpdatedAt()).isAfter(UPDATED_AT);
    }

    private static Issue.IssueBuilder lockedIssue() {
        return Issue.builder()
                .id(ISSUE_ID)
                .projectId(PROJECT_ID)
                .issueKey("TAS-1")
                .statusKey("TODO")
                .reporterId(REPORTER_ID)
                .summary(SUMMARY)
                .description(DESCRIPTION)
                .priority(IssuePriority.MEDIUM)
                .assigneeId(CURRENT_ASSIGNEE_ID)
                .storyPoints(STORY_POINTS)
                .startDate(START_DATE)
                .dueDate(DUE_DATE)
                .originalEstimateMinutes(ORIGINAL_ESTIMATE_MINUTES)
                .remainingEstimateMinutes(null)
                .createdAt(CREATED_AT)
                .updatedAt(UPDATED_AT)
                .version(VERSION);
    }

    private static PatchBuilder patch() {
        return new PatchBuilder();
    }

    /**
     * Собирает {@link IssuePatch}, в котором не заданные явно поля не меняются.
     */
    private static final class PatchBuilder {
        private Optional<String> summary = Optional.empty();
        private Optional<IssuePriority> priority = Optional.empty();
        private NullableField<String> description = NullableField.absent();
        private NullableField<UUID> assigneeId = NullableField.absent();
        private NullableField<BigDecimal> storyPoints = NullableField.absent();
        private NullableField<LocalDate> startDate = NullableField.absent();
        private NullableField<LocalDate> dueDate = NullableField.absent();
        private NullableField<Integer> originalEstimateMinutes = NullableField.absent();
        private NullableField<Integer> remainingEstimateMinutes = NullableField.absent();

        PatchBuilder summary(String value) {
            summary = Optional.of(value);
            return this;
        }

        PatchBuilder priority(IssuePriority value) {
            priority = Optional.of(value);
            return this;
        }

        PatchBuilder description(String value) {
            description = NullableField.of(value);
            return this;
        }

        PatchBuilder assigneeId(UUID value) {
            assigneeId = NullableField.of(value);
            return this;
        }

        PatchBuilder storyPoints(BigDecimal value) {
            storyPoints = NullableField.of(value);
            return this;
        }

        PatchBuilder startDate(LocalDate value) {
            startDate = NullableField.of(value);
            return this;
        }

        PatchBuilder dueDate(LocalDate value) {
            dueDate = NullableField.of(value);
            return this;
        }

        PatchBuilder originalEstimateMinutes(Integer value) {
            originalEstimateMinutes = NullableField.of(value);
            return this;
        }

        PatchBuilder remainingEstimateMinutes(Integer value) {
            remainingEstimateMinutes = NullableField.of(value);
            return this;
        }

        IssuePatch build() {
            return new IssuePatch(summary, priority, description, assigneeId, storyPoints,
                    startDate, dueDate, originalEstimateMinutes, remainingEstimateMinutes);
        }
    }
}
