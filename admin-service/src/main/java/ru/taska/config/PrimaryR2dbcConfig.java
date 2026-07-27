package ru.taska.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.ReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.r2dbc.core.DatabaseClient;
import ru.taska.converter.JsonBToJsonNodeConverter;
import ru.taska.converter.JsonNodeToJsonBConverter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Конфигурация основного (Primary) R2DBC-подключения приложения.
 *
 * Регистрирует главенствующие бины DatabaseClient и R2dbcEntityTemplate для устранения
 * конфликтов при наличии нескольких ConnectionFactory, а также подключает кастомные
 * конвертеры (в т.ч. JsonNode <-> jsonb).
 */
@Configuration
@EnableConfigurationProperties(R2dbcProperties.class)
public class PrimaryR2dbcConfig {

    @Bean
    @Primary
    public ConnectionFactory connectionFactory(R2dbcProperties properties) {
        ConnectionFactoryOptions baseOptions = ConnectionFactoryOptions.parse(properties.getUrl());
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .from(baseOptions)
                .option(ConnectionFactoryOptions.USER, properties.getUsername())
                .option(ConnectionFactoryOptions.PASSWORD, properties.getPassword())
                .build();

        ConnectionFactory raw = ConnectionFactories.get(options);

        ConnectionPoolConfiguration poolConfig = ConnectionPoolConfiguration.builder(raw)
                .name("admin-primary")
                .initialSize(properties.getPool().getInitialSize())
                .maxSize(properties.getPool().getMaxSize())
                .maxIdleTime(properties.getPool().getMaxIdleTime())
                .build();

        return new ConnectionPool(poolConfig);
    }

    @Bean
    @Primary
    public DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ObjectMapper objectMapper) {
        return R2dbcCustomConversions.of(
                PostgresDialect.INSTANCE,
                List.of(
                        new JsonNodeToJsonBConverter(objectMapper),
                        new JsonBToJsonNodeConverter(objectMapper)
                )
        );
    }

    @Bean
    @Primary
    public R2dbcEntityTemplate r2dbcEntityTemplate(
            DatabaseClient databaseClient,
            R2dbcCustomConversions customConversions
    ) {
        R2dbcMappingContext mappingContext = new R2dbcMappingContext();
        mappingContext.setSimpleTypeHolder(customConversions.getSimpleTypeHolder());

        MappingR2dbcConverter converter = new MappingR2dbcConverter(mappingContext, customConversions);
        ReactiveDataAccessStrategy strategy = new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE, converter);

        return new R2dbcEntityTemplate(databaseClient, strategy);
    }
}
