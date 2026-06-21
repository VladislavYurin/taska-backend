package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;

import java.util.UUID;

public interface IssueRepository extends ReactiveCrudRepository<Issue, UUID>, IssueRepositoryCustom {

    @Query("SELECT * FROM taska.issues WHERE id = :id AND deleted_at IS NULL")
    Mono<Issue> findActiveById(@Param("id") UUID id);

    /**
     ** Изменяет задачу по айди и возвращает измененный объект из БД.
     *
     * @param issueId айди обновляемой задачи.
     *
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    @Query("UPDATE taska.issues SET summary = :summary, description = :description, priority = :priority, version = version + 1 " +
            "WHERE id = :issueId AND deleted_at IS NULL RETURNING *")
    Mono<Issue> updateActiveByIdAndReturn(UUID issueId, String summary, String description, IssuePriority priority);

    /**
    ** Производит мягкое удаление задачи по айди, устанавливая значение
     * в поле deleted_at и возвращает удаленный объект из БД.
     * После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param issueId айди удаляемой задачи.
     *
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    @Query("UPDATE taska.issues SET deleted_at = NOW(), version = version + 1 " +
            "WHERE id = :issueId AND deleted_at IS NULL RETURNING *")
    Mono<Issue> softDeleteAndReturn(UUID issueId);

    Mono<Issue> findByIdAndDeletedAtIsNull(UUID id);
}
