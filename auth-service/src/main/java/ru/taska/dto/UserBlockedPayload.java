package ru.taska.dto;

import java.util.UUID;

public record UserBlockedPayload(
        UUID userId,
        String oldStatus,
        String newStatus,
        String reason,
        UUID actorUserId
) {}