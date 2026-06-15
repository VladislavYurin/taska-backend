package ru.taska.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox")
public record OutboxConfig(
        String topic,
        int batchSize,
        int maxAttempts,
        int schedulerDelay,
        int processingTimeoutSeconds
) {}
