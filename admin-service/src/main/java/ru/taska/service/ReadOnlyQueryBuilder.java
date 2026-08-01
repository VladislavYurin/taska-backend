package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.FilterOperators;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Строит БЕЗОПАСНЫЕ SQL запросы с параметризацией.
 *
 * Принцип работы:
 * 1. Проверяет, что таблица в allowlist (из конфига коллеги)
 * 2. Проверяет, что колонки для сортировки/фильтров безопасны
 * 3. Строит SQL с ПЛЕЙСХОЛДЕРАМИ ($1, $2, ...)
 * 4. Собирает значения для плейсхолдеров в отдельный список
 *
 * Пример:
 *   Вход: serviceKey="user-service", tableName="users", filters={status="active"}
 *   Выход: SqlQuery(sql="SELECT * FROM users WHERE status = $1", params=["active"])
 */

@Component
@RequiredArgsConstructor
public class ReadOnlyQueryBuilder {

    private final MetadataCatalogProperties properties;

    public record SqlQuery(
            String sql, // SQL с плейсхолдерами
            List<Object> params // значения для плейсхолдеров
    ){}

    public SqlQuery buildSafeQuery(ListTableRowsRequest request) {
        /// Извлекаем параметры
        String serviceKey = request.getBody().getServiceKey();
        String tableName = request.getBody().getTableName();
        int page = request.getBody().getPage();
        int pageSize = request.getBody().getPageSize();
        String sort = request.getBody().getSort();
        String order = request.getBody().getOrder();

        Map<String, FilterOperators> filters = request.getBody().getFiltersMap();

        /// Проверка: существует ли сервис (По идее всегда на фронте будет действительный список сервисов)
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            throw new IllegalArgumentException("Service not found: " + serviceKey);
        }

        /// Проверка: таблица в allowlist?
        var tableProps = serviceProps.tables();
        boolean isAllowed = tableProps.allow().isEmpty() || tableProps.allow().contains(tableName);
        boolean isDenied = tableProps.deny().contains(tableName);
        if (!isAllowed || isDenied) {
            throw new IllegalArgumentException("Table not accessible: " + tableName);
        }

        // 6. Строим БЕЗОПАСНЫЙ SQL (с плейсхолдерами!) (filters включает equals, from/to, contains)
        String sql = buildSafeSql(tableName, page, pageSize, sort, order, filters);
        List<Object> params = buildParams(filters);

        return new SqlQuery(sql, params);
    }

