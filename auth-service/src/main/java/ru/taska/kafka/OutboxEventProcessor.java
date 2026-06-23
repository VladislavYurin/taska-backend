package ru.taska.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.KafkaProperties;
import ru.taska.entity.OutboxEvent;
import ru.taska.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.Objects;

/**
 * Сервис обработки outbox событий.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher publisher;
    private final KafkaProperties properties;

    /**
     * Обрабатывает очередную пачку событий transactional outbox.
     *
     * <p>Берет события из таблицы outbox в БД и публикует последовательно в порядке получения.</p>
     */
    public Mono<Void> processOutboxEvents() {
        return Mono.defer(() -> {
            log.debug("Processing outbox events with batch size: {}", properties.outbox().batchSize());
        return outboxEventRepository.findUnpublished(properties.outbox().batchSize())
                .filter(Objects::nonNull)
                .concatMap(this::processEvent)
                .then();
        });
    }

    /**
     * Обрабатывает застрявшие события (в статусе 'PROCESSING') дольше указанного времени.
     */
    public Mono<Void> processStuckEvents() {
        return Mono.defer(() -> {
                    Instant threshold = Instant.now()
                            .minusSeconds(properties.outbox().processingTimeout().getSeconds());

                    return outboxEventRepository.resetStuckProcessingEvents(threshold)
                            .doOnSuccess(count -> {
                                if (count != null && count > 0) {
                                    log.warn("Recovered stuck outbox events: count={}", count);
                                } else {
                                    log.debug("No stuck outbox events detected");
                                }
                            });
                })
                .then();
    }

    /**
     * Публикует событие в Kafka и помечает его как успешно обработанное.
     *
     * <p>В случае ошибки запускает механизм обработки неудачной публикации.</p>
     */
    private Mono<Void> processEvent(OutboxEvent event) {
        return publisher.publish(event)
                .then(Mono.defer(() ->
                        outboxEventRepository.markAsPublished(event.getId(), Instant.now()).then())
                )
                .doOnSuccess(rows ->
                        log.info("Successfully published event: id={}", event.getId())
                )
                .onErrorResume(ex -> handleProcessingError(event, ex));
    }

    /**
     * Обрабатывает ошибку публикации события.
     *
     * <p>Если количество попыток достигло максимально допустимого значения,
     * событие переводится в состояние 'FAILED'.
     * Иначе увеличивается счётчик неудачных попыток.</p>
     */
    private Mono<Void> handleProcessingError(OutboxEvent event, Throwable ex) {
        log.warn("Failed to publish event: id={}, aggregateId={}, eventType={}",
                event.getId(), event.getAggregateId(), event.getEventType(), ex);

        int currentAttempts = event.getAttempts() == null ? 0 : event.getAttempts();
        int nextAttempt = currentAttempts + 1;

        if (nextAttempt >= properties.outbox().maxAttempts()) {
            return outboxEventRepository.markAsFailed(event.getId(), ex.getMessage())
                    .doOnSuccess(rows ->
                            log.error("Event marked as FAILED: id={}, attempts={}",
                                    event.getId(), nextAttempt)
                    )
                    .then();
        }

        return outboxEventRepository.incrementAttempts(event.getId(), ex.getMessage())
                .doOnSuccess(rows ->
                        log.debug("Retry scheduled for event: id={}, attempts={}/{}",
                                event.getId(), nextAttempt, properties.outbox().maxAttempts())
                )
                .then();
    }
}
