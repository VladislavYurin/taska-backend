package ru.taska.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record FilterAuditDTO(UUID actorUserId,
                             String action,
                             String targetService,
                             String targetTable,
                             String targetId,
                             String requestId,
                             Instant createdAtFrom,
                             Instant createdAtTo) {
}
