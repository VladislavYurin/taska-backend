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

        Optional.ofNullable(filter.actorUserId())
                .ifPresent(id -> criteria.and("actor_user_id").is(id));

        Optional.ofNullable(filter.action())
                .filter(s -> !s.isEmpty())
                .ifPresent(action -> criteria.and("action").is(action));

        Optional.ofNullable(filter.targetService())
                .filter(s -> !s.isEmpty())
                .ifPresent(service -> criteria.and("target_service").is(service));

        Optional.ofNullable(filter.targetTable())
                .filter(s -> !s.isEmpty())
                .ifPresent(table -> criteria.and("target_table").is(table));

        Optional.ofNullable(filter.targetId())
                .filter(s -> !s.isEmpty())
                .ifPresent(id -> criteria.and("target_id").is(id));

        Optional.ofNullable(filter.requestId())
                .filter(s -> !s.isEmpty())
                .ifPresent(requestId -> criteria.and("request_id").is(requestId));

        Optional.ofNullable(filter.createdAtFrom())
                .ifPresent(from -> criteria.and("created_at").greaterThanOrEquals(from));

        Optional.ofNullable(filter.createdAtTo())
                .ifPresent(to -> criteria.and("created_at").lessThanOrEquals(to));

        return criteria;
    }
}
