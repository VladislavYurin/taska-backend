package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import ru.taska.config.props.KafkaTopicsProperties;

import java.time.Duration;

@Configuration
public class IssueOutboxConfig implements OutboxConfig {
    private final KafkaTopicsProperties properties;

    public IssueOutboxConfig(KafkaTopicsProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getTopic() {
        return properties.topics().issueEvents();
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
