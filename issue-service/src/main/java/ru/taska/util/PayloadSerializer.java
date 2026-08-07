package ru.taska.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.UpdateField;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Создает {@link JsonNode} с фактически изменившимися данными задачи.
 */
@Component
@RequiredArgsConstructor
public class PayloadSerializer {
    private static final String REPORTER = "reporterId";
    private static final String ASSIGNEE = "assigneeId";
    private static final String PREVIOUS_ASSIGNEE_ID = "previousAssigneeId";
    private static final String OLD_SUMMARY = "oldSummary";
    private static final String NEW_SUMMARY = "newSummary";
    private static final String OLD_DESCRIPTION = "oldDescription";
    private static final String NEW_DESCRIPTION = "newDescription";
    private static final String OLD_PRIORITY = "oldPriority";
    private static final String NEW_PRIORITY = "newPriority";
    private static final String OLD_STORY_POINTS = "oldStoryPoints";
    private static final String NEW_STORY_POINTS = "newStoryPoints";
    private static final String OLD_START_DATE = "oldStartDate";
    private static final String NEW_START_DATE = "newStartDate";
    private static final String OLD_DUE_DATE = "oldDueDate";
    private static final String NEW_DUE_DATE = "newDueDate";
    private static final String NEW_ORIGINAL_ESTIMATED = "oldOriginalEstimateMinutes";
    private static final String OLD_ORIGINAL_ESTIMATED = "newOriginalEstimateMinutes";
    private static final String OLD_REMAINING_ESTIMATED = "oldRemainingEstimateMinutes";
    private static final String NEW_REMAINING_ESTIMATED  = "newRemainingEstimateMinutes";
    private static final String FROM_STATUS = "fromStatus";
    private static final String TO_STATUS = "toStatus";
    private static final String TRANSITIONED_ID = "transitionId";
    private static final String ISSUE_TYPE = "issueType";
    private static final String DELETED_AT = "deletedAt";

    private final ObjectMapper objectMapper;

    /**
     * Создает {@link JsonNode} с issue snapshot при создании задачи.
     *
     * @param issue созданная задача.
     * @return Mono<{@link JsonNode}> исторические данные.
     */
    public JsonNode createIssueCreatedPayload(Issue issue) {
        if (issue == null) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.valueToTree(issue);
    }

    /**
     * Создает {@link JsonNode} с измененными данными при назначении или переназначении задачи.
     *
     * @param previousAssigneeId айди предыдущего исполнителя.
     * @param newAssigneeId      айди нового исполнителя.
     * @return Mono<{@link JsonNode}> исторические данные.
     */
    public JsonNode createIssueAssignedPayload(UUID previousAssigneeId, UUID newAssigneeId) {
        ObjectNode node = objectMapper.createObjectNode();

        if (previousAssigneeId != null) {
            node.put(PREVIOUS_ASSIGNEE_ID, previousAssigneeId.toString());
        } else {
            node.putNull(PREVIOUS_ASSIGNEE_ID);
        }

        if (newAssigneeId != null) {
            node.put(ASSIGNEE, newAssigneeId.toString());
        } else {
            node.putNull(ASSIGNEE);
        }

        return node;
    }

