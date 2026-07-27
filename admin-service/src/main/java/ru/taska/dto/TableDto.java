package ru.taska.dto;

import java.util.List;

/**
 * DTO таблицы и её колонок в каталоге метаданных.
 */
public record TableDto(
        String name,
        List<ColumnDto> columns
) {
}
