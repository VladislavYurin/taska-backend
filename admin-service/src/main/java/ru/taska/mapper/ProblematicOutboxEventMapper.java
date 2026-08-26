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

    private static final String COL_ID = "id";
    private static final String COL_AGGREGATE_TYPE = "aggregate_type";
    private static final String COL_AGGREGATE_ID = "aggregate_id";
    private static final String COL_EVENT_TYPE = "event_type";
    private static final String COL_PAYLOAD = "payload";
    private static final String COL_STATUS = "status";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_PUBLISHED_AT = "published_at";
    private static final String COL_ATTEMPTS = "attempts";
    private static final String COL_LAST_ERROR_MESSAGE = "last_error_message";
    private static final String COL_PROCESSING_STARTED_AT = "processing_started_at";
    private static final String COL_REQUEST_ID = "request_id";

    /**
     * Маппинг строки из таблицы outbox_events в DTO.
     */
    public ProblematicOutboxEventResponseDto toDto(Map<String, Object> row, String serviceKey, String reason) {
        return new ProblematicOutboxEventResponseDto(
                row.get(COL_ID).toString(),
                (String) row.get(COL_AGGREGATE_TYPE),
                row.get(COL_AGGREGATE_ID).toString(),
                (String) row.get(COL_EVENT_TYPE),
                extractPayload(row.get(COL_PAYLOAD)),
                (String) row.get(COL_STATUS),
                toInstant(row.get(COL_CREATED_AT)),
                toInstant(row.get(COL_PUBLISHED_AT)),
                ((Number) row.get(COL_ATTEMPTS)).intValue(),
                (String) row.get(COL_LAST_ERROR_MESSAGE),
                toInstant(row.get(COL_PROCESSING_STARTED_AT)),
                (String) row.get(COL_REQUEST_ID),
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
