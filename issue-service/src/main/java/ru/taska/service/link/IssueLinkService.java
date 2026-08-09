package ru.taska.service.link;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueLink;
import ru.taska.domain.IssueLinkType;

import java.util.UUID;

/**
 * Сервис для работы со связями между задачами.
 */
public interface IssueLinkService {

    /**
     * Возвращает все связи задачи.
     *
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла
     * @param issueId идентификатор задачи
     * @param actorUserId пользователь, получающий список связей
     * @return список связей
     */
    Flux<IssueLink> listIssueLinks(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

    /**
     * Создает связь между двумя задачами.
     *
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла
     * @param sourceIssueId исходная задача
     * @param targetIssueId целевая задача
     * @param linkType тип связи
     * @param actorUserId пользователь, создающий связь
     * @return созданная связь
     */
    Mono<IssueLink> createIssueLink(
            String requestId,
            String nodeId,
            UUID sourceIssueId,
            UUID targetIssueId,
            IssueLinkType linkType,
            UUID actorUserId
    );

    /**
     * Удаляет существующую связь.
     *
     * @param requestId идентификатор запроса
     * @param nodeId идентификатор узла
     * @param issueId задача, из которой производится удаление
     * @param linkId идентификатор связи
     * @param actorUserId пользователь, удаляющий связь
     * @return удаленная связь
     */
    Mono<IssueLink> deleteIssueLink(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID linkId,
            UUID actorUserId
    );
}
