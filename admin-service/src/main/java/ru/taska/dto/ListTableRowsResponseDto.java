package ru.taska.dto;

import java.util.List;
import java.util.Map;

public record ListTableRowsResponseDto(
        List<Map<String, Object>> maskedRows,
        Long total,
        int page,
        int pageSize,
        List<String> columns,
        String serviceKey,
        String tableName
) {}
