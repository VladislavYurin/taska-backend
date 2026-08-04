package ru.taska.dto;

import java.util.Map;

public record ListTableRowsRequestDto(
        String serviceKey,
        String tableName,
        int page,
        int pageSize,
        String sort,
        String order,
        Map<String, FilterOperatorsDto> filters
) {}
