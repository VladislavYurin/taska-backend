package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.kafka")
public record KafkaProperties(
        Topics topics,
        Outbox outbox
) {
    public record Topics(
            String userEvents
    ) {
    }

    public record Outbox(
            Duration pollingInterval,
            Duration recoveryInterval,
            int batchSize,
            int maxAttempts,
            Duration processingTimeout
    ) {
    }
}