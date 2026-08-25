package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.dto.AuditEventDto;
import ru.taska.dto.UserStatusChangeDto;
import ru.taska.service.AdminUserService;
import ru.taska.service.AuditService;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final AuditService auditService;
    private final GrpcAdminUserManagementServiceClient client;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<UserStatusChangeDto> blockUser(UUID targetUserId, UUID actorUserId, String actorLogin,
                                               JsonNode actorRoles, String reason, String requestId, String nodeId) {
        return client.blockUser(targetUserId, actorUserId, reason, requestId, nodeId)
                .flatMap(result -> {
                    AuditEventDto eventDto = buildAuditEvent(
                            targetUserId, actorUserId, actorLogin, actorRoles, reason, requestId,
                            "USER_BLOCKED", result
                    );
                    return auditService.logAudit(eventDto).thenReturn(result);
                });
    }

    @Override
    public Mono<UserStatusChangeDto> unblockUser(UUID targetUserId, UUID actorUserId, String actorLogin,
                                                 JsonNode actorRoles, String reason, String requestId, String nodeId) {
        return client.unblockUser(targetUserId, actorUserId, reason, requestId, nodeId)
                .flatMap(result -> {
                    AuditEventDto eventDto = buildAuditEvent(
                            targetUserId, actorUserId, actorLogin, actorRoles, reason, requestId,
                            "USER_UNBLOCKED", result
                    );
                    return auditService.logAudit(eventDto).thenReturn(result);
                });
    }

    private AuditEventDto buildAuditEvent(
            UUID targetUserId,
            UUID actorUserId,
            String actorLogin,
            JsonNode actorRoles,
            String reason,
            String requestId,
            String action,
            UserStatusChangeDto result
    ) {
        return AuditEventDto.builder()
                .requestId(requestId)
                .actorUserId(actorUserId)
                .actorLogin(actorLogin)
                .actorRoles(actorRoles)
                .action(action)
                .targetService("auth-service")
                .targetTable("users")
                .targetId(targetUserId.toString())
                .oldValue(objectMapper.createObjectNode().put("status", result.previousStatus()))
                .newValue(objectMapper.createObjectNode().put("status", result.currentStatus()))
                .reason(reason)
                .build();
    }
}
