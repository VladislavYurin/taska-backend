package ru.taska.dto;

import java.time.Instant;

public record ProblematicOutboxEventResponseDto(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String status,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        String lastErrorMessage,
        Instant processingStartedAt,
        String requestId,
        String serviceKey,
        String reason
) {
}
