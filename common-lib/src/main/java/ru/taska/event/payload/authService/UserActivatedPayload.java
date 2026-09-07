package ru.taska.event.payload.authService;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при активации пользователя.
 */
public record UserActivatedPayload(
        UUID userId,
        String email
) {
}
