package ru.taska.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;


public interface AdminRepositoryCustom {
    Flux<AuditLog> findByFilter(FilterAuditDTO filterAuditDTO, int limit, long offset);

    Mono<Long> countByFilter(FilterAuditDTO filterAuditDTO);
}
