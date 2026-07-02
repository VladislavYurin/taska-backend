package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.entity.OutboxEvent;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends ReactiveCrudRepository<OutboxEvent, UUID>,
        CommonOutboxEventRepository<OutboxEvent>{

    Mono<OutboxEvent> findByAggregateId(UUID aggregateId);

    /**
     * Выбирает batchSize событий и возвращает их, одновременно меняя статус на 'PROCESSING'.
     */
    @Query("""
            UPDATE taska.outbox_events
            SET status = 'PROCESSING',
                processing_started_at = now()
            WHERE id IN (
                SELECT id FROM taska.outbox_events
                WHERE status = 'NEW'
                ORDER BY created_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """)
    Flux<OutboxEvent> findUnpublished(int batchSize);

    /**
     * Помечает успешно опубликованные события
     */
    @Modifying
    @Query("""
            UPDATE taska.outbox_events
            SET status = 'PUBLISHED',
                published_at = :publishedAt,
                processing_started_at = NULL
            WHERE id = :id AND status = 'PROCESSING'
            """)
    Mono<Integer> markAsPublished(UUID id, Instant publishedAt);

    /**
     * Увеличивает счетчик неудачных попыток публикации события.
     */
    @Modifying
    @Query("""
            UPDATE taska.outbox_events
            SET status = 'NEW',
                attempts = COALESCE(attempts, 0) + 1,
                last_error_message = :errorMessage,
                processing_started_at = NULL
            WHERE id =:id AND status = 'PROCESSING'
            """)
    Mono<Integer> incrementAttempts(UUID id, String errorMessage);

    /**
     * Помечает событие как окончательно неуспешное (при достижении порога maxAttempts)
     */
    @Modifying
    @Query("""
            UPDATE taska.outbox_events
            SET status = 'FAILED',
                attempts = COALESCE(attempts, 0) + 1,
                last_error_message = :errorMessage,
                processing_started_at = NULL
            WHERE id = :id AND status = 'PROCESSING'
            """)
    Mono<Integer> markAsFailed(UUID id, String errorMessage);

    /**
     * Сбрасывает состояние застрявшего события в 'NEW' для возможности повторной обработки.
     */
    @Modifying
    @Query("""
            UPDATE taska.outbox_events
            SET status = 'NEW',
                processing_started_at = NULL
            WHERE status = 'PROCESSING' AND processing_started_at < :threshold
            """)
    Mono<Integer> resetStuckProcessingEvents(Instant threshold);
}
