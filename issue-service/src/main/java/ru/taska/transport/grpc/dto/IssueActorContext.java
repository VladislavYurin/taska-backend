package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Валидированный контекст запроса с issueId и actorUserId.
 */
public record IssueActorContext(
        String requestId,
        String nodeId,
        UUID issueId,
        UUID actorUserId
) {
}
