package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * Payload события создания комментария.
 *
 * @param commentId     идентификатор комментария
 * @param authorUserId  идентификатор автора комментария
 * @param body          текст комментария
 */
public record CommentCreatedPayload(
        UUID commentId,
        UUID authorUserId,
        String body
) {}