package ru.taska.repository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import ru.taska.entity.WorkflowBindingEntity;

@Repository
public interface WorkflowBindingRepository extends R2dbcRepository<WorkflowBindingEntity, UUID> {
}
