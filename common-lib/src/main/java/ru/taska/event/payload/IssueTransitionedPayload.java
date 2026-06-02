package ru.taska.event.payload;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при изменении статуса задачи.
 */
public record IssueTransitionedPayload(
        UUID reporterId,
        UUID assigneeId
) {
}
