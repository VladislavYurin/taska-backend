package ru.taska.domain;

/**
 * Одна строка из information_schema.columns.
 * Внутреннее представление, наружу не отдаётся.
 */
public record ColumnMetadata(
        String tableName,
        String columnName,
        String dataType
) {
}
