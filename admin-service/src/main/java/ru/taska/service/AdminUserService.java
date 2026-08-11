package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.UserStatusChangeDto;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public interface AdminUserService {

    Mono<UserStatusChangeDto> blockUser(
            UUID targetUserId,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles,
            String reason,
            String requestId,
            String nodeId);

    Mono<UserStatusChangeDto> unblockUser(
            UUID targetUserId,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles,
            String reason,
            String requestId,
            String nodeId);
}
