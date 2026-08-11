package ru.taska.event.payload.authService;

import lombok.Builder;

import java.util.UUID;

/**
 * DTO для payload при отправке в Kafka outbox события при изменении статуса пользователя
 */
@Builder
public record UserStatusChangedPayload(
        UUID userId,
        String oldStatus,
        String newStatus,
        String reason,
        UUID actorUserId
) {}