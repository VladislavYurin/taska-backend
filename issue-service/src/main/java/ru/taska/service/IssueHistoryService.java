package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssueEventType;
import ru.taska.domain.entity.IssueHistory;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Сервис для взаимодействия с историческими данными задач.
 */
public interface IssueHistoryService {

    /**
     * Создает {@link IssueHistory} и сохраняет в БД исторические данные при создании задачи.
     *
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issue     измененная задача
     * @return Mono<{@link IssueHistory}> исторические данные
     */
    Mono<IssueHistory> saveIssueCreateHistory(
            String requestId,
            String nodeId,
            Issue issue
    );

    /**
     * Создает {@link IssueHistory} и сохраняет в БД исторические данные при модификации задачи.
     *
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issueId   измененная задача
     * @param payload   фактические изменения
     * @return Mono<{@link IssueHistory}> исторические данные
     */
    Mono<IssueHistory> saveIssueHistory(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            IssueEventType type,
            JsonNode payload
    );

    /**
     * Возвращает историю задачи
     * @param requestId айди запроса
     * @param nodeId    айди узла
     * @param issueId   айди задачи
     * @return Flux<{@link IssueHistory}> исторические данные
     */
    Flux<IssueHistory> getHistory(String requestId, String nodeId, UUID issueId);
}
