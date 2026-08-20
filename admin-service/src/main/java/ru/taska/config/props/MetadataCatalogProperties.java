package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * Конфигурация содержимого каталога метаданных, публикуемого admin-service.
 */
@ConfigurationProperties(prefix = "admin.metadata")
public record MetadataCatalogProperties(
        PaginationProperties pagination,
        MaskType defaultMaskType,
        Map<String, ServiceProperties> services
) {

    /**
     * Настройки пагинации для read-only API.
     */
    public record PaginationProperties(
            int defaultPage,
            int defaultPageSize,
            int maxPageSize
    ) {
    }

    /**
     * Настройки отображения каталога для одного сервиса.
     */
    public record ServiceProperties(
            String alias,
            @DefaultValue("taska") String schema,
            @DefaultValue TableProperties tables
    ) {
    }

    /**
     * @param allow            если список непустой — в каталог попадают только эти таблицы;
     *                         пустой список означает «разрешены все»
     * @param deny             таблицы, скрытые из каталога; имеет приоритет над {@code allow}
     * @param sensitiveColumns колонки в формате table.column → тип маскировки (MASK_FULL, MASK_PARTIAL, HIDE);
     *                         если тип не указан (null/пустая строка), используется {@code defaultMaskType}
     */
    public record TableProperties(
            @DefaultValue List<String> allow,
            @DefaultValue List<String> deny,
            @DefaultValue Map<String, String> sensitiveColumns
    ) {
    }
}
