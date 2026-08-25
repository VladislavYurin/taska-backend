package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.UserStatusChangeDto;

import java.util.UUID;

public interface AdminUserManagementService {
    Mono<UserStatusChangeDto> blockUser(UUID targetUserId, String reason, UUID actorId, UUID requestId);

    Mono<UserStatusChangeDto> unblockUser(UUID targetUserId, String reason, UUID actorId, UUID requestId);
}