package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при создании связи между двумя задачами.
 */
public record IssueLinkCreatedPayload(
        UUID createdBy,
        UUID sourceIssueId,
        UUID targetIssueId,
        String linkType
) {
}
