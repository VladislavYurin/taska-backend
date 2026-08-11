package ru.taska.dto;

import java.util.Map;

public record GetTableRowByIdResponseDto(
        Map<String, Object> row
) {}
