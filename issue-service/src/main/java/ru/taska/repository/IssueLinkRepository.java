package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueLink;

import java.util.UUID;

public interface IssueLinkRepository extends ReactiveCrudRepository<IssueLink, UUID> {

    /**
     * Находит активную связь между задачами.
     *
     * @param id      идентификатор связи
     * @param issueId идентификатор задачи
     * @return асинхронный контейнер Mono<{@link IssueLink}>, содержащий связь между задачами
     */
    @Query("""
            SELECT * FROM taska.issue_links
            WHERE id = :id
                AND (source_issue_id = :issueId OR target_issue_id = :issueId)
                AND deleted_at IS NULL
            """)
    Mono<IssueLink> findActiveByIdAndIssueId(UUID id, UUID issueId);

    /**
     * Находит все связи задачи.
     *
     * @param issueId идентификатор задачи
     * @return асинхронный контейнер Flux<{@link IssueLink}>, содержащий список всех связей задачи
     */
    @Query("""
            SELECT * FROM taska.issue_links
            WHERE (source_issue_id = :issueId OR target_issue_id = :issueId)
                AND deleted_at IS NULL
            """)
    Flux<IssueLink> findAllByIssueId(UUID issueId);

    /**
     * Производит мягкое удаление связи, устанавливая значение в поле deleted_at,
     * и возвращает мягко удаленный объект связи из БД.
     * После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param id идентификатор связи
     * @return асинхронный контейнер Mono<{@link IssueLink}>, содержащий мягко удаленную связь
     */
    @Query("""
            UPDATE taska.issue_links
            SET deleted_at = now()
            WHERE id = :id
              AND deleted_at IS NULL
            RETURNING *
            """)
    Mono<IssueLink> softDelete(UUID id);
}
