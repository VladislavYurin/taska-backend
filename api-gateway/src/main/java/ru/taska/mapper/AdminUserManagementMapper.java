package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.UserStatusDto;
import ru.taska.domain.dto.UserStatusResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AdminUserManagementMapper {

    public BlockUserRequest toBlockUserGrpcRequest(UUID targetUserId, String reason, GatewayContext context) {
        return BlockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(BlockUserRequestBody.newBuilder()
                        .setActorUserId(context.userContext().userId().toString())
                        .setActorLogin(context.userContext().login())
                        .setActorRoles(toRolesValue(context.userContext().globalRole()))
                        .setTargetUserId(targetUserId.toString())
                        .setReason(reason)
                        .build())
                .build();
    }

    public UnblockUserRequest toUnblockUserGrpcRequest(UUID targetUserId, String reason, GatewayContext context) {
        return UnblockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(UnblockUserRequestBody.newBuilder()
                        .setActorUserId(context.userContext().userId().toString())
                        .setActorLogin(context.userContext().login())
                        .setActorRoles(toRolesValue(context.userContext().globalRole()))
                        .setTargetUserId(targetUserId.toString())
                        .setReason(reason)
                        .build())
                .build();
    }

    public UserStatusResponseDto toRestBlockUserResponse(BlockUserResponse grpcResponse) {
        return new UserStatusResponseDto(UUID.fromString(grpcResponse.getUserId()),
                toUserStatusDto(grpcResponse.getPreviousStatus()),
                toUserStatusDto(grpcResponse.getCurrentStatus()),
                toOffsetDateTime(grpcResponse.getUpdatedAt())
        );
    }

    public UserStatusResponseDto toRestUnblockUserResponse(UnblockUserResponse grpcResponse) {
        return new UserStatusResponseDto(UUID.fromString(grpcResponse.getUserId()),
                toUserStatusDto(grpcResponse.getPreviousStatus()),
                toUserStatusDto(grpcResponse.getCurrentStatus()),
                toOffsetDateTime(grpcResponse.getUpdatedAt())
        );
    }

    private UserStatusDto toUserStatusDto(ru.taska.api.common.v1.UserStatus grpcStatus) {
        return switch (grpcStatus) {
            case USER_STATUS_ACTIVE -> UserStatusDto.ACTIVE;
            case USER_STATUS_BLOCKED -> UserStatusDto.BLOCKED;
            case USER_STATUS_INVITED -> UserStatusDto.INVITED;
            default -> throw new IllegalArgumentException("Unknown status: " + grpcStatus);
        };
    }


    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    private com.google.protobuf.Value toRolesValue(GlobalRole role) {
        return com.google.protobuf.Value.newBuilder()
                .setListValue(com.google.protobuf.ListValue.newBuilder()
                        .addValues(com.google.protobuf.Value.newBuilder()
                                .setStringValue(role.name())
                                .build())
                        .build())
                .build();
    }
}
