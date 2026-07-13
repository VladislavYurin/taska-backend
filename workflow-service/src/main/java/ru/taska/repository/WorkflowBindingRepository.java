package ru.taska.repository;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import ru.taska.entity.WorkflowBindingEntity;

@Repository
public interface WorkflowBindingRepository extends R2dbcRepository<WorkflowBindingEntity, UUID> {

    Flux<WorkflowBindingEntity> findByProjectIdAndIssueTypeIn(UUID projectId, Collection<String> issueTypes);
}
