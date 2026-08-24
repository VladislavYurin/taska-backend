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
import ru.taska.domain.Issue;
import ru.taska.domain.IssueType;
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.criteria.SearchCriteria;
import ru.taska.repository.builder.SearchQueryBuilder;
import ru.taska.repository.executor.SearchQueryExecutor;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class IssueRepositoryImpl implements IssueRepositoryCustom {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final DatabaseClient databaseClient;
    private final IssueMapper issueMapper;
    private final SearchQueryBuilder searchQueryBuilder;
    private final SearchQueryExecutor searchQueryExecutor;

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

    // ==================== Поиск issues с фильтрами ====================

    @Override
    public Flux<Issue> searchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery,
            int limit,
            long offset
    ) {
        SearchCriteria criteria = SearchCriteria.builder()
                .projectId(projectId)
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .priority(priority)
                .issueType(issueType)
                .searchQuery(searchQuery)
                .limit(limit)
                .offset(offset)
                .count(false)
                .build();

        return search(criteria);
    }

    @Override
    public Mono<Long> countSearchIssues(
            UUID projectId,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery
    ) {
        SearchCriteria criteria = SearchCriteria.builder()
                .projectId(projectId)
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .priority(priority)
                .issueType(issueType)
                .searchQuery(searchQuery)
                .count(true)
                .build();

        return count(criteria);
    }

    // ★ ЗАМЕНА ДЛЯ searchIssuesInProjects ★
    @Override
    public Flux<Issue> searchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery,
            int limit,
            long offset
    ) {
        SearchCriteria criteria = SearchCriteria.builder()
                .projectIds(projectIds)  // ← передаем список projectIds
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .priority(priority)
                .issueType(issueType)
                .searchQuery(searchQuery)
                .limit(limit)
                .offset(offset)
                .count(false)
                .build();

        return search(criteria);
    }

    @Override
    public Mono<Long> countSearchIssuesInProjects(
            List<UUID> projectIds,
            String statusKey,
            UUID assigneeId,
            UUID reporterId,
            String priority,
            String issueType,
            String searchQuery
    ) {
        SearchCriteria criteria = SearchCriteria.builder()
                .projectIds(projectIds)  // ← передаем список projectIds
                .statusKey(statusKey)
                .assigneeId(assigneeId)
                .reporterId(reporterId)
                .priority(priority)
                .issueType(issueType)
                .searchQuery(searchQuery)
                .count(true)
                .build();

        return count(criteria);
    }

    private Criteria buildCriteria(UUID projectId, String statusKey, UUID assigneeId) {
        Criteria criteria = Criteria.where("project_id").is(projectId)
                .and("deleted_at").isNull();

        if (statusKey != null) {
            criteria = criteria.and("status_key").is(statusKey);
        }
        if (assigneeId != null) {
            criteria = criteria.and("assignee_id").is(assigneeId);
        }

        return criteria;
    }

    /**
     * Универсальный метод поиска задач.
     * Поддерживает как один projectId, так и список projectIds.
     */
    private Flux<Issue> search(SearchCriteria criteria) {
        log.debug("Searching issues with criteria: {}", criteria);
        var query = searchQueryBuilder.build(criteria);
        return searchQueryExecutor.executeQuery(query);
    }

    /**
     * Универсальный метод подсчета задач.
     * Поддерживает как один projectId, так и список projectIds.
     */
    private Mono<Long> count(SearchCriteria criteria) {
        log.debug("Counting issues with criteria: {}", criteria);
        var query = searchQueryBuilder.build(criteria);
        return searchQueryExecutor.executeCount(query);
    }

    // ==================== Окончание поиска issues с фильтрами ====================

    @Override
    public Flux<Issue> findForBoard(
            UUID projectId,
            IssueType issueType,
            UUID assigneeId,
            String statusKey,
            boolean includeDone,
            List<UUID> labelIds,
            Integer pageSizePerColumn
    ) {
        boolean labelFilterActive = labelIds != null && !labelIds.isEmpty();

        Mono<List<UUID>> issueIdsMono;
        if (labelFilterActive) {
            issueIdsMono = r2dbcEntityTemplate.getDatabaseClient()
                    .sql("SELECT DISTINCT issue_id FROM taska.issue_labels WHERE label_id IN (:labelIds)")
                    .bind("labelIds", labelIds)
                    .map(row -> row.get("issue_id", UUID.class))
                    .all()
                    .collectList();
        }else {
            issueIdsMono = Mono.just(List.of());
        }

        return issueIdsMono.flatMapMany(issueIds -> {
                    if (labelFilterActive && issueIds.isEmpty()) {
                        return Flux.empty();
                    }

                    List<BoardFilterCondition> conditions = boardFilterConditions(
                            projectId, issueType, assigneeId, statusKey, includeDone,
                            labelFilterActive ? issueIds : null
                    );

                    if (pageSizePerColumn != null) {
                        return findForBoardLimitedPerColumn(conditions, pageSizePerColumn);
                    }

                    Query query = Query.query(toCriteria(conditions))
                            .sort(Sort.by(Sort.Direction.ASC, "status_key", "created_at"));

                    return r2dbcEntityTemplate.select(query, Issue.class);
                });

    }

    /**
     * Находит задачи для доски с ограничением количества задач в каждой колонке (status_key),
     * а не в результате целиком. Criteria API/{@code .limit()} режет весь результат,
     * поэтому здесь используется оконная функция ROW_NUMBER() с PARTITION BY status_key.
     */
    private Flux<Issue> findForBoardLimitedPerColumn(List<BoardFilterCondition> conditions, int pageSizePerColumn) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = toSqlWhere(conditions, params);
        params.put("pageSizePerColumn", pageSizePerColumn);

        String sql = """
                SELECT * FROM (
                    SELECT i.*,
                           ROW_NUMBER() OVER (PARTITION BY i.status_key ORDER BY i.created_at ASC) AS rn
                    FROM taska.issues i
                    WHERE %s
                ) t
                WHERE rn <= :pageSizePerColumn
                ORDER BY status_key ASC, created_at ASC
                """.formatted(where);

        DatabaseClient.GenericExecuteSpec spec = r2dbcEntityTemplate.getDatabaseClient().sql(sql);
        for (var entry : params.entrySet()) {
            spec = spec.bind(entry.getKey(), entry.getValue());
        }

        return spec.map(issueMapper::mapRowToIssue).all();
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

    /**
     * Единый список условий фильтрации задач для доски — общий источник как для Criteria API
     * (путь без {@code pageSizePerColumn}), так и для сырого SQL с оконной функцией
     * (путь с {@code pageSizePerColumn}). Раньше эти условия дублировались в двух местах вручную,
     * что легко было рассинхронизировать при добавлении нового фильтра в будущем.
     */
    private List<BoardFilterCondition> boardFilterConditions(
            UUID projectId,
            IssueType issueType,
            UUID assigneeId,
            String statusKey,
            boolean includeDone,
            List<UUID> labelFilterIssueIds
    ) {
        List<BoardFilterCondition> conditions = new ArrayList<>();
        conditions.add(new BoardFilterCondition("project_id", "projectId", BoardFilterOperator.EQUALS, projectId));
        conditions.add(new BoardFilterCondition("deleted_at", null, BoardFilterOperator.IS_NULL, null));
        if (issueType != null) {
            conditions.add(new BoardFilterCondition("issue_type", "issueType", BoardFilterOperator.EQUALS, issueType.name()));
        }
        if (assigneeId != null) {
            conditions.add(new BoardFilterCondition("assignee_id", "assigneeId", BoardFilterOperator.EQUALS, assigneeId));
        }
        if (statusKey != null) {
            conditions.add(new BoardFilterCondition("status_key", "statusKey", BoardFilterOperator.EQUALS, statusKey));
        }
        if (!includeDone) {
            conditions.add(new BoardFilterCondition("status_key", "excludedStatusKey", BoardFilterOperator.NOT_EQUALS, "DONE"));
        }
        if (labelFilterIssueIds != null) {
            conditions.add(new BoardFilterCondition("id", "labelFilterIssueIds", BoardFilterOperator.IN, labelFilterIssueIds));
        }
        return conditions;
    }

    private Criteria toCriteria(List<BoardFilterCondition> conditions) {
        List<Criteria> criteriaList = conditions.stream()
                .map(c -> switch (c.operator()) {
                    case EQUALS -> Criteria.where(c.column()).is(c.value());
                    case NOT_EQUALS -> Criteria.where(c.column()).not(c.value());
                    case IN -> Criteria.where(c.column()).in((Collection<?>) c.value());
                    case IS_NULL -> Criteria.where(c.column()).isNull();
                })
                .toList();
        return Criteria.from(criteriaList);
    }

    private String toSqlWhere(List<BoardFilterCondition> conditions, Map<String, Object> paramsOut) {
        return conditions.stream()
                .map(c -> {
                    String column = "i." + c.column();
                    return switch (c.operator()) {
                        case EQUALS -> {
                            paramsOut.put(c.paramName(), c.value());
                            yield column + " = :" + c.paramName();
                        }
                        case NOT_EQUALS -> {
                            paramsOut.put(c.paramName(), c.value());
                            yield column + " <> :" + c.paramName();
                        }
                        case IN -> {
                            paramsOut.put(c.paramName(), c.value());
                            yield column + " IN (:" + c.paramName() + ")";
                        }
                        case IS_NULL -> column + " IS NULL";
                    };
                })
                .collect(Collectors.joining(" AND "));
    }

    /**
     * Одно условие фильтрации board-запроса: колонка + оператор + значение.
     * {@code paramName} используется только для SQL-пути (именованный параметр); для Criteria-пути не нужен.
     */
    private record BoardFilterCondition(String column, String paramName, BoardFilterOperator operator, Object value) {
    }

    private enum BoardFilterOperator {
        EQUALS, NOT_EQUALS, IN, IS_NULL
    }
}
