package ru.taska.integration;

import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import nullable.NullableField;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.CheckProjectMemberRoleRequest;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.ProjectKeyResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssuePatch;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.PatchIssueResult;
import ru.taska.event.EventType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.IssueWatcherRepository;
import ru.taska.service.IssueService;
import ru.taska.service.patch.IssuePatchService;

class IssuePatchIT extends AbstractIT {

    @MockitoBean
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub projectServiceStub;

    @Autowired
    private IssueService issueService;

    @Autowired
    private IssuePatchService issuePatchService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueWatcherRepository issueWatcherRepository;

    @Autowired
    private DatabaseClient databaseClient;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REPORTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_USER_ID = REPORTER_ID;
    private static final UUID NEW_ASSIGNEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String REQUEST_ID = "req-patch-001";
    private static final String NODE_ID = "issue-service";

    private static final String SUMMARY = "Задача";
    private static final String DESCRIPTION = "Описание";
    private static final BigDecimal STORY_POINTS = new BigDecimal("5");
    private static final LocalDate START_DATE = LocalDate.of(2026, 9, 1);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 9, 10);
    private static final Integer ORIGINAL_ESTIMATE_MINUTES = 480;
    private static final Integer REMAINING_ESTIMATE_MINUTES = 240;

    private static final String FAILURE_FUNCTION_SQL = """
            CREATE OR REPLACE FUNCTION taska.it_simulate_insert_failure() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'simulated insert failure';
            END;
            $$
            """;
    private static final String FAILURE_TRIGGER = "it_simulate_insert_failure";

    @BeforeEach
    void setUp() {
        issueRepository.deleteAll().block();

        Mockito.when(projectServiceStub.checkProjectMemberRole(any(CheckProjectMemberRoleRequest.class)))
               .thenReturn(Mono.just(CheckProjectMemberRoleResponse.newBuilder()
                                                                   .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                                                                   .setIsMember(true)
                                                                   .setProjectExists(true)
                                                                   .build()));

        Mockito.when(projectServiceStub.getProjectKeyInternal(any(GetProjectKeyInternalRequest.class)))
               .thenReturn(Mono.just(ProjectKeyResponse.newBuilder()
                                                       .setProjectKey("TST")
                                                       .build()));
    }

    @DisplayName("Два одновременных PATCH с одной версией: один применяется, второй получает конфликт версий")
    @Test
    void concurrentPatchesWithSameVersion_onlyOneApplied() {
        Issue issue = createIssue();

        Mono<PatchIssueResult> first = issuePatchService.patchIssue(REQUEST_ID, NODE_ID, issue.getId(), ACTOR_USER_ID,
                issue.getVersion(), PatchBuilder.patch().summary("Первый").build());
        Mono<PatchIssueResult> second = issuePatchService.patchIssue(REQUEST_ID, NODE_ID, issue.getId(), ACTOR_USER_ID,
                issue.getVersion(), PatchBuilder.patch().summary("Второй").build());

        List<PatchIssueResult> results = Mono.zip(
                                                     first.subscribeOn(Schedulers.parallel()),
                                                     second.subscribeOn(Schedulers.parallel()))
                                             .map(tuple -> List.of(tuple.getT1(), tuple.getT2()))
                                             .block();

        Assertions.assertThat(results).isNotNull();
        Assertions.assertThat(results).filteredOn(PatchIssueResult::versionConflict).hasSize(1);
        Assertions.assertThat(results).filteredOn(result -> !result.versionConflict()).hasSize(1);

        PatchIssueResult applied = results.stream().filter(result -> !result.versionConflict()).findFirst().orElseThrow();
        Issue fetched = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getVersion()).isEqualTo(issue.getVersion() + 1);
        Assertions.assertThat(fetched.getSummary()).isEqualTo(applied.issue().getSummary());

        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_UPDATED)).isEqualTo(1);
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.UPDATED)).isEqualTo(1);
    }

    @DisplayName("PATCH не меняет неизменяемые поля задачи и не создаёт новую строку")
    @Test
    void patch_doesNotChangeImmutableFields() {
        Issue issue = createIssue();
        Issue before = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(before).isNotNull();

        PatchIssueResult result = patch(issue, PatchBuilder.patch()
                                                           .summary("Новое название")
                                                           .priority(IssuePriority.HIGH)
                                                           .description(NullableField.of("Новое описание"))
                                                           .assigneeId(NullableField.of(NEW_ASSIGNEE_ID))
                                                           .storyPoints(NullableField.of(new BigDecimal("8")))
                                                           .build());
        Assertions.assertThat(result.versionConflict()).isFalse();

        Issue after = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(after).isNotNull();
        Assertions.assertThat(after.getIssueKey()).isEqualTo(before.getIssueKey());
        Assertions.assertThat(after.getIssueNumber()).isEqualTo(before.getIssueNumber());
        Assertions.assertThat(after.getReporterId()).isEqualTo(before.getReporterId());
        Assertions.assertThat(after.getStatusKey()).isEqualTo(before.getStatusKey());
        Assertions.assertThat(after.getIssueType()).isEqualTo(before.getIssueType());
        Assertions.assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        Assertions.assertThat(after.getProjectId()).isEqualTo(before.getProjectId());
        Assertions.assertThat(after.getDeletedAt()).isNull();
        Assertions.assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);

        Assertions.assertThat(issueRepository.count().block()).isEqualTo(1);
    }

    @DisplayName("Nullable-поля: of(null) записывает NULL, absent оставляет значение, of(x) записывает x")
    @Test
    void patch_nullableFieldsSemantics() {
        Issue issue = createIssue();

        // of(null) очищает поле, absent — не трогает
        PatchIssueResult cleared = patch(issue, PatchBuilder.patch()
                                                            .description(NullableField.of(null))
                                                            .storyPoints(NullableField.of(null))
                                                            .startDate(NullableField.of(null))
                                                            .originalEstimateMinutes(NullableField.of(null))
                                                            .build());
        Assertions.assertThat(cleared.versionConflict()).isFalse();

        Map<String, Object> afterClear = selectPlanningColumns(issue.getId());
        Assertions.assertThat(afterClear.get("description")).isNull();
        Assertions.assertThat(afterClear.get("story_points")).isNull();
        Assertions.assertThat(afterClear.get("start_date")).isNull();
        Assertions.assertThat(afterClear.get("original_estimate_minutes")).isNull();
        Assertions.assertThat(afterClear.get("summary")).isEqualTo(SUMMARY);
        Assertions.assertThat(afterClear.get("due_date")).isEqualTo(DUE_DATE.toString());
        Assertions.assertThat(afterClear.get("remaining_estimate_minutes")).isEqualTo(REMAINING_ESTIMATE_MINUTES.toString());

        // of(x) записывает новое значение
        LocalDate newStartDate = LocalDate.of(2026, 9, 5);
        PatchIssueResult updated = patch(cleared.issue(), PatchBuilder.patch()
                                                                      .description(NullableField.of("Новое описание"))
                                                                      .storyPoints(NullableField.of(new BigDecimal("3.5")))
                                                                      .startDate(NullableField.of(newStartDate))
                                                                      .originalEstimateMinutes(NullableField.of(90))
                                                                      .build());
        Assertions.assertThat(updated.versionConflict()).isFalse();

        Map<String, Object> afterSet = selectPlanningColumns(issue.getId());
        Assertions.assertThat(afterSet.get("description")).isEqualTo("Новое описание");
        Assertions.assertThat(afterSet.get("story_points")).isEqualTo("3.50");
        Assertions.assertThat(afterSet.get("start_date")).isEqualTo(newStartDate.toString());
        Assertions.assertThat(afterSet.get("due_date")).isEqualTo(DUE_DATE.toString());
        Assertions.assertThat(afterSet.get("original_estimate_minutes")).isEqualTo("90");
        Assertions.assertThat(afterSet.get("remaining_estimate_minutes")).isEqualTo(REMAINING_ESTIMATE_MINUTES.toString());

        // Ответ PATCH совпадает с тем, что прочитается из БД: тот же масштаб story points и те же даты
        Issue fetched = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(updated.issue().getStoryPoints()).isEqualTo(new BigDecimal("3.50"));
        Assertions.assertThat(fetched.getStoryPoints()).isEqualTo(updated.issue().getStoryPoints());
        Assertions.assertThat(fetched.getStartDate()).isEqualTo(newStartDate);
        Assertions.assertThat(fetched.getDueDate()).isEqualTo(DUE_DATE);
    }

    @DisplayName("PATCH мягко удалённой задачи -> NOT_FOUND")
    @Test
    void patch_softDeletedIssue_notFound() {
        Issue issue = createIssue();
        Issue deleted = issueRepository.softDeleteAndReturn(issue.getId()).block();
        Assertions.assertThat(deleted).isNotNull();

        StepVerifier.create(issuePatchService.patchIssue(REQUEST_ID, NODE_ID, issue.getId(), ACTOR_USER_ID,
                            deleted.getVersion(), PatchBuilder.patch().summary("Новое название").build()))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                        Assertions.assertThat(((DomainException) throwable).getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    })
                    .verify();

        Issue fetched = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getSummary()).isEqualTo(SUMMARY);
        Assertions.assertThat(fetched.getVersion()).isEqualTo(deleted.getVersion());
        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_UPDATED)).isZero();
    }

    @DisplayName("PATCH полей и исполнителя пишет UPDATED/ASSIGNED в outbox и history, подписывает исполнителя; payload — jsonb-объект")
    @Test
    void patch_writesEventsAndWatcher() {
        Issue issue = createIssue();

        PatchIssueResult result = patch(issue, PatchBuilder.patch()
                                                           .summary("Новое название")
                                                           .assigneeId(NullableField.of(NEW_ASSIGNEE_ID))
                                                           .build());
        Assertions.assertThat(result.versionConflict()).isFalse();

        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_UPDATED)).isEqualTo(1);
        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_ASSIGNED)).isEqualTo(1);
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.UPDATED)).isEqualTo(1);
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.ASSIGNED)).isEqualTo(1);
        Assertions.assertThat(countWatchers(issue.getId(), NEW_ASSIGNEE_ID)).isEqualTo(1);

        List<String> updatedKeys = List.of("actorUserId", "assigneeId", "oldSummary", "newSummary", "watcherIds");
        Assertions.assertThat(outboxPayloadKeys(issue.getId(), EventType.ISSUE_UPDATED))
                  .containsExactlyInAnyOrderElementsOf(updatedKeys);
        Assertions.assertThat(historyPayloadKeys(issue.getId(), IssueEventType.UPDATED))
                  .containsExactlyInAnyOrderElementsOf(updatedKeys);
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_UPDATED, "oldSummary")).isEqualTo(SUMMARY);
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_UPDATED, "newSummary")).isEqualTo("Новое название");
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_UPDATED, "assigneeId"))
                  .isEqualTo(NEW_ASSIGNEE_ID.toString());

        List<String> assignedKeys = List.of("previousAssigneeId", "assigneeId", "actorUserId", "watcherIds");
        Assertions.assertThat(outboxPayloadKeys(issue.getId(), EventType.ISSUE_ASSIGNED))
                  .containsExactlyInAnyOrderElementsOf(assignedKeys);
        Assertions.assertThat(historyPayloadKeys(issue.getId(), IssueEventType.ASSIGNED))
                  .containsExactlyInAnyOrderElementsOf(assignedKeys);
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_ASSIGNED, "previousAssigneeId")).isNull();
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_ASSIGNED, "assigneeId"))
                  .isEqualTo(NEW_ASSIGNEE_ID.toString());
        Assertions.assertThat(outboxPayloadValue(issue.getId(), EventType.ISSUE_ASSIGNED, "actorUserId"))
                  .isEqualTo(ACTOR_USER_ID.toString());
    }

    @DisplayName("Новый исполнитель уже наблюдатель: PATCH успешен, дубля в issue_watchers нет")
    @Test
    void patch_assigneeAlreadyWatcher_noDuplicate() {
        Issue issue = createIssue();
        issueWatcherRepository.insertIfAbsent(issue.getId(), PROJECT_ID, NEW_ASSIGNEE_ID, ACTOR_USER_ID).block();
        long watchedEventsBefore = countOutboxEvents(issue.getId(), EventType.ISSUE_WATCHED);

        PatchIssueResult result = patch(issue, PatchBuilder.patch()
                                                           .assigneeId(NullableField.of(NEW_ASSIGNEE_ID))
                                                           .build());

        Assertions.assertThat(result.versionConflict()).isFalse();
        Assertions.assertThat(result.issue().getAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        Assertions.assertThat(countWatchers(issue.getId(), NEW_ASSIGNEE_ID)).isEqualTo(1);
        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_WATCHED)).isEqualTo(watchedEventsBefore);
        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_ASSIGNED)).isEqualTo(1);
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.ASSIGNED)).isEqualTo(1);
    }

    static Stream<Arguments> eventWriteFailures() {
        return Stream.of(
                Arguments.of("outbox IssueUpdated", "taska.outbox_events",
                        "NEW.aggregate_id = '%s' AND NEW.event_type = '" + EventType.ISSUE_UPDATED.getValue() + "'"),
                Arguments.of("history UPDATED", "taska.issue_history",
                        "NEW.issue_id = '%s' AND NEW.event_type = 'UPDATED'"),
                Arguments.of("history ASSIGNED", "taska.issue_history",
                        "NEW.issue_id = '%s' AND NEW.event_type = 'ASSIGNED'"),
                Arguments.of("outbox IssueAssigned", "taska.outbox_events",
                        "NEW.aggregate_id = '%s' AND NEW.event_type = '" + EventType.ISSUE_ASSIGNED.getValue() + "'")
        );
    }

    @DisplayName("Ошибка записи outbox или history после save откатывает изменения задачи")
    @ParameterizedTest(name = "сбой: {0}")
    @MethodSource("eventWriteFailures")
    void patch_eventWriteFailure_rollsBack(String failure, String table, String conditionTemplate) {
        Issue issue = createIssue();

        installFailureTrigger(table, conditionTemplate.formatted(issue.getId()));
        try {
            StepVerifier.create(issuePatchService.patchIssue(REQUEST_ID, NODE_ID, issue.getId(), ACTOR_USER_ID,
                                issue.getVersion(), PatchBuilder.patch()
                                                                .summary("Новое название")
                                                                .assigneeId(NullableField.of(NEW_ASSIGNEE_ID))
                                                                .build()))
                        .expectErrorSatisfies(throwable -> Assertions.assertThat(throwable)
                                                                     .hasStackTraceContaining("simulated insert failure"))
                        .verify();
        } finally {
            dropFailureTrigger(table);
        }

        Issue fetched = issueRepository.findById(issue.getId()).block();
        Assertions.assertThat(fetched).isNotNull();
        Assertions.assertThat(fetched.getVersion()).isEqualTo(issue.getVersion());
        Assertions.assertThat(fetched.getSummary()).isEqualTo(SUMMARY);
        Assertions.assertThat(fetched.getAssigneeId()).isNull();
        Assertions.assertThat(fetched.getUpdatedAt()).isEqualTo(issue.getUpdatedAt());

        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_UPDATED)).isZero();
        Assertions.assertThat(countOutboxEvents(issue.getId(), EventType.ISSUE_ASSIGNED)).isZero();
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.UPDATED)).isZero();
        Assertions.assertThat(countHistoryEvents(issue.getId(), IssueEventType.ASSIGNED)).isZero();
        Assertions.assertThat(countWatchers(issue.getId(), NEW_ASSIGNEE_ID)).isZero();
    }

    private Issue createIssue() {
        Issue created = issueService.createIssue(
                                            REQUEST_ID, NODE_ID, UUID.randomUUID().toString(), PROJECT_ID, IssueType.TASK,
                                            SUMMARY, DESCRIPTION, IssuePriority.MEDIUM, REPORTER_ID,
                                            STORY_POINTS, START_DATE, DUE_DATE,
                                            ORIGINAL_ESTIMATE_MINUTES, REMAINING_ESTIMATE_MINUTES)
                                    .block();
        Assertions.assertThat(created).isNotNull();
        // Перечитываем из БД, чтобы timestamp'ы были с точностью колонки
        return issueRepository.findById(created.getId()).block();
    }

    private PatchIssueResult patch(Issue issue, IssuePatch patch) {
        PatchIssueResult result = issuePatchService.patchIssue(REQUEST_ID, NODE_ID, issue.getId(), ACTOR_USER_ID,
                                                           issue.getVersion(), patch)
                                                   .block();
        Assertions.assertThat(result).isNotNull();
        return result;
    }

    /**
     * Читает колонки задачи в текстовом виде, чтобы проверять значения так, как они лежат в БД,
     * без маппинга R2DBC.
     */
    private Map<String, Object> selectPlanningColumns(UUID issueId) {
        return databaseClient.sql("""
                                     SELECT summary, description, story_points::text AS story_points,
                                            start_date::text AS start_date, due_date::text AS due_date,
                                            original_estimate_minutes::text AS original_estimate_minutes,
                                            remaining_estimate_minutes::text AS remaining_estimate_minutes
                                     FROM taska.issues WHERE id = :id
                                     """)
                             .bind("id", issueId)
                             .fetch()
                             .one()
                             .block();
    }

    private long countOutboxEvents(UUID issueId, EventType type) {
        return databaseClient.sql("SELECT COUNT(*) FROM taska.outbox_events WHERE aggregate_id = :id AND event_type = :type")
                             .bind("id", issueId)
                             .bind("type", type.getValue())
                             .map(row -> row.get(0, Long.class))
                             .one()
                             .block();
    }

    private long countHistoryEvents(UUID issueId, IssueEventType type) {
        return databaseClient.sql("SELECT COUNT(*) FROM taska.issue_history WHERE issue_id = :id AND event_type = :type")
                             .bind("id", issueId)
                             .bind("type", type.name())
                             .map(row -> row.get(0, Long.class))
                             .one()
                             .block();
    }

    private long countWatchers(UUID issueId, UUID userId) {
        return databaseClient.sql("SELECT COUNT(*) FROM taska.issue_watchers WHERE issue_id = :id AND user_id = :userId")
                             .bind("id", issueId)
                             .bind("userId", userId)
                             .map(row -> row.get(0, Long.class))
                             .one()
                             .block();
    }

    /**
     * Ключи payload события. {@code jsonb_object_keys} упадёт, если payload записан не jsonb-объектом
     * (например, JSON-строкой).
     */
    private List<String> outboxPayloadKeys(UUID issueId, EventType type) {
        return databaseClient.sql("""
                                     SELECT jsonb_object_keys(payload) AS k FROM taska.outbox_events
                                     WHERE aggregate_id = :id AND event_type = :type
                                     """)
                             .bind("id", issueId)
                             .bind("type", type.getValue())
                             .map(row -> row.get("k", String.class))
                             .all()
                             .collectList()
                             .block();
    }

    private List<String> historyPayloadKeys(UUID issueId, IssueEventType type) {
        return databaseClient.sql("""
                                     SELECT jsonb_object_keys(payload) AS k FROM taska.issue_history
                                     WHERE issue_id = :id AND event_type = :type
                                     """)
                             .bind("id", issueId)
                             .bind("type", type.name())
                             .map(row -> row.get("k", String.class))
                             .all()
                             .collectList()
                             .block();
    }

    private String outboxPayloadValue(UUID issueId, EventType type, String key) {
        return databaseClient.sql("""
                                     SELECT payload ->> :key AS v FROM taska.outbox_events
                                     WHERE aggregate_id = :id AND event_type = :type
                                     """)
                             .bind("key", key)
                             .bind("id", issueId)
                             .bind("type", type.getValue())
                             .map(row -> Optional.ofNullable(row.get("v", String.class)))
                             .one()
                             .block()
                             .orElse(null);
    }

    /**
     * Ставит на таблицу триггер, который падает при вставке строки, подходящей под {@code condition}.
     * Так сбой записи события происходит в самой БД, внутри транзакции PATCH.
     */
    private void installFailureTrigger(String table, String condition) {
        databaseClient.sql(FAILURE_FUNCTION_SQL).then().block();
        databaseClient.sql("CREATE TRIGGER " + FAILURE_TRIGGER + " BEFORE INSERT ON " + table
                                   + " FOR EACH ROW WHEN (" + condition + ")"
                                   + " EXECUTE FUNCTION taska.it_simulate_insert_failure()")
                      .then()
                      .block();
    }

    private void dropFailureTrigger(String table) {
        databaseClient.sql("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER + " ON " + table).then().block();
    }

    /**
     * Собирает {@link IssuePatch}, в котором по умолчанию ни одно поле не меняется.
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

        static PatchBuilder patch() {
            return new PatchBuilder();
        }

        PatchBuilder summary(String value) {
            summary = Optional.of(value);
            return this;
        }

        PatchBuilder priority(IssuePriority value) {
            priority = Optional.of(value);
            return this;
        }

        PatchBuilder description(NullableField<String> value) {
            description = value;
            return this;
        }

        PatchBuilder assigneeId(NullableField<UUID> value) {
            assigneeId = value;
            return this;
        }

        PatchBuilder storyPoints(NullableField<BigDecimal> value) {
            storyPoints = value;
            return this;
        }

        PatchBuilder startDate(NullableField<LocalDate> value) {
            startDate = value;
            return this;
        }

        PatchBuilder originalEstimateMinutes(NullableField<Integer> value) {
            originalEstimateMinutes = value;
            return this;
        }

        IssuePatch build() {
            return new IssuePatch(summary, priority, description, assigneeId, storyPoints,
                    startDate, dueDate, originalEstimateMinutes, remainingEstimateMinutes);
        }
    }
}
