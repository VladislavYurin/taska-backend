package ru.taska.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only репозиторий для работы с таблицей outbox_events.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ProblematicOutboxEventRepository {

    public static final String COL_FAILED_COUNT = "failed_count";
    public static final String COL_STUCK_COUNT = "stuck_count";
    public static final String COL_OVERDUE_COUNT = "overdue_count";

    private final ReadOnlyRepository readOnlyRepository;
    private final MetadataCatalogProperties catalogProperties;

    /**
     * Подсчёт проблемных событий по трём категориям: FAILED, застрявшие PROCESSING, застрявшие NEW.
     */
    public Mono<Map<String, Object>> countProblematicEvents(String serviceKey,
                                                            OffsetDateTime processingCutoff,
                                                            OffsetDateTime newCutoff) {
        String schema = resolveSchema(serviceKey);

        String sql = "SELECT "
                + "COUNT(*) FILTER (WHERE status = 'FAILED') AS " + COL_FAILED_COUNT + ", "
                + "COUNT(*) FILTER (WHERE status = 'PROCESSING' AND processing_started_at < $1) AS " + COL_STUCK_COUNT + ", "
                + "COUNT(*) FILTER (WHERE status = 'NEW' AND created_at < $2) AS " + COL_OVERDUE_COUNT + " "
                + "FROM " + schema + ".outbox_events "
                + "WHERE status IN ('FAILED', 'PROCESSING', 'NEW')";

        return readOnlyRepository.executeQuery(serviceKey, sql, List.of(processingCutoff, newCutoff)).next();
    }

    /**
     * Выборка проблемных outbox-событий, отсортированных по created_at ASC.
     */
    public Flux<Map<String, Object>> fetchProblematicEvents(String serviceKey,
                                                            OffsetDateTime processingCutoff,
                                                            OffsetDateTime newCutoff,
                                                            int limit) {
        String schema = resolveSchema(serviceKey);

        String sql = "SELECT * FROM " + schema + ".outbox_events "
                + "WHERE status = 'FAILED' "
                + "OR (status = 'PROCESSING' AND processing_started_at < $1) "
                + "OR (status = 'NEW' AND created_at < $2) "
                + "ORDER BY created_at ASC LIMIT $3";

        return readOnlyRepository.executeQuery(serviceKey, sql, List.of(processingCutoff, newCutoff, limit));
    }

    private String resolveSchema(String serviceKey) {
        MetadataCatalogProperties.ServiceProperties serviceProps = catalogProperties.services().get(serviceKey);
        if (serviceProps == null) {
            throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Unknown service: " + serviceKey);
        }
        return serviceProps.schema();
    }
}
