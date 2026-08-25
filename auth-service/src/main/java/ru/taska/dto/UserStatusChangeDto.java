package ru.taska.dto;

import ru.taska.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserStatusChangeDto(
        UUID userId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant updatedAt
) {
}