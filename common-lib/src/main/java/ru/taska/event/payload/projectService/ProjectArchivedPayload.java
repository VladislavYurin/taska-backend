package ru.taska.event.payload.projectService;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO для payload outbox-события при мягком удалении проекта.
 */
public record ProjectArchivedPayload(
        UUID projectId,
        String projectKey,
        UUID createdBy,
        Instant archivedAt
) {
}
