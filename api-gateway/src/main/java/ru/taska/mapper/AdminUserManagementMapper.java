package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.BlockUserRequest;
import ru.taska.api.admin.v1.BlockUserRequestBody;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequest;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequestBody;
import ru.taska.api.admin.v1.UnblockUserRequest;
import ru.taska.api.admin.v1.UnblockUserRequestBody;
import ru.taska.api.admin.v1.UserStatusResponse;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.BlockUserRequestDto;
import ru.taska.domain.dto.ResetLockoutRequestDto;
import ru.taska.domain.dto.UnblockUserRequestDto;
import ru.taska.domain.dto.UserStatusDto;
import ru.taska.domain.dto.UserStatusResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AdminUserManagementMapper {

    /**
     * rest request -> grpc request
     */
    public BlockUserRequest toBlockUserGrpcRequest(UUID targetUserId, BlockUserRequestDto requestDto, GatewayContext context) {
        return BlockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(BlockUserRequestBody.newBuilder()
                        .setActorUserId(context.userContext().userId())
                        .setActorLogin(context.userContext().login())
                        .setActorRole(toGlobalRoleProto(context.userContext().globalRole()))
                        .setTargetUserId(targetUserId.toString())
                        .setReason(requestDto.getReason())
                        .build())
                .build();
    }

    /**
     * rest request -> grpc request
     */
    public UnblockUserRequest toUnblockUserGrpcRequest(UUID targetUserId, UnblockUserRequestDto requestDto, GatewayContext context) {
        return UnblockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(UnblockUserRequestBody.newBuilder()
                        .setActorUserId(context.userContext().userId())
                        .setActorLogin(context.userContext().login())
                        .setActorRole(toGlobalRoleProto(context.userContext().globalRole()))
                        .setTargetUserId(targetUserId.toString())
                        .setReason(requestDto.getReason())
                        .build())
                .build();
    }

    /**
     * rest DTO request -> grpc request
     */
    public ResetCredentialLockoutRequest toResetCredentialLockoutRequest(UUID targetUserId, ResetLockoutRequestDto requestDto, GatewayContext context) {
        return ResetCredentialLockoutRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(ResetCredentialLockoutRequestBody.newBuilder()
                        .setTargetUserId(targetUserId.toString())
                        .setActorUserId(context.userContext().userId())
                        .setActorLogin(context.userContext().login())
                        .setActorRole(toGlobalRoleProto(context.userContext().globalRole()))
                        .setReason(requestDto.getReason())
                        .build())
                .build();
    }

    /**
     * grpc response -> DTO response
     */
    public UserStatusResponseDto toRestUserStatusResponse(UserStatusResponse grpcResponse) {
        UserStatusResponseDto restDto = new UserStatusResponseDto();
        restDto.setUserId(UUID.fromString(grpcResponse.getUserId()));
        restDto.setPreviousStatus(toUserStatusDto(grpcResponse.getPreviousStatus()));
        restDto.setCurrentStatus(toUserStatusDto(grpcResponse.getCurrentStatus()));
        restDto.setChangedAt(toOffsetDateTime(grpcResponse.getChangedAt()));
        return restDto;
    }

    private UserStatusDto toUserStatusDto(ru.taska.api.common.v1.UserStatus grpcStatus) {
        return switch (grpcStatus) {
            case USER_STATUS_ACTIVE -> UserStatusDto.ACTIVE;
            case USER_STATUS_BLOCKED -> UserStatusDto.BLOCKED;
            case USER_STATUS_INVITED -> UserStatusDto.INVITED;
            case USER_STATUS_LOCKED -> UserStatusDto.LOCKED;
            default -> throw new IllegalArgumentException("Unknown status: " + grpcStatus);
        };
    }


    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    private GlobalRoleProto toGlobalRoleProto(GlobalRole role) {
        return switch (role) {
            case GlobalRole.GLOBAL_ADMIN -> GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN;
            case GlobalRole.USER -> GlobalRoleProto.GLOBAL_ROLE_USER;
            default -> GlobalRoleProto.GLOBAL_ROLE_UNSPECIFIED;
        };
    }
}
