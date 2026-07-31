package ru.taska.domain;

/**
 * Одна строка из выборки primary key колонок в {@code information_schema}.
 */
public record PrimaryKeyMetadata(
        String tableName,
        String columnName
) {
}
