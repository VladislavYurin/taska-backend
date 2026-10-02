package ru.taska.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;


import java.util.Optional;


@Slf4j
@Repository
@RequiredArgsConstructor
public class AdminRepositoryCustomImpl implements AdminRepositoryCustom {


    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    @Override
    public Flux<AuditLog> findByFilter(FilterAuditDTO filterAuditDTO, int limit, long offset) {
        Query query = buildQuery(filterAuditDTO, limit, offset);
        log.debug("Executing audit log query: {}", query);
        return r2dbcEntityTemplate.select(query, AuditLog.class);
    }

    @Override
    public Mono<Long> countByFilter(FilterAuditDTO filterAuditDTO) {
        Query query = buildQuery(filterAuditDTO, null, null);
        return r2dbcEntityTemplate.count(query, AuditLog.class);
    }

    private Query buildQuery(FilterAuditDTO filter, Integer limit, Long offset) {
        Criteria criteria = buildCriteria(filter);
        Query query = Query.query(criteria);

        if (limit != null) {
            query.limit(limit);
        }
        if (offset != null) {
            query.offset(offset);
        }

        query.sort(Sort.by(Sort.Direction.DESC, "created_at"));
        return query;
    }

    private Criteria buildCriteria(FilterAuditDTO filter) {
        Criteria criteria = Criteria.empty();

        if (filter.actorUserId() != null) {
            criteria = criteria.and("actor_user_id").is(filter.actorUserId());
        }
        if (filter.action() != null && !filter.action().isEmpty()) {
            criteria = criteria.and("action").is(filter.action());
        }
        if (filter.targetService() != null && !filter.targetService().isEmpty()) {
            criteria = criteria.and("target_service").is(filter.targetService());
        }
        if (filter.targetTable() != null && !filter.targetTable().isEmpty()) {
            criteria = criteria.and("target_table").is(filter.targetTable());
        }
        if (filter.targetId() != null && !filter.targetId().isEmpty()) {
            criteria = criteria.and("target_id").is(filter.targetId());
        }
        if (filter.requestId() != null && !filter.requestId().isEmpty()) {
            criteria = criteria.and("request_id").is(filter.requestId());
        }
        if (filter.createdAtFrom() != null) {
            criteria = criteria.and("created_at").greaterThanOrEquals(filter.createdAtFrom());
        }
        if (filter.createdAtTo() != null) {
            criteria = criteria.and("created_at").lessThanOrEquals(filter.createdAtTo());
        }
        return criteria;
    }
}
