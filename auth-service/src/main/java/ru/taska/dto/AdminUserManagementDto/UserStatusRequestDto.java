package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;

import java.util.UUID;

/**
 * DTO изменения статуса пользователя (изменение происходят из-за blockUser, unblockUser, ResetCredentialLockout)
 * @param targetUserId айди пользователя, у которого производится сброс
 * @param actorUserId айди пользователя, проводящего сброс
 * @param reason причина сброса
 */
@Builder
public record UserStatusRequestDto(
        UUID targetUserId,
        UUID actorUserId,
        String reason
) {}
