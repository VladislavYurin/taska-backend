package ru.taska.domain;

import java.util.Map;
import java.util.Set;

/**
 * Категория типа колонки БД.
 * Определяет, какие операторы фильтрации допустимы для колонки.
 */
public enum DbColumnType {

    TEXT,
    TEMPORAL,
    NUMERIC,
    BOOLEAN,
    OTHER;

    private static final Map<String, DbColumnType> PG_TYPE_MAP = Map.ofEntries(
            Map.entry("text", TEXT),
            Map.entry("character varying", TEXT),
            Map.entry("varchar", TEXT),
            Map.entry("character", TEXT),
            Map.entry("char", TEXT),
            Map.entry("name", TEXT),
            Map.entry("citext", TEXT),

            Map.entry("timestamp with time zone", TEMPORAL),
            Map.entry("timestamp without time zone", TEMPORAL),
            Map.entry("date", TEMPORAL),
            Map.entry("time with time zone", TEMPORAL),
            Map.entry("time without time zone", TEMPORAL),

            Map.entry("integer", NUMERIC),
            Map.entry("bigint", NUMERIC),
            Map.entry("smallint", NUMERIC),
            Map.entry("numeric", NUMERIC),
            Map.entry("decimal", NUMERIC),
            Map.entry("real", NUMERIC),
            Map.entry("double precision", NUMERIC),
            Map.entry("serial", NUMERIC),
            Map.entry("bigserial", NUMERIC),
            Map.entry("smallserial", NUMERIC),

            Map.entry("boolean", BOOLEAN)
    );

    private static final Set<DbColumnType> CONTAINS_COMPATIBLE = Set.of(TEXT);
    private static final Set<DbColumnType> RANGE_COMPATIBLE = Set.of(TEMPORAL, NUMERIC);

    /**
     * Определяет категорию по имени типа из PostgreSQL information_schema.
     */
    public static DbColumnType fromPgType(String pgType) {
        if (pgType == null) {
            return OTHER;
        }
        return PG_TYPE_MAP.getOrDefault(pgType.toLowerCase(), OTHER);
    }

    public boolean supportsContains() {
        return CONTAINS_COMPATIBLE.contains(this);
    }

    public boolean supportsRange() {
        return RANGE_COMPATIBLE.contains(this);
    }
}
