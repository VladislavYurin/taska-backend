package ru.taska.transport.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик обработки outbox событий.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventScheduler {

    private final OutboxEventProcessor processor;

    /**
     * Запускает публикацию outbox событий.
     */
    @Scheduled(fixedDelayString = "${app.kafka.outbox.polling-interval}")
    public void publishOutboxEvents() {
        processor.processOutboxEvents()
                .doOnSuccess(unused -> log.trace("Outbox scheduler iteration completed successfully"))
                .doOnError(ex -> log.error("Outbox scheduler iteration failed: {}", ex.getMessage()))
                .subscribe();
    }

    /**
     * Запускает обработку застрявших событий.
     */
    @Scheduled(fixedDelayString = "${app.kafka.outbox.recovery-interval}")
    public void recoverStuckEvents() {
        processor.processStuckEvents()
                .doOnSuccess(unused -> log.trace("Recovery scheduler iteration completed successfully"))
                .doOnError(ex -> log.error("Failed to recover stuck events: {}", ex.getMessage()))
                .subscribe();
    }

}
