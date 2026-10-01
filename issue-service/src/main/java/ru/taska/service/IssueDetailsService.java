package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.aggregate.IssueDetailsAggregate;

import java.util.UUID;

/**
 * Сервис для получения полной информации о задаче.
 */
public interface IssueDetailsService {
    /**
     * Возвращает полную информацию о задаче с учётом прав доступа пользователя.
     *
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла сервиса
     * @param issueId идентификатор задачи
     * @param actorUserId идентификатор пользователя, выполняющего запрос
     * @return агрегат с информацией о задаче
     */
    Mono<IssueDetailsAggregate> getIssueDetails(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );
}
