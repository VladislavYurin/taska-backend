package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO для представления ответа после изменений статуса пользователя
 * @param userId айди пользователя, над которым проводятся изменения
 * @param previousStatus предыдущий статус
 * @param currentStatus статус после изменений
 * @param changedAt время, в которое была выполнена операция по изменению статуса
 */
@Builder
public record UserStatusResponseDto (
        UUID userId,
        String previousStatus,
        String currentStatus,
        OffsetDateTime changedAt
) {}
