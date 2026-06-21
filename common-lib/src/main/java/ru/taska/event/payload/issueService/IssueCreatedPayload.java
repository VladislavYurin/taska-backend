package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при создании задачи.
 */
public record IssueCreatedPayload(
        UUID reporterId,
        UUID assigneeId
) {
}
