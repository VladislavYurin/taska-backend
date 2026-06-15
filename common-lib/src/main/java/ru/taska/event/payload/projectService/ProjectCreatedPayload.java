package ru.taska.event.payload.projectService;

import java.util.UUID;

/**
 * DTO для payload outbox-события при создании проекта.
 */
public record ProjectCreatedPayload(
        UUID projectId,
        String projectKey,
        UUID createdBy
) {
}
