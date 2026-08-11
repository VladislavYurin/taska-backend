package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.entity.OutboxEvent;
import ru.taska.event.AggregateType;
import ru.taska.event.EventType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Сервис для взаимодействия с аутбоксом.
 */
public interface OutboxEventService {

    /**
     * Создает {@link OutboxEvent} и сохраняет в outbox сообщение по идентификатору задачи.
     *
     * @param requestId     идентификатор запроса
     * @param nodeId        идентификатор узла
     * @param aggregateType тип агрегата
     * @param aggregateId   идентификатор агрегата
     * @param type          тип события
     * @param payload       изменившиеся данные в формате {@link JsonNode}
     * @return Mono<{@link OutboxEvent}> сохраненное событие
     */
    Mono<OutboxEvent> saveOutboxEvent(
            String requestId,
            String nodeId,
            AggregateType aggregateType,
            UUID aggregateId,
            EventType type,
            JsonNode payload
    );
}
