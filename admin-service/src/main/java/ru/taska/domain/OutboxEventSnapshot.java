package ru.taska.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Снимок состояния outbox-события.
 * <p>
 * Используется административной операцией retry для фиксации
 * состояния события до и после изменения, а также для формирования
 * записи аудита.
 * <p>
 * Snapshot содержит payload, поскольку TAS-106 требует подтвердить,
 * что payload не изменился во время retry. При этом сама операция
 * retry не должна изменять это поле.
 *
 * @param id                  идентификатор outbox-события
 * @param aggregateType       тип агрегата
 * @param aggregateId         идентификатор агрегата
 * @param eventType           тип события
 * @param status              технический статус события
 * @param payload             payload события
 * @param attempts            количество попыток обработки
 * @param lastErrorMessage    сообщение последней ошибки
 * @param createdAt           время создания события
 * @param publishedAt         время успешной публикации
 * @param processingStartedAt время начала текущей обработки
 * @param requestId           идентификатор исходного запроса
 */
public record OutboxEventSnapshot(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        OutboxStatus status,
        JsonNode payload,
        Integer attempts,
        String lastErrorMessage,
        Instant createdAt,
        Instant publishedAt,
        Instant processingStartedAt,
        String requestId
) {
}