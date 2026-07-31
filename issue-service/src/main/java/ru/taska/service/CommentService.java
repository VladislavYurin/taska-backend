package ru.taska.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueComment;
import ru.taska.domain.PageResult;

import java.util.UUID;

/**
 * Сервис для управления комментариями к задачам.
 */
public interface CommentService {

    /**
     * Добавляет комментарий к задаче.
     *
     * @param requestId    ID запроса
     * @param nodeId       ID узла
     * @param issueId      ID задачи
     * @param authorUserId ID автора
     * @param body         текст комментария
     * @return Mono с созданным комментарием
     */
    Mono<IssueComment> addComment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID authorUserId,
            String body
    );

    /**
     * Обновляет комментарий.
     *
     * @param requestId      ID запроса
     * @param nodeId         ID узла
     * @param issueId        ID задачи
     * @param commentId      ID комментария
     * @param actorUserId    ID пользователя
     * @param body           новый текст комментария
     * @return Mono с обновлённым комментарием
     */
    Mono<IssueComment> updateComment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID commentId,
            UUID actorUserId,
            String body
    );

    /**
     * Удаляет комментарий (мягкое удаление).
     *
     * @param requestId   ID запроса
     * @param nodeId      ID узла
     * @param issueId     ID задачи
     * @param commentId   ID комментария
     * @param actorUserId ID пользователя
     * @return Mono с удалённым комментарием
     */
    Mono<IssueComment> deleteComment(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID commentId,
            UUID actorUserId
    );

    /**
     * Получает список комментариев к задаче.
     *
     * @param requestId    ID запроса
     * @param nodeId       ID узла
     * @param issueId      ID задачи
     * @param actorUserId  ID пользователя
     * @param page         номер страницы
     * @param pageSize     размер страницы
     * @return Mono с результатом пагинации
     */
    Mono<PageResult<IssueComment>> listComments(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId,
            Integer page,
            Integer pageSize
    );
}