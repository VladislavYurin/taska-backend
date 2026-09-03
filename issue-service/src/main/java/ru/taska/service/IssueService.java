package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.api.issue.v1.IssueBoardResponse;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.PageResult;
import ru.taska.domain.dto.labels.IssueWithLabels;

import java.util.List;
import java.util.UUID;

/**
 * Сервис для управления задачами.
 */
public interface IssueService {

    Mono<Issue> createIssue(
            String requestId,
            String nodeId,
            String idempotencyKey,
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
            UUID issueId,
            UUID assigneeId,
            UUID actorUserId
    );


    Mono<IssueWithHistory> getIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

    Mono<PageResult<IssueWithLabels>> listIssues(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID actorUserId,
            String status,
            UUID assigneeId,
            UUID labelId,
            Integer page,
            Integer pageSize
    );

    /**
     * * Производит мягкое удаление задачи по айди задачи, устанавливая значение
     * в поле deleted_at. После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param requestId   айди запроса.
     * @param nodeId      айди узла.
     * @param issueId     айди удаляемой задачи.
     * @param actorUserId айди юзера, удаляющего задачу.
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    Mono<Issue> deleteIssue(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );

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
                            IssuePriority priority
    );

    Mono<PageResult<Issue>> searchIssues(
            String requestId,
            String nodeId,
            UUID actorUserId,
            String query,
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            Integer page,
            Integer pageSize
    );

    /**
     * Возвращает список задач для доски.
     * @param requestId         айди запроса
     * @param nodeId            айди узла
     * @param actorUserId       айди изменяющего юзера
     * @param projectId         айди проекта
     * @param statusKey         статус задачи
     * @param assigneeId        айди исполнителя задачи
     * @param issueType         тип задачи
     * @param includeDone       маркер включения выполненных задач в ответ
     * @param labelIds          список айди меток
     * @param pageSizePerColumn количество задач на одну колонку
     * @return                  Mono с готовыми к ответу представлениями задач для доски
     */
    Mono<List<IssueBoardResponse>> listIssueBoard(
            String requestId,
            String nodeId,
            UUID actorUserId,
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            IssueType issueType,
            boolean includeDone,
            List<UUID> labelIds,
            Integer pageSizePerColumn
    );
}
