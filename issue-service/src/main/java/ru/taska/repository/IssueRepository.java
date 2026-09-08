package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.dto.IssueLinkInfoDto;

import java.util.UUID;

public interface IssueRepository extends ReactiveCrudRepository<Issue, UUID>, IssueRepositoryCustom {

    @Query("SELECT * FROM taska.issues WHERE id = :id AND deleted_at IS NULL")
    Mono<Issue> findActiveById(@Param("id") UUID id);

    @Query("SELECT project_id FROM taska.issues WHERE id = :id AND deleted_at IS NULL")
    Mono<UUID> findProjectIdByActiveIssueId(@Param("id") UUID id);

    /**
     * * Находит только активные задачи по айди и блокирует задачу до сохранения.
     *
     * @param id айди задачи.
     * @return Mono<{@link Issue}> запрашиваемая задача.
     */
    @Query("SELECT * FROM taska.issues WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
    Mono<Issue> findActiveByIdForUpdate(@Param("id") UUID id);

    /**
     * * Производит мягкое удаление задачи по айди, устанавливая значение
     * в поле deleted_at и возвращает удаленный объект из БД.
     * После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param issueId айди удаляемой задачи.
     * @return Mono<{@link Issue}> тело удаленной задачи.
     */
    @Query("UPDATE taska.issues SET deleted_at = NOW(), version = version + 1 " +
            "WHERE id = :issueId AND deleted_at IS NULL RETURNING *")
    Mono<Issue> softDeleteAndReturn(UUID issueId);

    /**
     * Атомарно меняет статус задачи через ручной optimistic lock за счет проверки версии задачи {@link Issue#getVersion()}.
     *
     * @param id      ID задачи
     * @param status  целевой (target) статус задачи
     * @param version текущая версия задачи (optimistic lock)
     * @return асинхронный контейнер Mono<{@link Issue}>, содержащий задачу с измененным статусом,
     * либо пустой {@link Mono#empty()} при конфликте версий
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

    /**
     * Находит две активные задачи, чтобы в дальнейшем можно было установить между ними связь.
     * Возвращает список DTO над этими задачами с необходимыми полями.
     *
     * @param sourceIssueId идентификатор исходной задачи (для которой устанавливается связь)
     * @param targetIssueId идентификатор целевой задачи (с которой устанавливается связь)
     * @return асинхронный контейнер Flux<{@link IssueLinkInfoDto}>, содержащий DTO только с нужными полями
     */
    @Query("""
            SELECT id, project_id
            FROM taska.issues
            WHERE id IN (:sourceIssueId, :targetIssueId)
                AND deleted_at IS NULL
            """)
    Flux<IssueLinkInfoDto> findIssueLinkInfo(UUID sourceIssueId, UUID targetIssueId);

    /**
     * Находит задачи по проекту, метке, статусу и исполнителю (с JOIN с issue_labels)
     * @param projectId
     * @param labelId
     * @param limit
     * @param offset
     * @return
     */
    @Query("""
        SELECT i.* FROM taska.issues i
        JOIN taska.issue_labels il ON il.issue_id = i.id
        WHERE i.project_id = :projectId
        AND i.deleted_at IS NULL
        AND il.label_id = :labelId
        AND (:statusKey IS NULL OR i.status_key = :statusKey)
        AND (:assigneeId IS NULL OR i.assignee_id = :assigneeId)
        ORDER BY i.created_at DESC
        LIMIT :limit OFFSET :offset
        """)
    Flux<Issue> findByLabelIdWithFilters(
            UUID projectId,
            UUID labelId,
            String statusKey,
            UUID assigneeId,
            int limit,
            long offset
    );

    /**
     * Считает задачи по проекту, метке, статусу и исполнителю (с JOIN с issue_labels)
     * @param projectId
     * @param labelId
     * @return
     */
    @Query("""
    SELECT COUNT(*) FROM taska.issues i
    JOIN taska.issue_labels il ON il.issue_id = i.id
    WHERE i.project_id = :projectId
    AND i.deleted_at IS NULL
    AND il.label_id = :labelId
    AND (:statusKey IS NULL OR i.status_key = :statusKey)
    AND (:assigneeId IS NULL OR i.assignee_id = :assigneeId)
""")
    Mono<Long> countByLabelIdWithFilters(
            UUID projectId,
            UUID labelId,
            String statusKey,
            UUID assigneeId
    );
}
