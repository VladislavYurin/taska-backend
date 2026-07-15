package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueComment;

import java.util.UUID;

public interface IssueCommentRepository extends ReactiveCrudRepository<IssueComment, UUID> {

    /**
     * Находит активные комментарии по ID задачи, отсортированные по дате создания (сначала новые).
     */
    @Query("SELECT * FROM taska.issue_comments WHERE issue_id = :issueId AND deleted_at IS NULL ORDER BY created_at DESC")
    Flux<IssueComment> findActiveByIssueIdOrderByCreatedAtDesc(UUID issueId);

    /**
     * Находит активный комментарий по ID.
     */
    @Query("SELECT * FROM taska.issue_comments WHERE id = :id AND deleted_at IS NULL")
    Mono<IssueComment> findActiveById(UUID id);

    /**
     * Проверяет существование активного комментария по ID и issueId.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM taska.issue_comments WHERE id = :id AND issue_id = :issueId AND deleted_at IS NULL)")
    Mono<Boolean> existsActiveByIdAndIssueId(UUID id, UUID issueId);

    /**
     * Мягкое удаление комментария.
     */
    @Query("UPDATE taska.issue_comments SET deleted_at = NOW(), version = version + 1 " +
            "WHERE id = :id AND deleted_at IS NULL RETURNING *")
    Mono<IssueComment> softDeleteAndReturn(UUID id);

    /**
     * Мягкое удаление комментария с проверкой версии (оптимистическая блокировка).
     *
     * @param id ID комментария
     * @param version текущая версия комментария (для проверки)
     * @return Mono с удаленным комментарием или пустой Mono, если версия не совпадает
     */
    @Query("""
            UPDATE taska.issue_comments
            SET deleted_at = NOW(),
                version = version + 1
            WHERE id = :id
              AND version = :version
              AND deleted_at IS NULL
            RETURNING *
            """)
    Mono<IssueComment> softDeleteWithVersionCheck(UUID id, Integer version);

    /**
     * Подсчёт количества комментариев у задачи.
     */
    @Query("SELECT COUNT(*) FROM taska.issue_comments WHERE issue_id = :issueId AND deleted_at IS NULL")
    Mono<Long> countActiveByIssueId(UUID issueId);

    /**
     * Обновление комментария с проверкой версии
     */
    @Query("""
            UPDATE taska.issue_comments
            SET body = :body,
                version = version + 1,
                updated_at = NOW()
            WHERE id = :id
                AND version = :version
                AND deleted_at IS NULL
            """)
    Mono<IssueComment> updateWithVersionCheck(UUID id, String body, Integer version);

    /**
     * Обновление комментария с проверкой версии и автора
     */
    @Query("""
    UPDATE taska.issue_comments
    SET body = :body,
        version = version + 1,
        updated_at = NOW()
    WHERE id = :id
      AND version = :version
      AND deleted_at IS NULL
      AND author_user_id = :actorUserId
    RETURNING *
    """)
    Mono<IssueComment> updateWithVersionCheckAndAuthor(
            UUID id, String body, Integer version, UUID actorUserId
    );
}