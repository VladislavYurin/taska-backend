package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.auth.admin.management.v1.*;
import ru.taska.api.common.v1.Header;
import ru.taska.dto.UserStatusChangeDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

//auth-response → внутренний DTO
@Component
public class AdminUserManagementAuthClientMapper {


    public BlockUserRequest toBlockUserGrpcRequest(
            UUID targetUserId,
            String reason,
            UUID actorUserId,
            String requestId,
            String nodeId
    ) {
        return BlockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(BlockUserRequestBody.newBuilder()
                        .setActorUserId(actorUserId.toString())
                        .setTargetUserId(targetUserId.toString())
                        .setReason(reason)
                        .build())
                .build();
    }

    public UnblockUserRequest toUnblockUserGrpcRequest(
            UUID targetUserId,
            String reason,
            UUID actorUserId,
            String requestId,
            String nodeId
    ) {
        return UnblockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(UnblockUserRequestBody.newBuilder()
                        .setActorUserId(actorUserId.toString())
                        .setTargetUserId(targetUserId.toString())
                        .setReason(reason)
                        .build())
                .build();
    }

    public UserStatusChangeDto toUserStatusChangeDto(BlockUserResponse grpcResponse) {
        return new UserStatusChangeDto(
                UUID.fromString(grpcResponse.getUserId()),
                grpcResponse.getPreviousStatus().name(),
                grpcResponse.getCurrentStatus().name(),
                toOffsetDateTime(grpcResponse.getUpdatedAt())
        );
    }

    public UserStatusChangeDto toUserStatusChangeDto(UnblockUserResponse grpcResponse) {
        return new UserStatusChangeDto(
                UUID.fromString(grpcResponse.getUserId()),
                grpcResponse.getPreviousStatus().name(),
                grpcResponse.getCurrentStatus().name(),
                toOffsetDateTime(grpcResponse.getUpdatedAt())
        );
    }


    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }


}
