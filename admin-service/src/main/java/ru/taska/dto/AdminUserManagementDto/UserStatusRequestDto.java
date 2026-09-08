package ru.taska.dto.AdminUserManagementDto;

import lombok.Builder;
import ru.taska.domain.GlobalRole;

import java.util.UUID;

/**
 * DTO изменения статуса пользователя (изменение происходят из-за blockUser, unblockUser, ResetCredentialLockout)
 * @param targetUserId айди пользователя, у которого производится сброс
 * @param actorUserId айди пользователя, проводящего сброс
 * @param actorLogin логин
 * @param role роль актора
 * @param reason причина изменения статуса
 */
@Builder
public record UserStatusRequestDto(
        UUID targetUserId,
        UUID actorUserId,
        String actorLogin,
        GlobalRole role,
        String reason
) {}
