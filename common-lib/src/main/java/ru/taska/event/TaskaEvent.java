package ru.taska.event;

import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Универсальное доменное событие из Kafka.
 *
 * <p>Структура соответствует сообщению, публикуемому из transactional outbox
 * сервисов Taska (auth, issue, project и т.п.).</p>
 */
@Builder
public record TaskaEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        JsonNode payload
) {
}
