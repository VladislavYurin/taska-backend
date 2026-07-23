package ru.taska.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

@Configuration
public class ReadonlyDbHealthConfig {

    @Bean(name = "authDb")
    ReactiveHealthIndicator authDbHealthIndicator(
            @Qualifier("authReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return () -> ping(connectionFactory);
    }

    @Bean(name = "projectDb")
    ReactiveHealthIndicator projectDbHealthIndicator(
            @Qualifier("projectReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return () -> ping(connectionFactory);
    }

    @Bean(name = "issueDb")
    ReactiveHealthIndicator issueDbHealthIndicator(
            @Qualifier("issueReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return () -> ping(connectionFactory);
    }

    @Bean(name = "workflowDb")
    ReactiveHealthIndicator workflowDbHealthIndicator(
            @Qualifier("workflowReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return () -> ping(connectionFactory);
    }

    @Bean(name = "notificationDb")
    ReactiveHealthIndicator notificationDbHealthIndicator(
            @Qualifier("notificationReadonlyConnectionFactory") ConnectionFactory connectionFactory
    ) {
        return () -> ping(connectionFactory);
    }

    private static Mono<Health> ping(ConnectionFactory connectionFactory) {
        return DatabaseClient.create(connectionFactory)
                .sql("SELECT 1")
                .fetch()
                .first()
                .thenReturn(Health.up().build())
                .onErrorResume(ex -> Mono.just(Health.down(ex).build()));
    }
}
