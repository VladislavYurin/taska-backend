package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;

import java.util.UUID;

public interface WorkflowRepository extends ReactiveCrudRepository<WorkflowEntity, UUID> {

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

    // Атомарно: параллельные изменения одного workflow не теряют увеличение версии.
    @Modifying
    @Query("UPDATE taska.workflows SET version = version + 1, updated_at = now() WHERE id = :workflowId")
    Mono<Integer> incrementVersion(UUID workflowId);
}
