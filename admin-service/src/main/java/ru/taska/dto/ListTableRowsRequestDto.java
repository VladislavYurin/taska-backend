package ru.taska.dto;

import java.util.Map;

public record ListTableRowsRequestDto(
        String serviceKey,
        String tableName,
        Integer page,
        Integer pageSize,
        String sort,
        String order,
        Map<String, String> filters
) {}
