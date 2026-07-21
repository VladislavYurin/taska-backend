package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки read-only подключений admin-service к БД остальных сервисов.
 * Свой datasource admin-db остаётся в {@code spring.r2dbc.*}.
 */
@ConfigurationProperties(prefix = "admin.readonly")
public record ReadonlyDatasourcesProperties(
        DatasourceProperties auth,
        DatasourceProperties project,
        DatasourceProperties issue,
        DatasourceProperties workflow,
        DatasourceProperties notification
) {

    public record DatasourceProperties(
            String url,
            String username,
            String password,
            @DefaultValue PoolProperties pool
    ) {
    }

    public record PoolProperties(
            @DefaultValue("2") int initialSize,
            @DefaultValue("10") int maxSize,
            @DefaultValue("10") int maxIdleTimeMinutes
    ) {
    }
}
