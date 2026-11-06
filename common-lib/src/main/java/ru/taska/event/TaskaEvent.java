package ru.taska.event;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import tools.jackson.databind.JsonNode;

/**
 * Универсальное доменное событие из Kafka.
 *
 * <p>Структура соответствует сообщению, публикуемому из transactional outbox
 * сервисов Taska (auth, issue, project и т.п.).</p>
 */
@Builder
public record TaskaEvent(
        UUID id,
        EventScopeKind scopeKind,
        UUID tenantId,
        UUID scopeId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        JsonNode payload,
        String requestId,
        Instant occurredAt,
        String producerService,
        String schemaVersion
) {
}
