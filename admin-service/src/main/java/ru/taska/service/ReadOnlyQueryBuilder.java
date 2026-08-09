package ru.taska.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    public record SqlQuery(
            String sql, // SQL с плейсхолдерами
            List<Object> params // значения для плейсхолдеров
    ){}

    public SqlQuery buildSafeQuery(
            String serviceKey,
            String tableName,
            int page,
            int pageSize,
            String sort,
            String order,
            Map<String, FilterOperatorsDto> filters
    ) {

        /// Проверка: валидны ли page и pageSize
        validatePagination(page, pageSize);

        /// Проверка: существует ли сервис (По идее всегда на фронте будет действительный список сервисов)
        var serviceProps = properties.services().get(serviceKey);
        if (serviceProps == null) {
            throw new DomainException(DomainStatus.NOT_FOUND,"Service not found: " + serviceKey);
        }

        /// Проверка: таблица в allowlist?
        var tableProps = serviceProps.tables();
        boolean isAllowed = tableProps.allow().isEmpty() || tableProps.allow().contains(tableName);
        boolean isDenied = tableProps.deny().contains(tableName);
        if (!isAllowed || isDenied) {
            throw new DomainException(DomainStatus.PERMISSION_DENIED,"Table not accessible: " + tableName);
        }

        /// Проверка: колонки для фильтров безопасны?
        for (String filterKey : filters.keySet()) {
            if (!VALID_PATTERN.matcher(filterKey).matches()) {
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid filter column: " + filterKey);
            }
        }

        /// Строим БЕЗОПАСНЫЙ SQL (с плейсхолдерами!) (filters включает equals, from/to, contains)
        String sql = buildSafeSql(tableName, page, pageSize, sort, order, filters);
        List<Object> params = buildParams(filters);

        return new SqlQuery(sql, params);
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
    public SqlQuery buildSafeCountQuery(String tableName, Map<String, FilterOperatorsDto> filters) {
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
            Map<String, FilterOperatorsDto> filters
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
            if (!VALID_PATTERN.matcher(sort).matches()) {
                throw new DomainException(DomainStatus.INVALID_ARGUMENT,"Invalid sort column: " + sort);
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
     * Строит WHERE-условия с плейсхолдерами на основе фильтров.
     * Используется в SELECT и COUNT запросах.
     *
     * @param filters фильтры с операторами
     * @return список условий (например, ["status = $1", "email ILIKE $2"])
     */
    private List<String> buildConditions(Map<String, FilterOperatorsDto> filters) {
        int paramIndex = 1; // начальный индекс для плейсхолдеров
        List<String> conditions = new ArrayList<>();

        for (var entry : filters.entrySet()) {
            String column = entry.getKey();
            FilterOperatorsDto filter = entry.getValue();

            // Экранируем колонку от зарезервированных слов
            String quotedColumn = "\"" + column + "\"";

            ///equals → column = $1
            if (filter.equals() != null && !filter.equals().isEmpty()) {
                conditions.add(quotedColumn + " = $" + paramIndex++);
            }
            ///contains → column ILIKE $1 (регистронезависимый)
            if (filter.contains() != null &&! filter.contains().isEmpty()) {
                conditions.add(quotedColumn + " ILIKE $" + paramIndex++ + " ESCAPE '\\'");
            }
            ///from → column >= $1
            if (filter.from() != null && !filter.from().isEmpty()) {
                conditions.add(quotedColumn + " >= $" + paramIndex++ + "::timestamptz"); // CAST для timestamp with timeZone
            }
            ///to → column <= $1
            if (filter.to() != null && !filter.to().isEmpty()) {
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
    private List<Object> buildParams(Map<String, FilterOperatorsDto> filters) {
        List<Object> params = new ArrayList<>();

        for (FilterOperatorsDto filterOps : filters.values()) {
            if (filterOps.equals() != null && !filterOps.equals().isEmpty()) {
                params.add(filterOps.equals());
            }
            if (filterOps.contains() != null && !filterOps.contains().isEmpty()) {
                String value = filterOps.contains();
                // Экранируем специальные символы LIKE
                value = value.replace("\\", "\\\\")  // экранируем обратный слеш
                        .replace("%", "\\%")    // экранируем %
                        .replace("_", "\\_");   // экранируем _
                // Для contains добавляем % вокруг значения
                params.add("%" + value + "%");
            }
            if (filterOps.from() != null && !filterOps.from().isEmpty()) {
                params.add(filterOps.from());
            }
            if (filterOps.to() != null && !filterOps.to().isEmpty()) {
                params.add(filterOps.to());
            }
        }

        return params;
    }

    /**
     * Валидация входящих page и pageSize
     * @param page
     * @param pageSize
     */
    private void validatePagination(int page, int pageSize) {
        if (page < 1) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Page must be >= 1");
        }
        if (pageSize < 1) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "PageSize must be >= 1");
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "PageSize must be <= " + MAX_PAGE_SIZE);
        }
    }
}
