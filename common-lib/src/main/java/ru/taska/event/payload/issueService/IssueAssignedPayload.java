package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при назначении ответственного за задачу.
 */
public record IssueAssignedPayload(
        UUID assigneeId
) {
}
