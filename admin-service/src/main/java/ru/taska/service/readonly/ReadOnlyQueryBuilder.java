package ru.taska.service.readonly;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import ru.taska.domain.DbColumnType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Строит БЕЗОПАСНЫЕ SQL запросы с параметризацией.
 *
 * Принцип работы:
 * 1. Валидирует запрос: существование сервиса, формат имени таблицы,
 *    allowlist/denylist (из конфига), формат колонок фильтров
 * 2. Проверяет, что колонка сортировки — безопасный SQL идентификатор
 * 3. Строит SQL с ПЛЕЙСХОЛДЕРАМИ ($1, $2, ...)
 * 4. Собирает значения для плейсхолдеров в отдельный список
 *
 * Фильтры всегда содержат явный оператор (equals, contains, from, to).
 * Если ни один оператор не задан — колонка не участвует в WHERE.
 *
 * Пример:
 *   Вход: serviceKey="user-service", tableName="users",
 *         filters={status: {equals: "active"}, email: {contains: "john"}}
 *   Выход: PageableListQueries(
 *            selectQuery=SqlQuery("SELECT * FROM users WHERE \"status\" = $1 AND \"email\" ILIKE $2 ESCAPE '\\'", ["active", "%john%"]),
 *            countQuery=SqlQuery("SELECT COUNT(*) FROM users WHERE \"status\" = $1 AND \"email\" ILIKE $2 ESCAPE '\\'", ["active", "%john%"]))
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class ReadOnlyQueryBuilder {

    private final ReadOnlyQueryValidator validator;

    private static final String ORDER_ASC = "ASC";
    private static final String ORDER_DESC = "DESC";


    public PageableListQueries buildSafePageableListQueries(
            String serviceKey,
            String schema,
            String tableName,
            int page,
            int pageSize,
            String sort,
            String order,
            Map<String, FilterOperatorsDto> filters,
            Map<String, DbColumnType> columnTypes,
            String primaryKeyColumn
    ) {

        log.debug("Building query for service='{}', table='{}', page={}, pageSize={}, sort='{}', order='{}'",
                serviceKey, tableName, page, pageSize, sort, order);

        validator.validateServiceAndTable(serviceKey, tableName);

        if (sort != null) {
            validator.validateColumn(sort, columnTypes, "sort");
        }
        for (String filterColumn : filters.keySet()) {
            validator.validateColumn(filterColumn, columnTypes, "filter");
        }
        validator.validateFilterOperatorCompatibility(filters, columnTypes);

        // WHERE и params строим за один проход для гарантии согласованности порядка плейсхолдеров и значений
        ConditionsAndParams conditionsAndParams = buildConditionsAndParams(filters, columnTypes);
        String whereClause = conditionsAndParams.conditions().isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", conditionsAndParams.conditions());
        List<Object> params = conditionsAndParams.params();

        String qualifiedTable = qualifyTable(schema, tableName);
        String selectSql = buildSelectSql(qualifiedTable, whereClause, page, pageSize, sort, order, primaryKeyColumn);
        String countSql = "SELECT COUNT(*) FROM " + qualifiedTable + whereClause;

        log.debug("Built SELECT query: {}", selectSql);
        log.debug("Built COUNT query: {}", countSql);

        return new PageableListQueries(
                new SqlQuery(selectSql, params),
                new SqlQuery(countSql, params)
        );
    }

    /**
     * Строит безопасный SELECT запрос для получения одной строки по primary key.
     *
     * @param serviceKey    ключ сервиса
     * @param tableName     имя таблицы
     * @param pkColumn      имя PK колонки
     * @param id            значение PK
     * @return SqlQuery с SELECT запросом и параметрами
     */
    public SqlQuery buildSafeGetByIdQuery(
            String serviceKey,
            String schema,
            String tableName,
            String pkColumn,
            String id
    ) {
        log.debug("Building getById query for service='{}', table='{}', pk='{}', id='{}'",
                serviceKey, tableName, pkColumn, id);

        validator.validateServiceAndTable(serviceKey, tableName);
        validator.checkThatStringIsSqlSafe(pkColumn);

        String selectSql = "SELECT * FROM " + qualifyTable(schema, tableName) + " WHERE \"" + pkColumn + "\"::text = $1";

        log.debug("Built getById query: {}", selectSql);

        return new SqlQuery(selectSql, List.of(id));
    }

    private String buildSelectSql(
            String qualifiedTable,
            String whereClause,
            int page,
            int pageSize,
            String sort,
            String order,
            String primaryKeyColumn
    ) {
        StringBuilder sql = new StringBuilder();
        // 1. SELECT + WHERE
        sql.append("SELECT * FROM ").append(qualifiedTable).append(whereClause);

        // 2. ORDER BY
        if (sort != null && !sort.isEmpty()) {
            sql.append(" ORDER BY \"").append(sort).append("\"");
            sql.append(" ").append(parseOrderDirection(order));
        } else {
            // Fallback на primary key: без ORDER BY PostgreSQL не гарантирует порядок строк,
            // что приводит к некорректной пагинации — строки могут дублироваться или теряться между страницами.
            sql.append(" ORDER BY \"").append(primaryKeyColumn).append("\" ASC");
        }

        // 3. LIMIT и OFFSET (пагинация)
        int offset = page * pageSize;
        sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append(offset);

        return sql.toString();
    }

    /**
     * Парсит направление сортировки.
     * При невалидном значении логирует предупреждение и возвращает ASC по умолчанию.
     */
    private String parseOrderDirection(String order) {
        if (order == null || order.isBlank()) {
            return ORDER_ASC;
        }

        String normalized = order.trim().toUpperCase();

        if (ORDER_ASC.equals(normalized) || ORDER_DESC.equals(normalized)) {
            return normalized;
        }

        throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                "Invalid order direction: '" + order + "'. Allowed values: ASC, DESC");
    }

    /**
     * Строит WHERE-условия с плейсхолдерами и собирает значения параметров за один проход.
     * Гарантирует, что порядок плейсхолдеров ($1, $2, ...) соответствует порядку значений в params.
     *
     * @param filters     фильтры с операторами
     * @param columnTypes типы колонок для определения способа парсинга значений from/to
     * @return условия и параметры
     */
    private ConditionsAndParams buildConditionsAndParams(
            Map<String, FilterOperatorsDto> filters,
            Map<String, DbColumnType> columnTypes
    ) {
        int paramIndex = 1;
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (var entry : filters.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String column = entry.getKey();
            FilterOperatorsDto filter = entry.getValue();

            // Экранируем колонку от зарезервированных слов
            String quotedColumn = "\"" + column + "\"";

            if (StringUtils.hasText(filter.equals())) {
                conditions.add(quotedColumn + " = $" + paramIndex++);
                params.add(parseEqualsValue(column, filter.equals(), columnTypes));
            }
            if (StringUtils.hasText(filter.contains())) {
                conditions.add(quotedColumn + " ILIKE $" + paramIndex++ + " ESCAPE '\\'");
                String value = filter.contains()
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
                params.add("%" + value + "%");
            }
            if (StringUtils.hasText(filter.from())) {
                conditions.add(quotedColumn + " >= $" + paramIndex++);
                params.add(parseRangeValue(column, filter.from(), columnTypes));
            }
            if (StringUtils.hasText(filter.to())) {
                conditions.add(quotedColumn + " <= $" + paramIndex++);
                params.add(parseRangeValue(column, filter.to(), columnTypes));
            }
        }

        return new ConditionsAndParams(conditions, params);
    }

    /**
     * Парсит значение для оператора equals в зависимости от типа колонки.
     * Для NUMERIC — BigDecimal, для BOOLEAN — Boolean, остальные — String.
     */
    private Object parseEqualsValue(String column, String value, Map<String, DbColumnType> columnTypes) {
        DbColumnType type = columnTypes.get(column);
        if (type == DbColumnType.NUMERIC) {
            return parseNumericValue(column, value);
        }
        if (type == DbColumnType.BOOLEAN) {
            return parseBooleanValue(column, value);
        }
        return value;
    }

    /**
     * Парсит значение для операторов from/to в зависимости от типа колонки.
     * Для TEMPORAL — OffsetDateTime, для NUMERIC — BigDecimal.
     */
    private Object parseRangeValue(String column, String value, Map<String, DbColumnType> columnTypes) {
        DbColumnType type = columnTypes.get(column);
        if (type == DbColumnType.NUMERIC) {
            return parseNumericValue(column, value);
        }
        if (type==DbColumnType.TEMPORAL) {
            return parseTemporalValue(column, value);
        }
        log.warn("Column of type {} does not support to/from operation", type);
        throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                "Invalid date format for column '" + column
                        + "': expected ISO-8601 (e.g. 2026-01-01T00:00:00Z), got '" + value + "'");
    }

    private OffsetDateTime parseTemporalValue(String column, String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            log.warn("Invalid date format for column '{}': expected ISO-8601, got '{}'", column, value);
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Invalid date format for column '" + column
                            + "': expected ISO-8601 (e.g. 2026-01-01T00:00:00Z), got '" + value + "'");
        }
    }

    private BigDecimal parseNumericValue(String column, String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid numeric value for column '{}': got '{}'", column, value);
            throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                    "Invalid numeric value for column '" + column + "': got '" + value + "'");
        }
    }

    private String qualifyTable(String schema, String tableName) {
        return "\"" + schema + "\".\"" + tableName + "\"";
    }

    private Boolean parseBooleanValue(String column, String value) {
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        log.warn("Invalid boolean value for column '{}': got '{}'", column, value);
        throw new DomainException(DomainStatus.INVALID_ARGUMENT,
                "Invalid boolean value for column '" + column
                        + "': expected 'true' or 'false', got '" + value + "'");
    }
}
