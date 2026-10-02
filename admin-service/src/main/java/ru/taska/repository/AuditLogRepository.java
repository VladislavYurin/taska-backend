package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, UUID>, AdminRepositoryCustom {

    @Query("""
            SELECT * FROM taska.admin_audit_log
            WHERE (:actorUserId IS NULL OR actor_user_id = :actorUserId)
            AND (:action IS NULL OR action = :action)
            AND (:targetService IS NULL OR target_service = :targetService)
            AND (:targetTable IS NULL OR target_table = :targetTable)
            AND (:targetId IS NULL OR target_id = :targetId)
            AND (:requestId IS NULL OR request_id = :requestId)
            AND (:createdAtFrom IS NULL OR created_at >= :createdAtFrom)
            AND (:createdAtTo IS NULL OR created_at <= :createdAtTo)
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<AuditLog> findByAuditWithFilters(FilterAuditDTO filterAuditDTO,
                                          Integer limit,
                                          Integer offset);
}




