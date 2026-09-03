package ru.taska.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.config.props.OutboxRetryProperties;
import ru.taska.config.props.OutboxWriteDatasourcesProperties;
import ru.taska.config.props.OutboxWriteDatasourcesProperties.DatasourceProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Конфигурация ограниченных write-подключений admin-service
 * к outbox-таблицам сервисных баз данных.
 * <p>
 * Подключения из этой конфигурации должны использоваться только
 * специализированными административными операциями над outbox.
 * Generic write-доступ к бизнес-таблицам сервисов через них
 * не предусмотрен.
 */
@Configuration
@EnableConfigurationProperties({
        OutboxWriteDatasourcesProperties.class,
        OutboxRetryProperties.class
})
public class OutboxWriteR2dbcConfig {

    private static final String AUTH_SERVICE_KEY = "auth";
    private static final String PROJECT_SERVICE_KEY = "project";
    private static final String ISSUE_SERVICE_KEY = "issue";

    /**
     * Создаёт write-подключение к БД auth-service.
     *
     * @param properties настройки write datasource
     * @return connection factory для auth-service
     */
    @Bean(name = "authOutboxWriteConnectionFactory")
    ConnectionFactory authOutboxWriteConnectionFactory(
            OutboxWriteDatasourcesProperties properties
    ) {
        return createPooledConnectionFactory(properties.auth());
    }

    /**
     * Создаёт write-подключение к БД project-service.
     *
     * @param properties настройки write datasource
     * @return connection factory для project-service
     */
    @Bean(name = "projectOutboxWriteConnectionFactory")
    ConnectionFactory projectOutboxWriteConnectionFactory(
            OutboxWriteDatasourcesProperties properties
    ) {
        return createPooledConnectionFactory(properties.project());
    }

    /**
     * Создаёт write-подключение к БД issue-service.
     *
     * @param properties настройки write datasource
     * @return connection factory для issue-service
     */
    @Bean(name = "issueOutboxWriteConnectionFactory")
    ConnectionFactory issueOutboxWriteConnectionFactory(
            OutboxWriteDatasourcesProperties properties
    ) {
        return createPooledConnectionFactory(properties.issue());
    }

    /**
     * Создаёт DatabaseClient для auth-service.
     *
     * @param connectionFactory write connection factory auth-service
     * @return DatabaseClient для выполнения ограниченных outbox-операций
     */
    @Bean(name = "authOutboxWriteDatabaseClient")
    DatabaseClient authOutboxWriteDatabaseClient(
            @Qualifier("authOutboxWriteConnectionFactory")
            ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    /**
     * Создаёт DatabaseClient для project-service.
     *
     * @param connectionFactory write connection factory project-service
     * @return DatabaseClient для выполнения ограниченных outbox-операций
     */
    @Bean(name = "projectOutboxWriteDatabaseClient")
    DatabaseClient projectOutboxWriteDatabaseClient(
            @Qualifier("projectOutboxWriteConnectionFactory")
            ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    /**
     * Создаёт DatabaseClient для issue-service.
     *
     * @param connectionFactory write connection factory issue-service
     * @return DatabaseClient для выполнения ограниченных outbox-операций
     */
    @Bean(name = "issueOutboxWriteDatabaseClient")
    DatabaseClient issueOutboxWriteDatabaseClient(
            @Qualifier("issueOutboxWriteConnectionFactory")
            ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    /**
     * Собирает write DatabaseClient по имени сервиса.
     *
     * @param auth    client auth-service
     * @param project client project-service
     * @param issue   client issue-service
     * @return map сервис → write DatabaseClient
     */
    @Bean(name = "outboxWriteDatabaseClients")
    Map<String, DatabaseClient> outboxWriteDatabaseClients(
            @Qualifier("authOutboxWriteDatabaseClient") DatabaseClient auth,
            @Qualifier("projectOutboxWriteDatabaseClient") DatabaseClient project,
            @Qualifier("issueOutboxWriteDatabaseClient") DatabaseClient issue
    ) {
        return Map.of(
                AUTH_SERVICE_KEY, auth,
                PROJECT_SERVICE_KEY, project,
                ISSUE_SERVICE_KEY, issue
        );
    }

    /**
     * Создаёт pooled R2DBC connection factory для service DB.
     *
     * @param properties настройки datasource
     * @return pooled connection factory
     */
    private static ConnectionFactory createPooledConnectionFactory(
            DatasourceProperties properties
    ) {
        ConnectionFactoryOptions baseOptions =
                ConnectionFactoryOptions.parse(properties.url());

        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .from(baseOptions)
                .option(ConnectionFactoryOptions.USER, properties.username())
                .option(ConnectionFactoryOptions.PASSWORD, properties.password())
                .build();

        ConnectionFactory connectionFactory =
                ConnectionFactories.get(options);

        ConnectionPoolConfiguration poolConfiguration =
                ConnectionPoolConfiguration.builder(connectionFactory)
                        .name("admin-outbox-write-" + properties.username())
                        .initialSize(properties.pool().initialSize())
                        .maxSize(properties.pool().maxSize())
                        .maxIdleTime(
                                Duration.ofMinutes(
                                        properties.pool().maxIdleTimeMinutes()
                                )
                        )
                        .build();

        return new ConnectionPool(poolConfiguration);
    }
}