// Для понимания логики работы buildSafeSql
//      Запрос от клиента
//            serviceKey = "user-service"
//            tableName = "users"
//            page = 1
//            pageSize = 20
//            sort = "created_at"
//            order = "desc"
//            filters = {
//                    "status": "active",
//                    "email": "john@gmail.com"
//                    }

    private String buildSafeSql(
            String tableName,
            int page,
            int pageSize,
            String sort,
            String order,
            Map<String, FilterOperators> filters
    ) {
        StringBuilder sql = new StringBuilder();
        // 1. SELECT
        sql.append("SELECT * FROM ").append(tableName);
                // Промежуточная строка "SELECT * FROM users"

        // 2. WHERE (с плейсхолдерами и операторами)
        if (!filters.isEmpty()) {
            sql.append(" WHERE ");
            List<String> conditions = buildConditions(filters);
            sql.append(String.join(" AND ", conditions));
        }
                // Промежуточная строка "SELECT * FROM users WHERE status = $1 AND email = $2"\

        // 3. ORDER BY
        if (sort != null && !sort.isEmpty()) {
            if (!sort.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                throw new IllegalArgumentException("Invalid sort column: " + sort);
            }
            sql.append(" ORDER BY ").append(sort);
            if (order != null && !order.isEmpty()) {
                String orderUpper = order.toUpperCase();
                if (orderUpper.equals("ASC") || orderUpper.equals("DESC")) {
                    sql.append(" ").append(order);
                }
                else {
                    sql.append(" ASC");  // default
                }
            }
        }
                // Промежуточная строка "SELECT * FROM users WHERE status = $1 AND email = $2 ORDER BY created_at desc"

        // 4. LIMIT и OFFSET (пагинация)
        int offset = (page - 1) * pageSize;
        sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);
                // Промежуточная строка "SELECT * FROM users WHERE status = $1 AND email = $2 ORDER BY created_at desc LIMIT 20 OFFSET 0"

        return sql.toString();
    }

    /**
     * Строит COUNT запрос для подсчета общего количества записей.
     *
     * Используется для пагинации: totalPages, hasNext, hasPrev
     *
     * @param tableName имя таблицы
     * @param filters фильтры (те же, что и в SELECT)
     * @return SqlQuery - COUNT SQL с плейсхолдерами и параметрами
     */
    public SqlQuery buildSafeCountQuery(String tableName, Map<String, FilterOperators> filters) {
        // 1. Строим COUNT SQL
        // Пример: "SELECT COUNT(*) FROM users WHERE status = $1"
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(tableName);

        // 2. WHERE (с плейсхолдерами) - строим в
        if (!filters.isEmpty()) {
            sql.append(" WHERE ");
            List<String> conditions = buildConditions(filters);
            sql.append(String.join(" AND ", conditions));
        }
        List<Object> params = buildParams(filters);

        return new SqlQuery(sql.toString(), params);
    }

    /**
     * Строит WHERE-условия с плейсхолдерами на основе фильтров.
     * Используется в SELECT и COUNT запросах.
     *
     * @param filters фильтры с операторами
     * @return список условий (например, ["status = $1", "email ILIKE $2"])
     */
    private List<String> buildConditions(Map<String, FilterOperators> filters) {
        int paramIndex = 1; // начальный индекс для плейсхолдеров
        List<String> conditions = new ArrayList<>();

        for (Map.Entry<String, FilterOperators> entry : filters.entrySet()) {
            String column = entry.getKey();
            FilterOperators filter = entry.getValue();

            // Экранируем колонку от зарезервированных слов
            String quotedColumn = "\"" + column + "\"";

            ///equals → column = $1
            if (filter.hasEquals()) {
                conditions.add(quotedColumn + " = $" + paramIndex++);
            }
            ///contains → column ILIKE $1 (регистронезависимый)
            if (filter.hasContains()) {
                conditions.add(quotedColumn + " ILIKE $" + paramIndex++ + " ESCAPE '\\'");
            }
            ///from → column >= $1
            if (filter.hasFrom()) {
                conditions.add(quotedColumn + " >= $" + paramIndex++ + "::timestamptz"); // CAST для timestamp with timeZone
            }
            ///to → column <= $1
            if (filter.hasTo()) {
                conditions.add(quotedColumn + " <= $" + paramIndex++ + "::timestamptz"); // CAST для timestamp with timeZone
            }
        }
        return conditions;
    }

    /**
    * Собирает значения фильтров в список для плейсхолдеров.
    *
    * Пример:
    *   filters = { "status": "active", "email": "john@gmail.com" }
    *   → ["active", "john@gmail.com"]
    */
    private List<Object> buildParams(Map<String, FilterOperators> filters) {
        List<Object> params = new ArrayList<>();

        for (FilterOperators filterOps : filters.values()) {
            if (filterOps.hasEquals()) {
                params.add(filterOps.getEquals());
            }
            if (filterOps.hasContains()) {
                String value = filterOps.getContains();
                // Экранируем специальные символы LIKE
                value = value.replace("\\", "\\\\")  // экранируем обратный слеш
                        .replace("%", "\\%")    // экранируем %
                        .replace("_", "\\_");   // экранируем _
                // Для contains добавляем % вокруг значения
                params.add("%" + value + "%");
            }
            if (filterOps.hasFrom()) {
                params.add(filterOps.getFrom());
            }
            if (filterOps.hasTo()) {
                params.add(filterOps.getTo());
            }
        }

        return params;
    }
}
