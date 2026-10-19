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

}




