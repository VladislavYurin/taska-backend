package ru.taska.event.payload.issueService;

import java.util.List;
import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при обновлении задачи.
 */
public record IssueUpdatedPayload(
        UUID assigneeId,
        UUID actorUserId,
        List<UUID> watcherIds
) {
}
