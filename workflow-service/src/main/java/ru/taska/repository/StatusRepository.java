package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.entity.StatusEntity;

import java.util.UUID;

public interface StatusRepository extends ReactiveCrudRepository<StatusEntity, UUID> {

    @Query("""
        SELECT s.*
        FROM taska.statuses s
        WHERE s.workflow_id = :workflowId
        ORDER BY s.sort_order ASC
        """)
    Flux<StatusEntity> findActiveByWorkflowId(UUID workflowId);

    Mono<StatusEntity> findByWorkflowIdAndStatusKey(UUID workflowId, String statusKey);
}