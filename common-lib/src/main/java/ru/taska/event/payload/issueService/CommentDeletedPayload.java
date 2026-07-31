package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * Payload события удаления комментария.
 *
 * @param commentId    идентификатор комментария
 * @param actorUserId  идентификатор пользователя, выполнившего удаление
 * @param body         текст удалённого комментария
 */
public record CommentDeletedPayload(
        UUID commentId,
        UUID actorUserId,
        String body
) {}
