package ru.taska.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Общий интерфейс репозитория для outbox
 * Каждый сервис реализует через свой репозиторий
 *
 * @param <T> Тип outbox события
 */
public interface CommonOutboxEventRepository<T> {
    /**
     * Выбирает bathSize события со статусом "NEW" и меняет на "PROCESSING"
     */
    Flux<T> findUnpublished(int batchSize);

    /**
     * Помечает событие успешно опубликованным
     */
    Mono<Integer> markAsPublished(UUID id, Instant publishedAt);

    /**
     * Увеличивает счетчик попыток и возвращает событие со статусом "NEW"
     */
    Mono<Integer> incrementAttempts(UUID id, String errorMessage);

    /**
     * Помечает событие как окончательно неуспешное (статус 'FAILED').
     */
    Mono<Integer> markAsFailed(UUID id, String errorMessage);

    /**
     * Сбрасывает статус 'PROCESSING' в 'NEW' для застрявших событий.
     */
    Mono<Integer> resetStuckProcessingEvents(Instant threshold);
}
