package ru.taska.transport.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.KafkaTopicsProperties;
import ru.taska.domain.OutboxEvent;
import ru.taska.repository.OutboxEventRepository;

import java.time.Instant;

/**
 * Сервис обработки outbox событий.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher publisher;
    private final KafkaTopicsProperties properties;

    /**
     * Обрабатывает очередную пачку событий transactional outbox.
     *
     * <p>Берет события из таблицы outbox в БД и публикует последовательно в порядке получения.</p>
     */
    public Mono<Void> processOutboxEvents() {
        return outboxEventRepository.findUnpublished(properties.outbox().batchSize())
                .concatMap(this::processEvent)
                .then();
    }

    /**
     * Обрабатывает застрявшие события (в статусе 'PROCESSING') дольше указанного времени.
     */
    public Mono<Void> processStuckEvents() {
        return Mono.defer(() -> {
                    Instant threshold = Instant.now()
                            .minusSeconds(properties.outbox().processingTimeout().toSeconds());

                    return outboxEventRepository.resetStuckProcessingEvents(threshold)
                            .doOnSuccess(count -> {
                                if (count != null && count > 0) {
                                    log.warn("Recovered stuck outbox events: count={}", count);
                                } else {
                                    log.trace("No stuck outbox events detected");
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
                        outboxEventRepository.markAsPublished(event.getId(), Instant.now()))
                )
                .doOnSuccess(rows ->
                        log.info("Successfully published event : id={}", event.getId())
                )
                .then()
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
        log.error("Failed to publish event: id={}, aggregateId={}, eventType={}",
                event.getId(), event.getAggregateId(), event.getEventType(), ex);

        int currentAttempts = event.getAttempts() == null ? 0 : event.getAttempts();
        int nextAttempt = currentAttempts + 1;

        if (nextAttempt >= properties.outbox().maxAttempts()) {
            return outboxEventRepository.markAsFailed(event.getId(), ex.getMessage())
                    .doOnSuccess(rows ->
                            log.warn("Event marked as FAILED: id={}, attempts={}",
                                    event.getId(), nextAttempt)
                    )
                    .then();
        }

        return outboxEventRepository.incrementAttempts(event.getId(), ex.getMessage())
                .doOnSuccess(rows ->
                        log.warn("Retry scheduled for event: id={}, attempts={}/{}",
                                event.getId(), nextAttempt, properties.outbox().maxAttempts())
                )
                .then();
    }

}
