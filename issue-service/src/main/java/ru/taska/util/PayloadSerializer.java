package ru.taska.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueEventType;
import ru.taska.domain.IssuePriority;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
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
    public JsonNode createIssueUpdatedPayload(Issue issue, UUID actorUserId, String newSummary, String newDescription,
                                              IssuePriority newPriority, Double newStoryPoints, Instant newStartDate,
                                              Instant newDueDate, Long newOriginalEstimateMinutes, Long newRemainingEstimateMinutes) {
        ObjectNode node = objectMapper.createObjectNode();

        if (actorUserId != null) {
            node.put(REPORTER, actorUserId.toString());
        } else {
            node.putNull(REPORTER);
        }

        if (issue.getAssigneeId() != null) {
            node.put(ASSIGNEE, issue.getAssigneeId().toString());
        } else {
            node.putNull(ASSIGNEE);
        }

        boolean isChanged = false;

        if (newSummary != null && !newSummary.equals(issue.getSummary())) {
            node.put(OLD_SUMMARY, issue.getSummary());
            node.put(NEW_SUMMARY, newSummary);
            isChanged = true;
        }
        if (newDescription != null && !newDescription.equals(issue.getDescription())) {
            node.put(OLD_DESCRIPTION, issue.getDescription());
            node.put(NEW_DESCRIPTION, newDescription);
            isChanged = true;
        }
        if (newPriority != null && !newPriority.equals(issue.getPriority())) {
            node.put(OLD_PRIORITY, issue.getPriority().toString());
            node.put(NEW_PRIORITY, newPriority.toString());
            isChanged = true;
        }

        if (newStoryPoints != null && !newStoryPoints.equals(issue.getStoryPoints())) {
            node.put(OLD_STORY_POINTS, issue.getStoryPoints());
            node.put(NEW_STORY_POINTS, newStoryPoints);
            isChanged = true;
        }
        if (newStartDate != null && !newStartDate.equals(Instant.EPOCH) && !newStartDate.equals(issue.getStartDate())) {
            node.put(OLD_START_DATE, issue.getStartDate() != null ? issue.getStartDate().toString() : null);
            node.put(NEW_START_DATE, newStartDate.toString());
            isChanged = true;
        }
        if (newDueDate != null && !newDueDate.equals(Instant.EPOCH) && !newDueDate.equals(issue.getDueDate())) {
            node.put(OLD_DUE_DATE, issue.getDueDate() != null ? issue.getDueDate().toString() : null);
            node.put(NEW_DUE_DATE, newDueDate.toString());
            isChanged = true;
        }

        if (newOriginalEstimateMinutes != null && !newOriginalEstimateMinutes.equals(issue.getOriginalEstimateMinutes())) {
            node.put(OLD_ORIGINAL_ESTIMATED, issue.getOriginalEstimateMinutes());
            node.put(NEW_ORIGINAL_ESTIMATED, newOriginalEstimateMinutes);
            isChanged = true;
        }
        if (newRemainingEstimateMinutes != null && !newRemainingEstimateMinutes.equals(issue.getRemainingEstimateMinutes())) {
            node.put(OLD_REMAINING_ESTIMATED, issue.getRemainingEstimateMinutes());
            node.put(NEW_REMAINING_ESTIMATED, newRemainingEstimateMinutes);
            isChanged = true;
        }

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
