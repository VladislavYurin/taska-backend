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
        Map<String, ServiceProperties> services
) {

    /**
     * Настройки отображения каталога для одного сервиса.
     */
    public record ServiceProperties(
            String alias,
            @DefaultValue TableProperties tables
    ) {
    }

    /**
     * @param allow            если список непустой — в каталог попадают только эти таблицы;
     *                         пустой список означает «разрешены все»
     * @param deny             таблицы, скрытые из каталога; имеет приоритет над {@code allow}
     * @param sensitiveColumns колонки, помечаемые флагом sensitive
     */
    public record TableProperties(
            @DefaultValue List<String> allow,
            @DefaultValue List<String> deny,
            @DefaultValue List<String> sensitiveColumns
    ) {
    }
}
