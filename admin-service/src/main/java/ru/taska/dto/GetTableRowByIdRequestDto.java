package ru.taska.dto;

public record GetTableRowByIdRequestDto(
        String serviceKey,
        String tableName,
        String id
) {}
