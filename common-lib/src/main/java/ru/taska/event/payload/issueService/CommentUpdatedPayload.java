package ru.taska.event.payload.issueService;

import java.util.UUID;

/**
 * Payload события обновления комментария.
 *
 * @param commentId    идентификатор комментария
 * @param actorUserId  идентификатор пользователя, выполнившего обновление
 * @param oldBody      старый текст комментария
 * @param newBody      новый текст комментария
 */
public record CommentUpdatedPayload(
        UUID commentId,
        UUID actorUserId,
        String oldBody,
        String newBody
) {}