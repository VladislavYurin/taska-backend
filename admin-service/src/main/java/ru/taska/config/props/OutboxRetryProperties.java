package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки ручного retry outbox-событий.
 *
 * @param stuckThreshold время, после которого событие
 *                       в статусе PROCESSING считается зависшим
 */
@ConfigurationProperties(prefix = "admin.outbox-retry")
public record OutboxRetryProperties(
        Duration stuckThreshold
) {
}