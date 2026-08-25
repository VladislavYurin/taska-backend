package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.config.props.GrpcClientProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.ReactorAdminUserManagementServiceGrpc;
import ru.taska.dto.UserStatusChangeDto;
import ru.taska.mapper.AdminUserManagementAuthClientMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcAdminUserManagementServiceClient {

    private final ReactorAdminUserManagementServiceGrpc.ReactorAdminUserManagementServiceStub userManagementServiceStub;
    private final AdminUserManagementAuthClientMapper adminUserManagementAuthClientMapper;
    private final GrpcClientProperties properties;

    private ReactorAdminUserManagementServiceGrpc.ReactorAdminUserManagementServiceStub dynamicStub() {
        return userManagementServiceStub.withDeadlineAfter(
                properties.authService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public Mono<UserStatusChangeDto> blockUser(
            UUID targetUserId,
            UUID actorUserId,
            String reason,
            String requestId,
            String nodeId
    ) {
        log.info("[{}] Calling blockUser for user: {}", requestId, targetUserId);

        BlockUserRequest grpcRequest = adminUserManagementAuthClientMapper
                .toBlockUserGrpcRequest(targetUserId, reason, actorUserId, requestId, nodeId);

        return dynamicStub().blockUser(grpcRequest)
                .map(adminUserManagementAuthClientMapper::toUserStatusChangeDto);
    }

    public Mono<UserStatusChangeDto> unblockUser(
            UUID targetUserId,
            UUID actorUserId,
            String reason,
            String requestId,
            String nodeId
    ) {
        log.info("[{}] Calling unblockUser for user: {}", requestId, targetUserId);

        UnblockUserRequest grpcRequest = adminUserManagementAuthClientMapper
                .toUnblockUserGrpcRequest(targetUserId, reason, actorUserId, requestId, nodeId);

        return dynamicStub().unblockUser(grpcRequest)
                .map(adminUserManagementAuthClientMapper::toUserStatusChangeDto);
    }
}
