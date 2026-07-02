package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import ru.taska.config.props.KafkaProperties;

import java.time.Duration;

/**
 *
 */
@Configuration
public class AuthOutboxConfig implements OutboxConfig {
    private final KafkaProperties properties;

    public AuthOutboxConfig(KafkaProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getTopic() {
        return properties.topics().userEvents();
    }

    @Override
    public Duration getPollingInterval() {
        return properties.outbox().pollingInterval();
    }

    @Override
    public Duration getRecoveryInterval() {
        return properties.outbox().recoveryInterval();
    }

    @Override
    public int getBatchSize() {
        return properties.outbox().batchSize();
    }

    @Override
    public int getMaxAttempts() {
        return properties.outbox().maxAttempts();
    }

    @Override
    public Duration getProcessingTimeout() {
        return properties.outbox().processingTimeout();
    }
}
