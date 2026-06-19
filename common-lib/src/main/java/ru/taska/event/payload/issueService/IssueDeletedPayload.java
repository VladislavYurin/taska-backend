package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при удалении задачи.
 */
public record IssueDeletedPayload(
        UUID reporterId,
        UUID assigneeId
) {
}
