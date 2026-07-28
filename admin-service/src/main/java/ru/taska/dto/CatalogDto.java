package ru.taska.dto;

import java.util.List;

/**
 * Корневой DTO каталога метаданных, возвращаемый admin-service.
 */
public record CatalogDto(
        List<ServiceDto> services
) {
}
