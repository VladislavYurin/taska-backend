package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
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
}
