package ru.taska.processor;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.OutboxConfig;
import ru.taska.publisher.AbstractOutboxEventPublisher;
import ru.taska.repository.CommonOutboxEventRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Абстрактный класс для обработки outbox событий
 *
 * @param <T> Тип outbox события
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractOutboxEventProcessor<T> {

    protected final AbstractOutboxEventPublisher<T> publisher;
    protected final OutboxConfig config;
    protected final CommonOutboxEventRepository<T> repository;

    /**
     * Обрабатывает очередную пачку событий transactional outbox.
     * <p>По умолчанию:</p>
     * <li>Собирает статистику успешных/неудачных публикаций</li>
     * <li>Логирует сводный результат в doFinally</li>
     * <li>Фильтрует null события (можно переопределить)</li>
     *
     */
    public Mono<Void> processOutboxEvents() {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        return getEventsStream()
                .concatMap(event -> processEvent(event, success, failure))
                .then()
                .doFinally(signal -> {
                    int ok = success.get();
                    int failed = failure.get();
                    if (ok + failed > 0) {
                        log.info("Outbox processed: Total={}, Published={}, Failed={}",
                                ok + failed, ok, failed);
                    }
                });
    }

    /**
     * Обрабатывает застрявшие события (в статусе 'PROCESSING') дольше указанного времени.
     */
    public Mono<Void> processStuckEvents() {
        return Mono.defer(() -> {
                    Instant threshold = Instant.now()
                            .minus(config.getProcessingTimeout());

                    return repository.resetStuckProcessingEvents(threshold)
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

    // ===================== Защищенные методы (можно переопределять) =====================

    /**
     * Возвращает поток событий для обработки.
     * По умолчанию — из репозитория с фильтрацией null.
     */
    protected Flux<T> getEventsStream() {
        return repository.findUnpublished(config.getBatchSize())
                .filter(Objects::nonNull);
    }


    // ===================== Приватные методы (общая логика) =====================

    /**
     * Публикует событие в Kafka и помечает его как успешно обработанное
     *
     * <p>В случае ошибки запускает механизм обработки неудачной публикации.</p>
     */
    private Mono<Void> processEvent(T event, AtomicInteger success, AtomicInteger failure) {
        UUID eventId = getEventId(event);
        return publisher.publish(event)
                .then(Mono.defer(() ->
                        repository.markAsPublished(eventId, Instant.now())
                ))
                .doOnSuccess(ignored -> {
                    success.incrementAndGet();
                    log.info("Successfully published event: id={}", eventId);
                })
                .then()
                .onErrorResume(ex -> {
                    failure.incrementAndGet();
                    return handleProcessingError(event, ex);
                });
    }

    /**
     * Обрабатывает ошибку публикации события.
     *
     * <p>Если количество попыток достигло максимально допустимого значения,
     * событие переводится в состояние 'FAILED'.
     * Иначе увеличивается счётчик неудачных попыток.</p>
     */
    private Mono<Void> handleProcessingError(T event, Throwable ex) {
        UUID eventId = getEventId(event);
        UUID aggregateId = getAggregateId(event);
        String eventType = getEventType(event);
        int currentAttempts = getAttempts(event);
        int nextAttempt = currentAttempts + 1;
        log.error(
                "Failed to publish event: id={}, aggregateId={}, eventType={}",
                eventId, aggregateId, eventType, ex
        );
        if (nextAttempt >= config.getMaxAttempts()) {
            return repository.markAsFailed(eventId, ex.getMessage())
                    .doOnSuccess(rows ->
                            log.warn(
                                    "Event marked as FAILED: id={}, attempts={}",
                                    eventId,
                                    nextAttempt
                            )
                    )
                    .then();
        }

        return repository.incrementAttempts(eventId, ex.getMessage())
                .doOnSuccess(rows ->
                        log.warn(
                                "Retry scheduled for event: id={}, attempts={}/{}",
                                eventId,
                                nextAttempt,
                                config.getMaxAttempts()
                        )
                )
                .then();
    }
    // ===================== АБСТРАКТНЫЕ МЕТОДЫ =====================

    protected abstract UUID getEventId(T event);
    protected abstract UUID getAggregateId(T event);
    protected abstract String getEventType(T event);
    protected abstract int getAttempts(T event);


}
