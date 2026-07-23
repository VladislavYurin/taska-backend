package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import ru.taska.entity.AuditLog;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends ReactiveCrudRepository <AuditLog, UUID> {

}
