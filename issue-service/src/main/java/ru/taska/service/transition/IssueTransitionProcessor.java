package ru.taska.service.transition;

import reactor.core.publisher.Mono;
import ru.taska.domain.IssueWithHistory;

import java.util.UUID;

/**
 * Сервис для управления переходом задачи по workflow.
 */
public interface IssueTransitionProcessor {

    /**
     * Выполняет переход задачи по workflow.
     *
     * @param requestId    ID запроса
     * @param nodeId       ID узла
     * @param issueId      ID задачи
     * @param transitionId ID перехода по workflow
     * @param actorUserId  ID пользователя, инициировавшего запрос
     * @param payload      дополнительные данные для перехода
     * @return Mono<{@link IssueWithHistory}> включающим задачу с обновленным статусом,
     * а так же историю изменений
     */
    Mono<IssueWithHistory> transitionIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID transitionId,
            UUID actorUserId,
            String payload
    );
}
