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
import ru.taska.mapper.IssueMapper;
import ru.taska.repository.criteria.SearchCriteria;
import ru.taska.repository.builder.SearchQueryBuilder;
import ru.taska.repository.executor.SearchQueryExecutor;

import java.util.List;
import java.util.UUID;

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
}
