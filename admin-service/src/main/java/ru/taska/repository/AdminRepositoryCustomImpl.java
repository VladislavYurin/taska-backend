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
import ru.taska.filter.CriteriaRenderer;
import ru.taska.filter.FilterBuilder;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AdminRepositoryCustomImpl implements AdminRepositoryCustom {

    private static final String CREATED_AT = "created_at";
    private static final String ID = "id";

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

    /**
     * Собирает запрос по фильтру. {@link Query} иммутабелен: {@code limit}/{@code offset}/{@code sort}
     * возвращают новый объект, поэтому результат каждого вызова нужно переприсваивать.
     * Сортировка — новые записи первыми; {@code id} добавлен как tie-breaker, чтобы записи
     * с одинаковым {@code created_at} не «прыгали» между страницами.
     */
    private Query buildQuery(FilterAuditDTO filter, Integer limit, Long offset) {
        Query query = Query.query(buildCriteria(filter))
                .sort(Sort.by(Sort.Order.desc(CREATED_AT), Sort.Order.desc(ID)));

        if (limit != null) {
            query = query.limit(limit);
        }
        if (offset != null) {
            query = query.offset(offset);
        }
        return query;
    }

    private Criteria buildCriteria(FilterAuditDTO filter) {
        if (filter == null) {
            return Criteria.empty();
        }
        return CriteriaRenderer.render(FilterBuilder.create()
                .eq("actor_user_id", filter.actorUserId())
                .eq("action", filter.action())
                .eq("target_service", filter.targetService())
                .eq("target_table", filter.targetTable())
                .eq("target_id", filter.targetId())
                .eq("request_id", filter.requestId())
                .gte(CREATED_AT, filter.createdAtFrom())
                .lte(CREATED_AT, filter.createdAtTo())
                .build());
    }
}
