package ru.taska.event.payload.projectService;

import java.util.UUID;

/**
 * DTO для payload outbox-события при добавлении участника в проект.
 */
public record MemberAddedPayload(
        UUID projectId,
        UUID userId,
        String role,
        UUID addedBy
) {
}
