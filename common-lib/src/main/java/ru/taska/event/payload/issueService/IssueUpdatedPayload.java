package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при обновлении задачи.
 */
public record IssueUpdatedPayload(
        UUID reporterId,
        UUID assigneeId
) {
}
