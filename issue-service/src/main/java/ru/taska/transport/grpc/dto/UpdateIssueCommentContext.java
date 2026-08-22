package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Валидированный контекст updateIssueComment.
 */
public record UpdateIssueCommentContext(
        String requestId,
        String nodeId,
        UUID issueId,
        UUID commentId,
        UUID actorUserId,
        String body
) {
}
