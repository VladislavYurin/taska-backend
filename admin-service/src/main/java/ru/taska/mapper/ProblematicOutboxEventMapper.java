package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.GetProblematicOutboxEventsSummaryResponse;
import ru.taska.api.admin.v1.ProblematicEventCountsByService;
import ru.taska.api.admin.v1.ProblematicOutboxEventDto;
import ru.taska.dto.GetProblematicOutboxEventsSummaryResponseDto;
import ru.taska.dto.ProblematicOutboxEventResponseDto;
import ru.taska.dto.ProblematicEventCountDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import io.r2dbc.postgresql.codec.Json;

import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
public class ProblematicOutboxEventMapper {

    /**
     * Маппинг строки из таблицы outbox_events в DTO.
     */
    public ProblematicOutboxEventResponseDto toDto(Map<String, Object> row, String serviceKey, String reason) {
        return new ProblematicOutboxEventResponseDto(
                row.get("id").toString(),
                (String) row.get("aggregate_type"),
                row.get("aggregate_id").toString(),
                (String) row.get("event_type"),
                extractPayload(row.get("payload")),
                (String) row.get("status"),
                toInstant(row.get("created_at")),
                toInstant(row.get("published_at")),
                ((Number) row.get("attempts")).intValue(),
                (String) row.get("last_error_message"),
                toInstant(row.get("processing_started_at")),
                (String) row.get("request_id"),
                serviceKey,
                reason
        );
    }

    private String extractPayload(Object value) {
        if (value instanceof Json json) {
            return json.asString();
        }
        return value.toString();
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        try {
            return Instant.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw new DomainException(
                    DomainStatus.INTERNAL,
                    "Cannot convert value '" + value + "' (type: " + value.getClass().getName() + ") to Instant");
        }
    }


    public GetProblematicOutboxEventsSummaryResponse toProto(GetProblematicOutboxEventsSummaryResponseDto dto) {
        GetProblematicOutboxEventsSummaryResponse.Builder builder = GetProblematicOutboxEventsSummaryResponse.newBuilder()
                .setNotAllShown(dto.notAllShown());

        for (ProblematicOutboxEventResponseDto event : dto.events()) {
            builder.addEvents(toProtoEvent(event));
        }

        for (ProblematicEventCountDto count : dto.counts()) {
            builder.addCounts(toProtoCount(count));
        }

        return builder.build();
    }

    private ProblematicOutboxEventDto toProtoEvent(ProblematicOutboxEventResponseDto dto) {
        ProblematicOutboxEventDto.Builder builder = ProblematicOutboxEventDto.newBuilder()
                .setId(dto.id())
                .setAggregateType(dto.aggregateType())
                .setAggregateId(dto.aggregateId())
                .setEventType(dto.eventType())
                .setPayload(dto.payload())
                .setStatus(dto.status())
                .setCreatedAt(toTimestamp(dto.createdAt()))
                .setAttempts(dto.attempts())
                .setServiceKey(dto.serviceKey())
                .setReason(dto.reason());

        if (dto.publishedAt() != null) {
            builder.setPublishedAt(toTimestamp(dto.publishedAt()));
        }
        if (dto.lastErrorMessage() != null) {
            builder.setLastErrorMessage(dto.lastErrorMessage());
        }
        if (dto.processingStartedAt() != null) {
            builder.setProcessingStartedAt(toTimestamp(dto.processingStartedAt()));
        }
        if (dto.requestId() != null) {
            builder.setRequestId(dto.requestId());
        }

        return builder.build();
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private ProblematicEventCountsByService toProtoCount(ProblematicEventCountDto dto) {
        return ProblematicEventCountsByService.newBuilder()
                .setServiceKey(dto.serviceKey())
                .setOverdueNewCount(dto.overdueNewCount())
                .setStuckProcessingCount(dto.stuckProcessingCount())
                .setFailedCount(dto.failedCount())
                .build();
    }
}
