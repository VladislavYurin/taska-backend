package ru.taska.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserStatusChangeDto (
        UUID userId,
        String previousStatus,
        String currentStatus,
        OffsetDateTime updatedAt
) {}
