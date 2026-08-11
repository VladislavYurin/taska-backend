package ru.taska.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.config.props.AuditAction;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.dto.AuditEventDto;
import ru.taska.service.AdminUserService;
import ru.taska.service.AuditService;
import ru.taska.transport.grpc.GrpcAdminUserManagementServiceClient;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final AuditService auditService;
    private final GrpcAdminUserManagementServiceClient client;
    private final ObjectMapper objectMapper;
    private final GrpcClientProperties properties;

    private static final String USERS_TABLE = "users";
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
                                        .actorLogin(requestDto.actorLogin())
                                        .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                        .action(AuditAction.USER_BLOCKED.name())
                                        .targetService(properties.authService().serviceName())
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
                                        .actorLogin(requestDto.actorLogin())
                                        .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                        .action(AuditAction.USER_UNBLOCKED.name())
                                        .targetService(properties.authService().serviceName())
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
    public Mono<UserStatusResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    ) {
        return client
                .resetCredentialLockout(requestId, nodeId, requestDto)
                .flatMap(responseDto ->
                        auditService.logAudit(
                                AuditEventDto.builder()
                                        .requestId(requestId)
                                        .actorUserId(requestDto.actorUserId())
                                        .actorLogin(requestDto.actorLogin())
                                        .actorRoles(objectMapper.valueToTree(requestDto.role()))
                                        .action(AuditAction.RESET_CREDENTIAL_LOCKOUT.name())
                                        .targetService(properties.authService().serviceName())
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
}
