package ru.taska.event.payload;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при приглашении пользователя.
 */
public record UserInvitedPayload(
        UUID userId,
        String email,
        String invitedBy
) {
}