package ru.taska.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.config.props.ReadonlyDatasourcesProperties;
import ru.taska.config.props.ReadonlyDatasourcesProperties.DatasourceProperties;

import java.time.Duration;

import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

/**
 * Отдельные pooled {@link ConnectionFactory} для read-only доступа к сервисным БД.
 * Не помечаем beans как {@code @Primary}, чтобы не пересечься со своим {@code spring.r2dbc}.
 */
@Configuration
@EnableConfigurationProperties(ReadonlyDatasourcesProperties.class)
public class ReadonlyR2dbcConfig {

    @Bean(name = "authReadonlyConnectionFactory")
    ConnectionFactory authReadonlyConnectionFactory(ReadonlyDatasourcesProperties properties) {
        return createPooledConnectionFactory(properties.auth());
    }

    @Bean(name = "projectReadonlyConnectionFactory")
    ConnectionFactory projectReadonlyConnectionFactory(ReadonlyDatasourcesProperties properties) {
        return createPooledConnectionFactory(properties.project());
    }

    @Bean(name = "issueReadonlyConnectionFactory")
    ConnectionFactory issueReadonlyConnectionFactory(ReadonlyDatasourcesProperties properties) {
        return createPooledConnectionFactory(properties.issue());
    }

    @Bean(name = "workflowReadonlyConnectionFactory")
    ConnectionFactory workflowReadonlyConnectionFactory(ReadonlyDatasourcesProperties properties) {
        return createPooledConnectionFactory(properties.workflow());
    }

    @Bean(name = "notificationReadonlyConnectionFactory")
    ConnectionFactory notificationReadonlyConnectionFactory(ReadonlyDatasourcesProperties properties) {
        return createPooledConnectionFactory(properties.notification());
    }

    @Bean(name = "authReadonlyDatabaseClient")
    DatabaseClient authReadonlyDatabaseClient(
            @Qualifier("authReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean(name = "projectReadonlyDatabaseClient")
    DatabaseClient projectReadonlyDatabaseClient(
            @Qualifier("projectReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean(name = "issueReadonlyDatabaseClient")
    DatabaseClient issueReadonlyDatabaseClient(
            @Qualifier("issueReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean(name = "workflowReadonlyDatabaseClient")
    DatabaseClient workflowReadonlyDatabaseClient(
            @Qualifier("workflowReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean(name = "notificationReadonlyDatabaseClient")
    DatabaseClient notificationReadonlyDatabaseClient(
            @Qualifier("notificationReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return DatabaseClient.create(connectionFactory);
    }

    private static ConnectionFactory createPooledConnectionFactory(DatasourceProperties properties) {
        ConnectionFactoryOptions baseOptions = ConnectionFactoryOptions.parse(properties.url());
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .from(baseOptions)
                .option(USER, properties.username())
                .option(PASSWORD, properties.password())
                .build();

        ConnectionFactory connectionFactory = io.r2dbc.spi.ConnectionFactories.get(options);

        ConnectionPoolConfiguration poolConfiguration = ConnectionPoolConfiguration.builder(connectionFactory)
                .name("admin-ro-" + properties.username())
                .initialSize(properties.pool().initialSize())
                .maxSize(properties.pool().maxSize())
                .maxIdleTime(Duration.ofMinutes(properties.pool().maxIdleTimeMinutes()))
                .build();

        return new ConnectionPool(poolConfiguration);
    }
}
