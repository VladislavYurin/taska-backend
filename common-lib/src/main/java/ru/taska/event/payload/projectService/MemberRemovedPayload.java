package ru.taska.event.payload.projectService;

import java.util.UUID;

/**
 * DTO для payload outbox-события при удалении участника из проекта.
 */
public record MemberRemovedPayload(
        UUID projectId,
        UUID userId
) {
}
