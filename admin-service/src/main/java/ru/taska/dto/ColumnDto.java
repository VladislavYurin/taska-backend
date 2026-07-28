package ru.taska.dto;

/**
 * DTO колонки таблицы в каталоге метаданных.
 */
public record ColumnDto(
        String name,
        String type,
        boolean primaryKey,
        boolean sensitive
) {
}
