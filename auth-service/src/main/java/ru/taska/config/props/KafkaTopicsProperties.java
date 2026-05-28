package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka")
public record KafkaTopicsProperties(
        Topics topics,
        Outbox outbox
) {
    public record Topics(
            String userEvents
    ) {
    }

    public record Outbox(
            long pollingInterval,
            long recoveryInterval,
            int batchSize,
            int maxAttempts,
            long processingTimeoutSeconds
    ) {
    }
}