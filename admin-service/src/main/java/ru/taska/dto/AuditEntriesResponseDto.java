package ru.taska.dto;


import lombok.Builder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Builder
public record AuditEntriesResponseDto(
        UUID actorUserId,
        String actorLogin,
        String action,
        String targetService,
        String targetTable,
        String targetId,
        JsonNode oldValue,
        JsonNode newValue,
        String reason,
        String requestId,
        Instant createdAt
) {
}


