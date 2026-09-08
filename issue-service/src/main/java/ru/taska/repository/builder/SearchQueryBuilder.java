package ru.taska.repository.builder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.repository.criteria.SearchCriteria;

import java.util.*;

@Component
public class SearchQueryBuilder {

    private static final String SELECT = "SELECT * FROM taska.issues WHERE 1=1";
    private static final String COUNT = "SELECT COUNT(*) as count FROM taska.issues WHERE 1=1";
    private static final String DELETED_CONDITION = " AND deleted_at IS NULL";
    private static final String ORDER_BY = " ORDER BY created_at DESC";

    public SearchQuery build(SearchCriteria criteria) {
        SearchQuery query = new SearchQuery();
        StringBuilder sql = new StringBuilder(criteria.isCount() ? COUNT : SELECT);

        sql.append(DELETED_CONDITION);

        // Добавляем фильтры
        addFilter(sql, query, "status_key", criteria.getStatusKey());
        addFilter(sql, query, "assignee_id", criteria.getAssigneeId());
        addFilter(sql, query, "reporter_id", criteria.getReporterId());
        addFilter(sql, query, "priority", criteria.getPriority());
        addFilter(sql, query, "issue_type", criteria.getIssueType());

        // ★ УНИВЕРСАЛЬНЫЙ ФИЛЬТР ПО ПРОЕКТУ ★
        addProjectFilter(sql, query, criteria);

        // Добавляем поисковый запрос
        addSearchCondition(sql, query, criteria.getSearchQuery());

        // Добавляем пагинацию
        if (!criteria.isCount()) {
            sql.append(ORDER_BY);
            sql.append(" LIMIT :limit OFFSET :offset");
            query.addParam("limit", criteria.getLimit());
            query.addParam("offset", criteria.getOffset());
        }

        query.setSql(sql.toString());
        return query;
    }

    // ★ УНИВЕРСАЛЬНЫЙ МЕТОД ДЛЯ ФИЛЬТРАЦИИ ПО ПРОЕКТУ ★
    private void addProjectFilter(StringBuilder sql, SearchQuery query, SearchCriteria criteria) {
        // Приоритет: если есть projectIds - используем IN
        if (criteria.getProjectIds() != null && !criteria.getProjectIds().isEmpty()) {
            addProjectIdsCondition(sql, query, criteria.getProjectIds());
        }
        // Если есть projectId - используем =
        else if (criteria.getProjectId() != null) {
            addFilter(sql, query, "project_id", criteria.getProjectId());
        }
        // Если ничего нет - не добавляем фильтр по проекту (поиск по всем проектам)
    }

    private <T> void addFilter(StringBuilder sql, SearchQuery query, String column, T value) {
        if (value != null && !(value instanceof String && ((String) value).isBlank())) {
            sql.append(" AND ").append(column).append(" = :").append(column);
            query.addParam(column, value);
        }
    }

    private void addSearchCondition(StringBuilder sql, SearchQuery query, String searchQuery) {
        if (searchQuery != null && !searchQuery.isBlank()) {
            sql.append(" AND (");
            sql.append(" issue_key ILIKE :searchQuery");
            sql.append(" OR summary ILIKE :searchQuery");
            sql.append(" OR description ILIKE :searchQuery");
            sql.append(" )");
            query.addParam("searchQuery", "%" + searchQuery + "%");
        }
    }

    private void addProjectIdsCondition(StringBuilder sql, SearchQuery query, List<UUID> projectIds) {
        if (projectIds != null && !projectIds.isEmpty()) {
            sql.append(" AND project_id IN (");
            for (int i = 0; i < projectIds.size(); i++) {
                if (i > 0) sql.append(",");
                String paramName = "projectId" + i;
                sql.append(":").append(paramName);
                query.addParam(paramName, projectIds.get(i));
            }
            sql.append(")");
        }
    }
}