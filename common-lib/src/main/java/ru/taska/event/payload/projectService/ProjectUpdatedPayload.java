package ru.taska.event.payload.projectService;

import java.util.UUID;

/**
 * DTO для payload outbox-события при обновлении проекта.
 */
public record ProjectUpdatedPayload(
        UUID projectId,
        String name,
        String description,
        String color,
        UUID updatedBy
) {
}
