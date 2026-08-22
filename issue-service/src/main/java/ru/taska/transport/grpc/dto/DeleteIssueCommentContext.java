package ru.taska.transport.grpc.dto;

import java.util.UUID;

/**
 * Валидированный контекст deleteIssueComment.
 */
public record DeleteIssueCommentContext(
        String requestId,
        String nodeId,
        UUID issueId,
        UUID commentId,
        UUID actorUserId
) {
}
