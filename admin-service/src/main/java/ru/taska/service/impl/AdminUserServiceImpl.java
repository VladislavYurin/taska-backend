package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.AuditAction;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.dto.AuditEventDto;
import ru.taska.service.AdminUserService;
import ru.taska.service.AuditService;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final AuditService auditService;
    private final GrpcAdminUserManagementServiceClient client;
    private final ObjectMapper objectMapper;

    /**
     * Ключ auth-service в каталоге {@code admin.metadata.services}. В {@code target_service} журнала
     * аудита пишется именно ключ каталога (как и в retry outbox), а не имя gRPC-клиента:
     * по нему фильтруется журнал и подбираются правила маскирования sensitive-колонок.
     */
    static final String AUTH_SERVICE_KEY = "auth";

    private static final String USERS_TABLE = "users";
    private static final String CREDENTIAL_TABLE = "credentials";
    private static final String STATUS = "status";

    @Override
    public Mono<UserStatusResponseDto> blockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return client
                .blockUser(requestId, nodeId, requestDto)
                .flatMap(responseDto ->
                        auditService.logAudit(
                                AuditEventDto.builder()
                                        .requestId(requestId)
                                        .actorUserId(requestDto.actorUserId())
                                        .actorDisplayName(requestDto.actorDisplayName())
                                        .actorEmail(requestDto.actorEmail())
                                        .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                        .action(AuditAction.USER_BLOCKED.name())
                                        .targetService(AUTH_SERVICE_KEY)
                                        .targetTable(USERS_TABLE)
                                        .targetId(requestDto.targetUserId().toString())
                                        .oldValue(objectMapper.createObjectNode().put(STATUS,responseDto.previousStatus()))
                                        .newValue(objectMapper.createObjectNode().put(STATUS,responseDto.currentStatus()))
                                        .reason(requestDto.reason())
                                        .build()
                        )
                        .thenReturn(responseDto)
                );
    }

    @Override
    public Mono<UserStatusResponseDto> unblockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return client
                .unblockUser(requestId, nodeId, requestDto)
                .flatMap(responseDto ->
                        auditService.logAudit(
                                AuditEventDto.builder()
                                        .requestId(requestId)
                                        .actorUserId(requestDto.actorUserId())
                                        .actorDisplayName(requestDto.actorDisplayName())
                                        .actorEmail(requestDto.actorEmail())
                                        .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                        .action(AuditAction.USER_UNBLOCKED.name())
                                        .targetService(AUTH_SERVICE_KEY)
                                        .targetTable(USERS_TABLE)
                                        .targetId(requestDto.targetUserId().toString())
                                        .oldValue(objectMapper.createObjectNode().put(STATUS,responseDto.previousStatus()))
                                        .newValue(objectMapper.createObjectNode().put(STATUS,responseDto.currentStatus()))
                                        .reason(requestDto.reason())
                                        .build()
                        )
                        .thenReturn(responseDto)
                );
    }

    @Override
    public Mono<UserCredentialStateResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return client
                .resetCredentialLockout(requestId, nodeId, requestDto)
                .flatMap(responseDto -> {

                        ObjectNode oldValue = objectMapper.valueToTree(responseDto.oldCredentialState());
                        oldValue.put("status", responseDto.previousStatus());

                        ObjectNode newValue = objectMapper.valueToTree(responseDto.newCredentialState());
                        newValue.put("status", responseDto.currentStatus());

                        return auditService
                                .logAudit(
                                        AuditEventDto.builder()
                                                .requestId(requestId)
                                                .actorUserId(requestDto.actorUserId())
                                                .actorDisplayName(requestDto.actorDisplayName())
                                                .actorEmail(requestDto.actorEmail())
                                                .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                                .action(AuditAction.RESET_CREDENTIAL_LOCKOUT.name())
                                                .targetService(AUTH_SERVICE_KEY)
                                                .targetTable(CREDENTIAL_TABLE)
                                                .targetId(requestDto.targetUserId().toString())
                                                .oldValue(oldValue)
                                                .newValue(newValue)
                                                .reason(requestDto.reason())
                                                .build()
                                )
                                .thenReturn(responseDto);
                });
    }
}
