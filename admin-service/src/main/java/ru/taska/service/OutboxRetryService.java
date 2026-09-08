package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.OutboxEventSnapshot;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Сервис административного повторного запуска outbox-событий.
 */
public interface OutboxRetryService {

    /**
     * Повторно переводит допустимое outbox-событие в состояние NEW
     * и фиксирует административное действие в аудите.
     *
     * @param requestId   идентификатор запроса
     * @param nodeId      идентификатор узла
     * @param service     сервис-владелец outbox
     * @param eventId     идентификатор outbox-события
     * @param reason      обязательная причина ручного retry
     * @param actorUserId идентификатор администратора
     * @param actorLogin  логин администратора
     * @param actorRoles  роли администратора для аудита
     * @return состояние outbox-события после retry
     */
    Mono<OutboxEventSnapshot> retryOutboxEvent(
            String requestId,
            String nodeId,
            String service,
            UUID eventId,
            String reason,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles
    );
}