package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueWatcher;

import java.util.UUID;

public interface IssueWatcherRepository extends ReactiveCrudRepository<IssueWatcher, UUID> {

    /**
     * Возвращает подписчиков задачи, отсортированных по дате подписки (сначала новые).
     */
    @Query("""
            SELECT * FROM taska.issue_watchers
            WHERE issue_id = :issueId
            ORDER BY created_at DESC
            """)
    Flux<IssueWatcher> findAllByIssueId(UUID issueId);

    /**
     * Проверяет, подписан ли пользователь на задачу.
     */
    @Query("""
            SELECT EXISTS(
                SELECT 1 FROM taska.issue_watchers
                WHERE issue_id = :issueId AND user_id = :userId
            )
            """)
    Mono<Boolean> existsByIssueIdAndUserId(UUID issueId, UUID userId);

    /**
     * Подсчитывает количество подписчиков задачи.
     */
    @Query("SELECT COUNT(*) FROM taska.issue_watchers WHERE issue_id = :issueId")
    Mono<Long> countByIssueId(UUID issueId);

    /**
     * Создаёт подписку идемпотентно: повторный вызов не создаёт дубликат.
     */
    @Query("""
            INSERT INTO taska.issue_watchers (issue_id, project_id, user_id, created_by)
            VALUES (:issueId, :projectId, :userId, :createdBy)
            ON CONFLICT (issue_id, user_id) DO NOTHING
            RETURNING *
            """)
    Mono<IssueWatcher> insertIfAbsent(UUID issueId, UUID projectId, UUID userId, UUID createdBy);

    /**
     * Находит подписку пользователя на задачу
     */
    @Query("""
            SELECT * FROM taska.issue_watchers
            WHERE issue_id = :issueId AND user_id = :userId""")
    Mono<IssueWatcher> findByIssueIdAndUserId(UUID issueId, UUID userId);

    /**
     * Удаляет подписку пользователя на задачу.
     */
    @Query("""
            DELETE FROM taska.issue_watchers
            WHERE issue_id = :issueId AND user_id = :userId
            """)
    Mono<Long> deleteByIssueIdAndUserId(UUID issueId, UUID userId);
}
