package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Настройки мониторинга outbox-событий.
 *
 * @param processingTimeouts   допустимое время нахождения события в статусе PROCESSING для каждого сервиса;
 *                              превышение означает, что событие зависло
 * @param overdueNewThresholds допустимое время нахождения события в статусе NEW для каждого сервиса;
 *                              превышение означает, что событие не было взято в обработку
 * @param services             список service-key сервисов, имеющих таблицу outbox_events
 * @param maxProblematicListSize максимальное количество проблемных событий в ответе
 */
@ConfigurationProperties(prefix = "admin.outbox")
public record OutboxProcessingProperties(
        Map<String, Duration> processingTimeouts,
        Map<String, Duration> overdueNewThresholds,
        List<String> services,
        int maxProblematicListSize
) {
}
