package ru.taska.service.readonly;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.config.props.OutboxProcessingProperties;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;
import ru.taska.dto.ProblematicEventCountDto;
import ru.taska.dto.ProblematicOutboxEventResponseDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProblematicOutboxEventMapper;
import ru.taska.repository.ProblematicOutboxEventRepository;
import ru.taska.service.ProblematicOutboxEventService;

import static ru.taska.repository.ProblematicOutboxEventRepository.COL_FAILED_COUNT;
import static ru.taska.repository.ProblematicOutboxEventRepository.COL_OVERDUE_COUNT;
import static ru.taska.repository.ProblematicOutboxEventRepository.COL_STUCK_COUNT;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProblematicOutboxEventServiceImpl implements ProblematicOutboxEventService {

    private static final String OUTBOX_TABLE = "outbox_events";

    private final OutboxProcessingProperties outboxProperties;
    private final ProblematicOutboxEventRepository outboxEventRepository;
    private final ProblematicOutboxEventMapper problematicOutboxEventMapper;
    private final SensitiveDataMaskService maskService;

    private static final String REASON_FAILED = "Event processing failed";
    private static final String REASON_STUCK_PROCESSING = "Event stuck in PROCESSING state (exceeded processing timeout)";
    private static final String REASON_OVERDUE_NEW = "Event stuck in NEW state (not picked up for processing)";

    @Override
    public Mono<GetProblematicOutboxEventsSummaryResponseDto> getProblematicOutboxEventsSummary(
            String serviceKey, String requestId, String nodeId) {
        return Mono.defer(() -> {
            List<String> outboxServices = outboxProperties.services();
            int maxSize = outboxProperties.maxProblematicListSize();

            boolean serviceSpecified = serviceKey != null && !serviceKey.isEmpty();
            if (serviceSpecified && !outboxServices.contains(serviceKey)) {
                log.warn("Requested service '{}' is not in the outbox services list: {}", serviceKey, outboxServices);
                return Mono.error(new DomainException(
                        DomainStatus.INVALID_ARGUMENT,
                        "Service '" + serviceKey + "' does not have outbox_events table"));
            }

            List<String> targetServices = serviceSpecified ? List.of(serviceKey) : outboxServices;
            Instant now = Instant.now();

            Mono<List<ProblematicEventCountDto>> countsMono = Flux.fromIterable(outboxServices)
                    .flatMap(sk -> countProblematicEvents(sk, now))
                    .collectList();

            Mono<List<ProblematicOutboxEventResponseDto>> eventsMono = Flux.fromIterable(targetServices)
                    // +1, чтобы определить, есть ли ещё записи сверх лимита (для notAllShown)
                    .flatMap(sk -> fetchProblematicEvents(sk, now, maxSize + 1, requestId, nodeId))
                    .collectSortedList(Comparator.comparing(ProblematicOutboxEventResponseDto::createdAt));

            return Mono.zip(countsMono, eventsMono)
                    .map(tuple -> {
                        List<ProblematicEventCountDto> counts = tuple.getT1();
                        List<ProblematicOutboxEventResponseDto> allEvents = tuple.getT2();

                        boolean notAllShown = allEvents.size() > maxSize;
                        List<ProblematicOutboxEventResponseDto> events = notAllShown
                                ? allEvents.subList(0, maxSize)
                                : allEvents;

                        return new GetProblematicOutboxEventsSummaryResponseDto(events, counts, notAllShown);
                    });
        });
    }

    private Mono<ProblematicEventCountDto> countProblematicEvents(String serviceKey, Instant now) {
        OffsetDateTime processingCutoff = computeProcessingCutoff(serviceKey, now);
        OffsetDateTime newCutoff = computeNewCutoff(serviceKey, now);

        return outboxEventRepository.countProblematicEvents(serviceKey, processingCutoff, newCutoff)
                .map(row -> new ProblematicEventCountDto(
                        serviceKey,
                        ((Number) row.get(COL_OVERDUE_COUNT)).longValue(),
                        ((Number) row.get(COL_STUCK_COUNT)).longValue(),
                        ((Number) row.get(COL_FAILED_COUNT)).longValue()
                ))
                .defaultIfEmpty(new ProblematicEventCountDto(serviceKey, 0, 0, 0));
    }

    private Flux<ProblematicOutboxEventResponseDto> fetchProblematicEvents(
            String serviceKey, Instant now, int limit, String requestId, String nodeId) {
        OffsetDateTime processingCutoff = computeProcessingCutoff(serviceKey, now);
        OffsetDateTime newCutoff = computeNewCutoff(serviceKey, now);

        return outboxEventRepository.fetchProblematicEvents(serviceKey, processingCutoff, newCutoff, limit)
                .collectList()
                .map(rows -> maskService.maskSensitiveData(rows, serviceKey, OUTBOX_TABLE, requestId, nodeId))
                .flatMapMany(Flux::fromIterable)
                .map(row -> problematicOutboxEventMapper.toDto(row, serviceKey, determineReason(row)));
    }

    private String determineReason(Map<String, Object> row) {
        String status = (String) row.get("status");
        if ("FAILED".equals(status)) {
            return REASON_FAILED;
        }
        if ("PROCESSING".equals(status)) {
            return REASON_STUCK_PROCESSING;
        }
        if ("NEW".equals(status)) {
            return REASON_OVERDUE_NEW;
        }
        log.error("Outbox event id={} with status '{}' is not problematic, but was returned by the query", row.get("id"), status);
        throw new DomainException(
                DomainStatus.INTERNAL,
                "Outbox event id=" + row.get("id") + " with status '" + status + "' is not problematic");
    }

    private OffsetDateTime computeProcessingCutoff(String serviceKey, Instant now) {
        Duration timeout = outboxProperties.processingTimeouts().get(serviceKey);
        return OffsetDateTime.ofInstant(now.minus(timeout), ZoneOffset.UTC);
    }

    private OffsetDateTime computeNewCutoff(String serviceKey, Instant now) {
        Duration threshold = outboxProperties.overdueNewThresholds().get(serviceKey);
        return OffsetDateTime.ofInstant(now.minus(threshold), ZoneOffset.UTC);
    }
}
