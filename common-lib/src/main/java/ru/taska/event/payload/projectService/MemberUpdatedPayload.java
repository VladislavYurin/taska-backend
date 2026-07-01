package ru.taska.event.payload.projectService;

import java.util.UUID;

/**
 * DTO для payload outbox-события при изменении участника проекта.
 */
public record MemberUpdatedPayload(
        UUID userId,
        String role,
        UUID projectId
) {

}
