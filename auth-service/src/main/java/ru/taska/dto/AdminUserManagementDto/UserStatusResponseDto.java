package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;
import ru.taska.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO ответа после изменений статуса пользователя
 */
@Builder
public record UserStatusResponseDto(
        UUID userId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant changedAt
) {
}