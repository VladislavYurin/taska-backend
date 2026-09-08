package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки ограниченных write-подключений admin-service
 * к outbox-таблицам сервисных баз данных.
 * <p>
 * Эти подключения используются только для административных операций,
 * явно разрешающих изменение технического состояния outbox-событий.
 * Они не должны использоваться для generic CRUD или изменения
 * бизнес-данных сервисов.
 *
 * @param auth    настройки подключения к БД auth-service
 * @param project настройки подключения к БД project-service
 * @param issue   настройки подключения к БД issue-service
 */
@ConfigurationProperties(prefix = "admin.outbox-write")
public record OutboxWriteDatasourcesProperties(
        DatasourceProperties auth,
        DatasourceProperties project,
        DatasourceProperties issue
) {

    /**
     * Настройки одного подключения к сервисной БД.
     *
     * @param url      R2DBC URL базы данных
     * @param username пользователь базы данных
     * @param password пароль пользователя базы данных
     * @param pool     параметры пула соединений
     */
    public record DatasourceProperties(
            String url,
            String username,
            String password,
            PoolProperties pool
    ) {
    }

    /**
     * Настройки пула R2DBC-соединений.
     *
     * @param initialSize        начальное количество соединений
     * @param maxSize            максимальное количество соединений
     * @param maxIdleTimeMinutes максимальное время простоя соединения в минутах
     */
    public record PoolProperties(
            int initialSize,
            int maxSize,
            int maxIdleTimeMinutes
    ) {
    }
}