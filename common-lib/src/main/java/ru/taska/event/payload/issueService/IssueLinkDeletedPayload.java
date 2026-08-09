package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при удалении связи между двумя задачами.
 */
public record IssueLinkDeletedPayload(
        UUID deletedBy,
        UUID sourceIssueId,
        UUID targetIssueId,
        String linkType
) {
}
