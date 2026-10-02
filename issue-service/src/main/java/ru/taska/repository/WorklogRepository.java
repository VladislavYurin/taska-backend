package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Worklog;

import java.util.UUID;

public interface WorklogRepository extends ReactiveCrudRepository<Worklog, UUID> {

    @Query("SELECT * FROM taska.issue_worklogs WHERE id = :id AND deleted_at IS NULL")
    Mono<Worklog> findActiveById(@Param("id") UUID id);

    /**
     * * Находит только активные ворклоги по айди и блокирует ворклог до сохранения.
     *
     * @param id айди ворклога.
     * @return Mono<{@link Worklog}> запрашиваемый ворклог.
     */
    @Query("SELECT * FROM taska.issue_worklogs WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
    Mono<Worklog> findActiveByIdForUpdate(@Param("id") UUID id);


    @Query("SELECT * FROM taska.issue_worklogs t WHERE t.issue_id = :id AND deleted_at IS NULL ORDER BY work_date DESC")
    Flux<Worklog> findActiveByIssueId(@Param("id") UUID id);


    /**
     * * Производит мягкое удаление ворклога по айди, устанавливая значение
     * в поле deleted_at и возвращает удаленный объект из БД.
     * После удаления данные остаются в БД, но объект больше не участвует в выдаче.
     *
     * @param id айди удаляемого ворклога.
     * @return Mono<{@link Worklog}> тело удалённого worklog.
     */
    @Query("UPDATE taska.issue_worklogs SET deleted_at = now(), updated_at = now()," +
            " version = version + 1 WHERE id = :id AND deleted_at IS NULL  RETURNING *")
    Mono<Worklog> softDelete(@Param("id") UUID id);
}