    /**
     * Создает {@link JsonNode} с измененными данными при обновлении задачи.
     *
     * @param issue          измененная задача.
     * @param newSummary     новое краткое описание задачи.
     * @param newDescription новое полное описание задачи.
     * @param newPriority    новый приоритет задачи.
     * @return Mono<{@link JsonNode}> исторические данные.
     */
    public JsonNode createIssueUpdatedPayload(Issue originalIssue, UUID actorUserId,
                                              UpdateField<String> summary,
                                              UpdateField<String> description,
                                              UpdateField<IssuePriority> priority,
                                              UpdateField<Double> storyPoints,
                                              UpdateField<Instant> startDate,
                                              UpdateField<Instant> dueDate,
                                              UpdateField<Long> originalEstimateMinutes,
                                              UpdateField<Long> remainingEstimateMinutes) {

        ObjectNode node = objectMapper.createObjectNode();

        // Системные поля логов (кто и на ком выполнял)
        if (actorUserId != null) {
            node.put(REPORTER, actorUserId.toString());
        } else {
            node.putNull(REPORTER);
        }

        if (originalIssue.getAssigneeId() != null) {
            node.put(ASSIGNEE, originalIssue.getAssigneeId().toString());
        } else {
            node.putNull(ASSIGNEE);
        }

        boolean isChanged = false;

        // --- 1. Строковые поля ---
        if (summary.isPresent() && !Objects.equals(summary.value(), originalIssue.getSummary())) {
            node.put(OLD_SUMMARY, originalIssue.getSummary());
            if (summary.value() != null) node.put(NEW_SUMMARY, summary.value()); else node.putNull(NEW_SUMMARY);
            isChanged = true;
        }

        if (description.isPresent() && !Objects.equals(description.value(), originalIssue.getDescription())) {
            node.put(OLD_DESCRIPTION, originalIssue.getDescription());
            if (description.value() != null) node.put(NEW_DESCRIPTION, description.value()); else node.putNull(NEW_DESCRIPTION);
            isChanged = true;
        }

        // --- 2. Энумы ---
        if (priority.isPresent() && !Objects.equals(priority.value(), originalIssue.getPriority())) {
            node.put(OLD_PRIORITY, originalIssue.getPriority() != null ? originalIssue.getPriority().toString() : null);
            if (priority.value() != null) node.put(NEW_PRIORITY, priority.value().toString()); else node.putNull(NEW_PRIORITY);
            isChanged = true;
        }

        // --- 3. Числа ---
        if (storyPoints.isPresent() && !Objects.equals(storyPoints.value(), originalIssue.getStoryPoints())) {
            node.put(OLD_STORY_POINTS, originalIssue.getStoryPoints());
            if (storyPoints.value() != null) node.put(NEW_STORY_POINTS, storyPoints.value()); else node.putNull(NEW_STORY_POINTS);
            isChanged = true;
        }

        // --- 4. Даты (Убрали костыли с Instant.EPOCH) ---
        if (startDate.isPresent() && !Objects.equals(startDate.value(), originalIssue.getStartDate())) {
            node.put(OLD_START_DATE, originalIssue.getStartDate() != null ? originalIssue.getStartDate().toString() : null);
            if (startDate.value() != null) node.put(NEW_START_DATE, startDate.value().toString()); else node.putNull(NEW_START_DATE);
            isChanged = true;
        }

        if (dueDate.isPresent() && !Objects.equals(dueDate.value(), originalIssue.getDueDate())) {
            node.put(OLD_DUE_DATE, originalIssue.getDueDate() != null ? originalIssue.getDueDate().toString() : null);
            if (dueDate.value() != null) node.put(NEW_DUE_DATE, dueDate.value().toString()); else node.putNull(NEW_DUE_DATE);
            isChanged = true;
        }

        // --- 5. Тайм-трекинг ---
        if (originalEstimateMinutes.isPresent() && !Objects.equals(originalEstimateMinutes.value(), originalIssue.getOriginalEstimateMinutes())) {
            node.put(OLD_ORIGINAL_ESTIMATED, originalIssue.getOriginalEstimateMinutes());
            if (originalEstimateMinutes.value() != null) node.put(NEW_ORIGINAL_ESTIMATED, originalEstimateMinutes.value()); else node.putNull(NEW_ORIGINAL_ESTIMATED);
            isChanged = true;
        }

        if (remainingEstimateMinutes.isPresent() && !Objects.equals(remainingEstimateMinutes.value(), originalIssue.getRemainingEstimateMinutes())) {
            node.put(OLD_REMAINING_ESTIMATED, originalIssue.getRemainingEstimateMinutes());
            if (remainingEstimateMinutes.value() != null) node.put(NEW_REMAINING_ESTIMATED, remainingEstimateMinutes.value()); else node.putNull(NEW_REMAINING_ESTIMATED);
            isChanged = true;
        }

        // Если реально ничего не поменялось (прислали те же значения, что уже были в базе)
        if (!isChanged) {
            return objectMapper.createObjectNode();
        }

        return node;
    }


    /**
     * Создает {@link JsonNode} с измененными данными при изменении статуса задачи.
     *
     * @param sourceStatus текущий статус.
     * @param targetStatus новый статус.
     * @param actorUserId  айди актора изменения.
     * @return Mono<{@link JsonNode}> исторические данные.
     */
    public JsonNode createTransitionedPayload(String sourceStatus, String targetStatus, UUID transitionId, UUID actorUserId, UUID assigneeId) {
        ObjectNode node = objectMapper.createObjectNode();

        if (actorUserId != null) {
            node.put(REPORTER, actorUserId.toString());
        } else {
            node.putNull(REPORTER);
        }

        if (assigneeId != null) {
            node.put(ASSIGNEE, assigneeId.toString());
        } else {
            node.putNull(ASSIGNEE);
        }

        node.put(TRANSITIONED_ID, transitionId.toString());
        node.put(FROM_STATUS, sourceStatus != null ? sourceStatus : "");
        node.put(TO_STATUS, targetStatus != null ? targetStatus : "");

        return node;
    }

    /**
     * Создает {@link JsonNode} с измененными данными при удалении задачи.
     *
     * @param type      тип произошедшего события.
     * @param deletedAt время удаления.
     * @return Mono<{@link JsonNode}> исторические данные.
     */
    public JsonNode createIssueDeletedPayload(IssueEventType type, Instant deletedAt, UUID actorUserId, UUID assigneeId) {
        ObjectNode node = objectMapper.createObjectNode();

        if (actorUserId != null) {
            node.put(REPORTER, actorUserId.toString());
        } else {
            node.putNull(REPORTER);
        }

        if (assigneeId != null) {
            node.put(ASSIGNEE, assigneeId.toString());
        } else {
            node.putNull(ASSIGNEE);
        }

        node.put(ISSUE_TYPE, type != null ? type.toString() : "");
        node.put(DELETED_AT, deletedAt != null ? deletedAt.toString() : Instant.now().toString());

        return node;
    }
}
