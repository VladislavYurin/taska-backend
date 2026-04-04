package ru.taska.event;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Универсальное доменное событие из Kafka.
 *
 * <p>Структура соответствует сообщению, публикуемому из transactional outbox
 * сервисов Taska (auth, issue, project и т.п.).</p>
 */
public record TaskaEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        JsonNode payload
) {
}

