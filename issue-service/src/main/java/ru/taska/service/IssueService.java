package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.PageResult;

import java.util.UUID;

/**
 * Сервис для управления задачами.
 */
public interface IssueService {

    Mono<Issue> createIssue(
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    );

    Mono<Issue> assignIssue(UUID issueId, UUID assigneeId, UUID actorUserId);


    Mono<IssueWithHistory> getIssue(UUID issueId);

    Mono<PageResult<Issue>> listIssues(UUID projectId, IssueStatus status, UUID assigneeId, Integer page, Integer pageSize);

    /**
     ** Производит мягкое удаление задачи по айди задачи, устанавливая значение
     * в поле deleted_at. После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param requestId айди запроса.
     * @param nodeId айди узла.
     * @param issueId айди удаляемой задачи.
     * @param actorUserId айди юзера, удаляющего задачу.
     *
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    /**
     * Обновляет задачу на основе данных из Mono<{@link ru.taska.api.issue.v1.UpdateIssueRequest}>
     * Получает данные из header (requestId, nodeId) для логирования
     * и issueId, actorUserId, summary, description, priority для создания проекта.
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param issueId     айди изменяемой задачи
     * @param actorUserId айди изменяющего юзера
     * @param summary     короткие описание задачи
     * @param description полное описание задачи
     * @param priority    обновленный приоритет задачи
     * @return Mono<{@link Issue}> с соответствующими параметрами обновленного проекта
     */
    Mono<Issue> updateIssue(String requestId,
                            String nodeId,
                            UUID issueId,
                            UUID actorUserId,
                            String summary,
                            String description,
                            IssuePriority priority);

    /**
     * Мягко удаляет задачу на основе данных из Mono<{@link ru.taska.api.issue.v1.DeleteIssueRequest}>
     * При удалении, устанавливается поле deletedAt, задача остается в БД, но не отображается при вызове
     * GetIssue или ListIssues.
     * Получает данные из header (requestId, nodeId) для логирования
     * и issueId, actorUserId для удаления задачи
     *
     * @param requestId   айди запроса
     * @param nodeId      айди узла
     * @param issueId     айди удаляемой задачи
     * @param actorUserId айди удаляющего задачу юзера
     * @return Mono<{@link Issue}> с соответствующими параметрами удаленного проекта
     */
    Mono<Issue> deleteIssue(String requestId, String nodeId, UUID issueId, UUID actorUserId);
}
