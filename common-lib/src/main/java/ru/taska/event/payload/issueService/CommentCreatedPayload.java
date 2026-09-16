package ru.taska.event.payload.issueService;

import java.util.List;
import java.util.UUID;

/**
 * Payload события создания комментария.
 *
 * @param commentId     идентификатор комментария
 * @param actorUserId  идентификатор автора комментария
 * @param body          текст комментария
 * @param watcherIds   список айди наблюдателей за задачей, к которой создали комментарий
 */
public record CommentCreatedPayload(
        UUID commentId,
        UUID actorUserId,
        String body,
        List<UUID> watcherIds
) {}