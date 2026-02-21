package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import ru.taska.entity.TransitionEntity;

import java.util.UUID;

public interface TransitionRepository extends ReactiveCrudRepository<TransitionEntity, UUID> {

    @Query("""
        SELECT t.*
        FROM taska.transitions t
        WHERE t.workflow_id = :workflowId
          AND t.is_hidden = FALSE
        ORDER BY t.sort_order ASC
        """)
    Flux<TransitionEntity> findVisibleByWorkflowId(UUID workflowId);
}
