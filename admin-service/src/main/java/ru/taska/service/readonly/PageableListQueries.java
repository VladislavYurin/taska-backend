package ru.taska.service.readonly;

/**
 * Пара запросов для постраничного просмотра: SELECT (данные) и COUNT (общее количество).
 */
public record PageableListQueries(
        SqlQuery selectQuery,
        SqlQuery countQuery
) {
}
