package ru.taska.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueAttachment;
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
    private static final String FROM_STATUS = "fromStatus";
    private static final String TO_STATUS = "toStatus";
    private static final String TRANSITIONED_ID = "transitionId";
    private static final String ISSUE_TYPE = "issueType";
    private static final String DELETED_AT = "deletedAt";
    private static final String ATTACHMENT_ID = "attachmentId";
    private static final String ISSUE_ID = "issueId";
    private static final String UPLOADED_BY = "uploadedBy";
    private static final String DELETED_BY = "deletedBy";
    private static final String FILE_NAME = "fileName";
    private static final String CONTENT_TYPE = "contentType";
    private static final String SIZE_BYTES = "sizeBytes";
    private static final String CREATED_AT = "createdAt";

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
    public JsonNode createIssueUpdatedPayload(Issue issue, UUID actorUserId, String newSummary, String newDescription, IssuePriority newPriority) {
        if (newSummary.equals(issue.getSummary()) &&
                newDescription.equals(issue.getDescription()) &&
                newPriority.equals(issue.getPriority())) {

            return objectMapper.createObjectNode();
        }

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

        if (!newSummary.equals(issue.getSummary())) {
            node.put(OLD_SUMMARY, issue.getSummary());
            node.put(NEW_SUMMARY, newSummary);
        }
        if (!newDescription.equals(issue.getDescription())) {
            node.put(OLD_DESCRIPTION, issue.getDescription());
            node.put(NEW_DESCRIPTION, newDescription);
        }
        if (!newPriority.equals(issue.getPriority())) {
            node.put(OLD_PRIORITY, issue.getPriority().toString());
            node.put(NEW_PRIORITY, newPriority.toString());
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

    /**
     * Создает {@link JsonNode} с данными о загруженном вложении.
     *
     * @param attachment сохранённое вложение.
     * @return {@link JsonNode} с метаданными загрузки.
     */
    public JsonNode createAttachmentUploadedPayload(IssueAttachment attachment) {
        ObjectNode node = objectMapper.createObjectNode();

        node.put(ATTACHMENT_ID, attachment.getId().toString());
        node.put(ISSUE_ID, attachment.getIssueId().toString());
        node.put(UPLOADED_BY, attachment.getUploadedBy().toString());
        node.put(FILE_NAME, attachment.getFileName());
        node.put(CONTENT_TYPE, attachment.getContentType());
        node.put(SIZE_BYTES, attachment.getSizeBytes());
        node.put(CREATED_AT, attachment.getCreatedAt().toString());

        return node;
    }

    /**
     * Создает {@link JsonNode} с данными об удалённом вложении.
     *
     * @param attachment  удалённое вложение.
     * @param deletedByUserId идентификатор пользователя, выполнившего удаление.
     * @return {@link JsonNode} с метаданными удаления.
     */
    public JsonNode createAttachmentDeletedPayload(IssueAttachment attachment, UUID deletedByUserId) {
        ObjectNode node = objectMapper.createObjectNode();

        node.put(ATTACHMENT_ID, attachment.getId().toString());
        node.put(ISSUE_ID, attachment.getIssueId().toString());
        node.put(DELETED_BY, deletedByUserId.toString());
        node.put(FILE_NAME, attachment.getFileName());
        node.put(DELETED_AT, attachment.getDeletedAt().toString());

        return node;
    }
}
