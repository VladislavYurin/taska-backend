package ru.taska.dto;

import java.util.List;

/**
 * DTO описания одного сервиса в каталоге метаданных.
 */
public record ServiceDto(
        String serviceKey,
        String alias,
        List<TableDto> tables
) {
}
