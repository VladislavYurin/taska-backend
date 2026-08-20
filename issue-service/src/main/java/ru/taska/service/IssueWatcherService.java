package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.IssueWatcher;
import ru.taska.domain.PageResult;
import ru.taska.domain.dto.IssueWatchStateDto;
import ru.taska.domain.dto.UnwatchIssueResult;
import ru.taska.domain.dto.WatchIssueResult;

import java.util.UUID;

/**
 * Сервис для работы с подписчиками (watchers) задачи.
 */
public interface IssueWatcherService {

    /**
     * Подписывает пользователя на задачу; повторный вызов не создаёт дубликат.
     */
    Mono<WatchIssueResult> watchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            UUID targetUserId
    );

    /**
     * Отписывает пользователя от задачи; повторный вызов не считается ошибкой.
     */
    Mono<UnwatchIssueResult> unwatchIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            UUID targetUserId
    );

    /**
     * Возвращает страницу подписчиков задачи.
     */
    Mono<PageResult<IssueWatcher>> listIssueWatchers(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            Integer page,
            Integer pageSize
    );

    /**
     * Возвращает watchedByMe и watchersCount для текущего пользователя.
     */
    Mono<IssueWatchStateDto> getIssueWatchState(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

    /**
     * Возвращает состояние подписки без проверки прав (для enrichment уже авторизованного GetIssue).
     */
    Mono<IssueWatchStateDto> getWatchState(UUID issueId, UUID actorUserId);
}
