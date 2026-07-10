package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;

import java.util.UUID;

public interface IssueRepository extends ReactiveCrudRepository<Issue, UUID>, IssueRepositoryCustom {

    @Query("SELECT * FROM taska.issues WHERE id = :id AND deleted_at IS NULL")
    Mono<Issue> findActiveById(@Param("id") UUID id);

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

    /**
     * Атомарно меняет статус задачи через ручной optimistic lock за счет проверки версии задачи {@link Issue#getVersion()}.
     *
     * @param id ID задачи
     * @param status целевой (target) статус задачи
     * @param version текущая версия задачи (optimistic lock)
     *
     * @return асинхронный контейнер Mono<{@link Issue}>, содержащий задачу с измененным статусом,
     *         либо пустой {@link Mono#empty()} при конфликте версий
     */
    @Query("""
            UPDATE taska.issues
            SET status_key = :status,
                version = version + 1,
                updated_at = NOW()
            WHERE id = :id AND version = :version AND deleted_at IS NULL
            RETURNING *
            """)
    Mono<Issue> changeStatus(UUID id, String status, Integer version);
}
