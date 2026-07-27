package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.OutboxEvent;
import ru.taska.event.EventType;
import tools.jackson.databind.JsonNode;

/**
 * Сервис для взаимодействия с аутбоксом.
*/
public interface OutboxEventService {

    /**
     * Создает {@link OutboxEvent} и сохраняет в outbox сообщение при создании задачи.
     *
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issue     созданная задача
     * @return Mono<{@link OutboxEvent}> сохраненное событие
     */
    Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, Issue issue);

    /**
     * Создает {@link OutboxEvent} и сохраняет в outbox сообщение при изменении или удалении задачи.
     *
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issue     измененная задача
     * @param payload   изменившиеся данные в формате {@link JsonNode}
     * @return Mono<{@link OutboxEvent}> сохраненное событие
     */
    Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, Issue issue, EventType type, JsonNode payload);

    /**
     * Создает {@link OutboxEvent} и сохраняет в outbox сообщение по идентификатору задачи.
     *
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issueId   идентификатор задачи
     * @param type      тип события
     * @param payload   данные события в формате {@link JsonNode}
     * @return Mono<{@link OutboxEvent}> сохраненное событие
     */
    Mono<OutboxEvent> saveOutboxEvent(String requestId, String nodeId, java.util.UUID issueId, EventType type, JsonNode payload);

}