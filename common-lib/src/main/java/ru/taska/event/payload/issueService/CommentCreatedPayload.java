package ru.taska.event.payload.issueService;

import java.util.List;
import java.util.UUID;

/**
 * Payload события создания комментария.
 *
 * @param commentId     идентификатор комментария
 * @param actorUserId  идентификатор автора комментария
 * @param body          текст комментария
 */
public record CommentCreatedPayload(
        UUID commentId,
        UUID actorUserId,
        String body,
        List<UUID> watcherIds
) {}