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
            String requestId,
            String nodeId,
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    );

    Mono<Issue> assignIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID assigneeId,
            UUID actorUserId
    );


    Mono<IssueWithHistory> getIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId
    );

    Mono<PageResult<Issue>> listIssues(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            IssueStatus status,
            UUID assigneeId,
            Integer page,
            Integer pageSize
    );

    /**
     * * Производит мягкое удаление задачи по айди задачи, устанавливая значение
     * в поле deleted_at. После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param projectId   айди проекта.
     * @param issueId     айди удаляемой задачи.
     * @param actorUserId айди юзера, удаляющего задачу.
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    Mono<Issue> deleteIssue(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId
    );
}
