package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Notification;

import java.time.Instant;
import java.util.UUID;

public interface NotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    @Query("""
            SELECT *
            FROM taska.notifications
            WHERE user_id = :userId
              AND read_at IS NULL
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Notification> findUnreadByUserId(UUID userId, int limit, long offset);

    @Query("""
            SELECT COUNT(*)
            FROM taska.notifications
            WHERE user_id = :userId
              AND read_at IS NULL
            """)
    Mono<Long> countUnreadByUserId(UUID userId);

    @Query("""
            SELECT *
            FROM taska.notifications
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Notification> findAllByUserId(UUID userId, int limit, long offset);

    Mono<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            UPDATE taska.notifications
            SET read_at = :readAt
            WHERE id = :notificationId
              AND user_id = :userId AND read_at IS NULL
            RETURNING *
            """)
    Mono<Notification> markAsRead(UUID notificationId, UUID userId, Instant readAt);

    @Modifying
    @Query("""
            UPDATE taska.notifications
            SET read_at = :readAt
            WHERE user_id = :userId AND read_at IS NULL
            """)
    Mono<Long> markAllAsRead(UUID userId, Instant readAt);

    /**
     * Находит все уведомления, созданные из указанного события.
     * Используется в тестах и при отладке.
     */
    @Query("""
            SELECT * FROM taska.notifications
            WHERE source_event_id = :sourceEventId
            ORDER BY created_at DESC
            """)
    Flux<Notification> findAllBySourceEventId(UUID sourceEventId);
}