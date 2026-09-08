package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Валидированный контекст addIssueComment.
 */
public record AddIssueCommentContext(
        String requestId,
        String nodeId,
        UUID issueId,
        UUID authorUserId,
        String body
) {
}
