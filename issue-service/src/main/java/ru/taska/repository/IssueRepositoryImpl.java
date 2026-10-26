package ru.taska.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import ru.taska.domain.IssueType;
import ru.taska.filter.CriteriaRenderer;
import ru.taska.filter.Filter;
import ru.taska.filter.FilterBuilder;
import ru.taska.filter.SqlRenderer;
import ru.taska.filter.SqlWhere;
import ru.taska.mapper.IssueMapper;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class IssueRepositoryImpl implements IssueRepositoryCustom {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final DatabaseClient databaseClient;
    private final IssueMapper issueMapper;

    @Override
    public Flux<Issue> findByFilter(UUID projectId, String statusKey, UUID assigneeId, int limit, long offset) {
        Query query = Query.query(buildCriteria(projectId, statusKey, assigneeId))
                .sort(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit(limit)
                .offset(offset);
        return r2dbcEntityTemplate.select(query, Issue.class);
    }

    @Override
    public Mono<Long> countByFilter(UUID projectId, String statusKey, UUID assigneeId) {
        return r2dbcEntityTemplate.count(
                Query.query(buildCriteria(projectId, statusKey, assigneeId)),
                Issue.class
        );
    }

    /**
     * Метка проверяется подзапросом, а не JOIN с {@code issue_labels}: так в запросе одна таблица
     * и колонки фильтра не нужно уточнять алиасом. Пара (задача, метка) уникальна, поэтому
     * результат тот же, что у JOIN.
     */
    @Override
    public Flux<Issue> findByLabelIdWithFilters(
            UUID projectId,
            UUID labelId,
            String statusKey,
            UUID assigneeId,
            int limit,
            long offset
    ) {
        SqlWhere where = SqlRenderer.render(listIssuesFilter(projectId, statusKey, assigneeId));

        String sql = """
                SELECT * FROM taska.issues
                WHERE %s
                  AND id IN (SELECT issue_id FROM taska.issue_labels WHERE label_id = :labelId)
                ORDER BY created_at DESC
                LIMIT :limit OFFSET :offset
                """.formatted(where.sql());

        return where.bindTo(databaseClient.sql(sql))
                .bind("labelId", labelId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(issueMapper::mapRowToIssue)
                .all();
    }

    @Override
    public Mono<Long> countByLabelIdWithFilters(UUID projectId, UUID labelId, String statusKey, UUID assigneeId) {
        SqlWhere where = SqlRenderer.render(listIssuesFilter(projectId, statusKey, assigneeId));

        String sql = """
                SELECT COUNT(*) FROM taska.issues
                WHERE %s
                  AND id IN (SELECT issue_id FROM taska.issue_labels WHERE label_id = :labelId)
                """.formatted(where.sql());

        return where.bindTo(databaseClient.sql(sql))
                .bind("labelId", labelId)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    // ==================== Поиск issues с фильтрами ====================

    @Override
    public Flux<Issue> searchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            String searchQuery,
            int limit,
            long offset
    ) {
        Filter filter = searchFilter(statusKey, assigneeId, reporterId, priority, issueType, searchQuery)
                .eq("project_id", projectId)
                .build();
        return search(filter, limit, offset);
    }

    @Override
    public Mono<Long> countSearchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            String searchQuery
    ) {
        Filter filter = searchFilter(statusKey, assigneeId, reporterId, priority, issueType, searchQuery)
                .eq("project_id", projectId)
                .build();
        return count(filter);
    }

    @Override
    public Flux<Issue> searchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            String searchQuery,
            int limit,
            long offset
    ) {
        Filter filter = searchFilter(statusKey, assigneeId, reporterId, priority, issueType, searchQuery)
                .in("project_id", projectIds)
                .build();
        return search(filter, limit, offset);
    }

    @Override
    public Mono<Long> countSearchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            String searchQuery
    ) {
        Filter filter = searchFilter(statusKey, assigneeId, reporterId, priority, issueType, searchQuery)
                .in("project_id", projectIds)
                .build();
        return count(filter);
    }

    private Criteria buildCriteria(UUID projectId, String statusKey, UUID assigneeId) {
        return CriteriaRenderer.render(listIssuesFilter(projectId, statusKey, assigneeId));
    }

    /**
     * Условия списка задач проекта — общие для выборки с меткой и без неё,
     * чтобы фильтры по статусу и исполнителю в них не расходились.
     */
    private Filter listIssuesFilter(UUID projectId, String statusKey, UUID assigneeId) {
        return FilterBuilder.create()
                .eqRequired("project_id", projectId)
                .isNull("deleted_at")
                // Пустой статус не снимает фильтр (в отличие от eq), а даёт пустой результат, как в случае с поиском по меткам
                .when(statusKey != null, b -> b.in("status_key", List.of(statusKey)))
                .eq("assignee_id", assigneeId)
                .build();
    }

    /**
     * Общие условия поиска задач без фильтра по проекту: его добавляет вызывающий метод
     * (один проект или список доступных проектов). Текст ищется без учёта регистра
     * в ключе, названии и описании задачи.
     */
    private FilterBuilder searchFilter(
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            IssuePriority priority,
            IssueType issueType,
            String searchQuery
    ) {
        return FilterBuilder.create()
                .isNull("deleted_at")
                .eq("status_key", statusKey)
                .eq("assignee_id", assigneeId)
                .eq("reporter_id", reporterId)
                .eq("priority", priority)
                .eq("issue_type", issueType == null ? null : issueType.name())
                .eq("priority", priority)
                .anyOf(any -> any
                        .containsIgnoreCase("issue_key", searchQuery)
                        .containsIgnoreCase("summary", searchQuery)
                        .containsIgnoreCase("description", searchQuery));
    }

    /** Страница результатов поиска: сначала новые задачи, при равном времени создания — по убыванию id. */
    private Flux<Issue> search(Filter filter, int limit, long offset) {
        if (filter.matchesNothing()) {
            return Flux.empty();
        }
        Query query = Query.query(CriteriaRenderer.render(filter))
                .sort(Sort.by(Sort.Direction.DESC, "created_at", "id"))
                .limit(limit)
                .offset(offset);
        return r2dbcEntityTemplate.select(query, Issue.class);
    }

    private Mono<Long> count(Filter filter) {
        if (filter.matchesNothing()) {
            return Mono.just(0L);
        }
        return r2dbcEntityTemplate.count(Query.query(CriteriaRenderer.render(filter)), Issue.class);
    }

    // ==================== Окончание поиска issues с фильтрами ====================

    /**
     * Реализация board-запроса из {@link IssueRepositoryCustom}. Непустой {@code labelIds}
     * сначала отдельным запросом резолвится в множество id задач, помеченных этими метками,
     * и подставляется в фильтр; {@code null} или пустой {@code labelIds} означает «фильтр по
     * меткам не применяется», а непустой список, которому не соответствует ни одна задача,
     * даёт пустой результат, а не молчаливо снятый фильтр. При {@code null}
     * {@code pageSizePerColumn} выборка не ограничивается и собирается через Criteria API;
     * при заданном значении выполняется сырой SQL с оконной функцией, оставляющей не более
     * N задач в каждой колонке доски (по {@code status_key}). Оба пути строятся из одного
     * {@link Filter}, поэтому набор условий в них не расходится.
     */
    @Override
    public Flux<Issue> findForBoard(
            UUID projectId,
            IssueType issueType,
            UUID assigneeId,
            String statusKey,
            boolean includeDone,
            List<String> doneStatusKeys,
            List<UUID> labelIds,
            Integer pageSizePerColumn,
            IssuePriority priority,
            String query
    ) {
        return resolveLabelFilterIssueIds(labelIds)
                .map(issueIds -> boardFilter(
                        projectId, issueType, assigneeId, statusKey, includeDone, doneStatusKeys,
                        issueIds.orElse(null), priority, query
                ))
                .flatMapMany(filter -> {
                    if (filter.matchesNothing()) {
                        return Flux.empty();
                    }
                    return pageSizePerColumn != null
                            ? findForBoardLimitedPerColumn(filter, pageSizePerColumn)
                            : r2dbcEntityTemplate.select(buildBoardCriteriaQuery(filter), Issue.class);
                });
    }

    /**
     * Резолвит {@code labelIds} в список id задач, помеченных хотя бы одной из этих меток.
     * Для {@code null}/пустого {@code labelIds} фильтр по меткам неактивен и возвращается
     * {@link Optional#empty()}; пустой список внутри означает «метки заданы, но совпадений нет».
     */
    private Mono<Optional<List<UUID>>> resolveLabelFilterIssueIds(List<UUID> labelIds) {
        if (labelIds == null || labelIds.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return r2dbcEntityTemplate.getDatabaseClient()
                .sql("SELECT DISTINCT issue_id FROM taska.issue_labels WHERE label_id IN (:labelIds)")
                .bind("labelIds", labelIds)
                .map(row -> row.get("issue_id", UUID.class))
                .all()
                .collectList()
                .map(Optional::of);
    }

    /**
     * Criteria-запрос для доски (путь без {@code pageSizePerColumn}): условия из
     * {@code filter} плюс сортировка по колонке ({@code status_key}) и дате создания.
     */
    private Query buildBoardCriteriaQuery(Filter filter) {
        return Query.query(CriteriaRenderer.render(filter))
                .sort(Sort.by(Sort.Direction.ASC, "status_key", "created_at"));
    }

    /**
     * Находит задачи для доски с ограничением количества задач в каждой колонке (status_key),
     * а не в результате целиком. Criteria API/{@code .limit()} режет весь результат,
     * поэтому здесь используется оконная функция ROW_NUMBER() с PARTITION BY status_key.
     */
    private Flux<Issue> findForBoardLimitedPerColumn(Filter filter, int pageSizePerColumn) {
        SqlWhere where = SqlRenderer.render(filter);

        String sql = """
                SELECT * FROM (
                    SELECT *,
                           ROW_NUMBER() OVER (PARTITION BY status_key ORDER BY created_at ASC) AS rn
                    FROM taska.issues
                    WHERE %s
                ) t
                WHERE rn <= :pageSizePerColumn
                ORDER BY status_key ASC, created_at ASC
                """.formatted(where.sql());

        return where.bindTo(r2dbcEntityTemplate.getDatabaseClient().sql(sql))
                .bind("pageSizePerColumn", pageSizePerColumn)
                .map(issueMapper::mapRowToIssue)
                .all();
    }

    @Override
    public Mono<Map<UUID, List<UUID>>> findLabelIdsByIssueIds(List<UUID> issueIds) {
        if (issueIds == null || issueIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return r2dbcEntityTemplate.getDatabaseClient()
                .sql("SELECT issue_id, label_id FROM taska.issue_labels WHERE issue_id IN (:issueIds)")
                .bind("issueIds", issueIds)
                .map((row, meta) -> new AbstractMap.SimpleEntry<>(
                        row.get("issue_id", UUID.class),
                        row.get("label_id", UUID.class)
                ))
                .all()
                .collectMultimap(Map.Entry::getKey, Map.Entry::getValue)
                .map(multimap -> multimap.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
    }
    @Override
    public Mono<Map<UUID, Long>> countWatchersByIssueIds(List<UUID> issueIds) {
        if (issueIds == null || issueIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return r2dbcEntityTemplate.getDatabaseClient()
                .sql("SELECT issue_id, COUNT(*) AS cnt FROM taska.issue_watchers WHERE issue_id IN (:issueIds) GROUP BY issue_id")
                .bind("issueIds", issueIds)
                .map((row, meta) -> new AbstractMap.SimpleEntry<>(
                        row.get("issue_id", UUID.class),
                        row.get("cnt", Long.class)
                ))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    @Override
    public Mono<Map<UUID, Long>> countCommentsByIssueIds(List<UUID> issueIds){
        if (issueIds == null || issueIds.isEmpty()){
            return Mono.just(Map.of());
        }

        return r2dbcEntityTemplate.getDatabaseClient()
                .sql("SELECT issue_id, COUNT(*) AS cnt FROM taska.issue_comments WHERE issue_id IN (:issueIds) AND deleted_at IS NULL GROUP BY issue_id")
                .bind("issueIds", issueIds)
                .map((row, meta) -> new AbstractMap.SimpleEntry<>(
                        row.get("issue_id", UUID.class),
                        row.get("cnt", Long.class)
                ))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    @Override
    public Mono<Map<UUID, Long>> countAttachmentsByIssueIds(List<UUID> issueIds){
        if (issueIds == null || issueIds.isEmpty()){
            return Mono.just(Map.of());
        }

        return r2dbcEntityTemplate.getDatabaseClient()
                .sql("SELECT issue_id, COUNT(*) AS cnt FROM taska.issue_attachments WHERE issue_id IN (:issueIds) AND deleted_at IS NULL GROUP BY issue_id")
                .bind("issueIds", issueIds)
                .map((row, meta) -> new AbstractMap.SimpleEntry<>(
                        row.get("issue_id", UUID.class),
                        row.get("cnt", Long.class)
                ))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }


    /**
     * Фильтр задач для доски — общий источник как для Criteria API (путь без
     * {@code pageSizePerColumn}), так и для сырого SQL с оконной функцией (путь с
     * {@code pageSizePerColumn}). {@code labelFilterIssueIds}: {@code null} — фильтр по меткам
     * не задан, пустой список — метки заданы, но задач с ними нет, и фильтр ничего не пропускает.
     */
    private Filter boardFilter(
            UUID projectId,
            IssueType issueType,
            UUID assigneeId,
            String statusKey,
            boolean includeDone,
            List<String> doneStatusKeys,
            List<UUID> labelFilterIssueIds,
            IssuePriority priority,
            String query
    ) {
        return FilterBuilder.create()
                .eqRequired("project_id", projectId)
                .isNull("deleted_at")
                .eq("issue_type", issueType == null ? null : issueType.name())
                .eq("priority", priority)
                .eq("assignee_id", assigneeId)
                .eq("status_key", statusKey)
                .when(!includeDone, b -> b.notIn("status_key", doneStatusKeys))
                .inOrNone("id", labelFilterIssueIds)
                .anyOf(any -> any.containsIgnoreCase("issue_key", query)
                        .containsIgnoreCase("summary", query).containsIgnoreCase("description", query))
                .build();
    }
}
