package ru.taska.repository;

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
            FROM notifications
            WHERE user_id = :userId
              AND read_at IS NULL
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Notification> findUnreadByUserId(UUID userId, int limit, long offset);

    @Query("""
            SELECT *
            FROM notifications
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<Notification> findAllByUserId(UUID userId, int limit, long offset);

    Mono<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
        UPDATE notifications
        SET read_at = :readAt
        WHERE id = :notificationId
          AND user_id = :userId
        """)
    Mono<Integer> markAsRead(UUID notificationId, UUID userId, Instant readAt);
}