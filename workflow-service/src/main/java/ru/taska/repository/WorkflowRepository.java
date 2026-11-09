package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;

import java.util.UUID;

public interface WorkflowRepository extends ReactiveCrudRepository<WorkflowEntity, UUID> {
    @Query("SELECT * FROM taska.workflows WHERE owner_project_id=:project AND is_active=TRUE ORDER BY created_at,id")
    reactor.core.publisher.Flux<WorkflowEntity> findOwnedByProject(UUID project);

    @Query("SELECT * FROM taska.workflows WHERE id=:workflowId FOR UPDATE")
    Mono<WorkflowEntity> findByIdForUpdate(UUID workflowId);

    @Modifying
    @Query("UPDATE taska.workflows SET name=:name,version=version+1,updated_at=now() WHERE id=:workflowId AND version=:version")
    Mono<Integer> updateMetadata(UUID workflowId, String name, int version);

    @Query("""
    SELECT w.*
    FROM taska.workflow_bindings wb
        JOIN taska.workflows w ON wb.workflow_id = w.id
    WHERE wb.project_id = :projectId
      AND wb.issue_type = :issueType
      AND w.is_active = TRUE
    LIMIT 1
    """)
    Mono<WorkflowEntity> findWorkflowForProject(UUID projectId, String issueType);

    @Query("""
            SELECT w.* FROM taska.workflow_bindings b JOIN taska.workflows w ON w.id=b.workflow_id
            WHERE b.project_id=:projectId AND b.issue_type=:issueType AND w.is_active=TRUE
              AND (b.issue_type_generation=:generation OR (b.issue_type_generation IS NULL AND :legacy))
            LIMIT 1
            """)
    Mono<WorkflowEntity> findWorkflowForProjectGeneration(UUID projectId, String issueType, UUID generation, boolean legacy);

    // Атомарно: параллельные изменения одного workflow не теряют увеличение версии.
    @Modifying
    @Query("UPDATE taska.workflows SET version = version + 1, updated_at = now() WHERE id = :workflowId AND version = :version")
    Mono<Integer> incrementVersion(UUID workflowId, int version);
}
