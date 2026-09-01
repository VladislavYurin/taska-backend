package ru.taska.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IssueRepositoryCustom {

    Flux<Issue> findByFilter(UUID projectId, String status, UUID assigneeId, int limit, long offset);

    Mono<Long> countByFilter(UUID projectId, String status, UUID assigneeId);

    /**
     * Поиск задач с фильтрацией и полнотекстовым поиском.
     *
     * @param projectId   идентификатор проекта (может быть null)
     * @param statusKey   статус задачи (может быть null)
     * @param assigneeId  идентификатор исполнителя (может быть null)
     * @param reporterId  идентификатор создателя (может быть null)
     * @param priority    приоритет (может быть null)
     * @param issueType   тип задачи (может быть null)
     * @param searchQuery поисковый запрос (может быть null)
     * @param limit       размер страницы
     * @param offset      смещение
     * @return Flux<Issue> список найденных задач
     */
    Flux<Issue> searchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery,
            int limit,
            long offset
    );

    /**
     * Подсчёт количества задач, соответствующих фильтрам поиска.
     */
    Mono<Long> countSearchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery
    );

    /**
     * Для поиска задач, по нескольким определённым проектам ()
     */
    Flux<Issue> searchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery,
            int limit,
            long offset
    );

    /**
     * Подсчёт количества задач, по нескольким определённым проектам ()
     */
    Mono<Long> countSearchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery
    );

    /**
     * Возвращает задачи проекта для отображения на доске.
     */

    Flux<Issue> findForBoard(
            UUID projectId,
            IssueType issueType,
            UUID assigneeId,
            String statusKey,
            boolean includeDone,
            List<UUID> labelIds,
            Integer pageSizePerColumn
    );

    /**
     * Батчево находит идентификаторы меток для списка задач, сгруппированные по issueId.
     * Задачи без меток в карте отсутствуют (не N+1 — один запрос на весь список issueIds).
     */
    Mono<Map<UUID, List<UUID>>> findLabelIdsByIssueIds(List<UUID> issueIds);

    /**
     * Подсчет количества наблюдателей задач по списку их айди.
     */
    Mono<Map<UUID, Long>> countWatchersByIssueIds(List<UUID> issueIds);

    /**
     * Подсчет количества комментариев к задачам по списку их айди.
     */
    Mono<Map<UUID, Long>> countCommentsByIssueIds(List<UUID> issueIds);
}
