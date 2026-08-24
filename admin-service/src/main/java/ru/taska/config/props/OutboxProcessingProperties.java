package ru.taska.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Настройки мониторинга outbox-событий.
 *
 * @param processingTimeouts   порог «застрявшести» в статусе PROCESSING для каждого сервиса
 * @param overdueNewThresholds порог «застрявшести» в статусе NEW для каждого сервиса
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
