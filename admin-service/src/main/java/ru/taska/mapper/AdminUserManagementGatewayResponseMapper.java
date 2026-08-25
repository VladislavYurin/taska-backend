package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.BlockUserResponse;
import ru.taska.api.admin.v1.UnblockUserResponse;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.dto.UserStatusChangeDto;

import java.time.Instant;
import java.time.OffsetDateTime;

@Component
public class AdminUserManagementGatewayResponseMapper {
    public BlockUserResponse toGatewayBlockUserResponse(UserStatusChangeDto dto) {
        return BlockUserResponse.newBuilder()
                .setUserId(dto.userId().toString())
                .setPreviousStatus(UserStatus.valueOf(dto.previousStatus()))
                .setCurrentStatus(UserStatus.valueOf(dto.currentStatus()))
                .setUpdatedAt(toTimestamp(dto.updatedAt()))
                .build();
    }

    public UnblockUserResponse toGatewayUnblockUserResponse(UserStatusChangeDto dto) {
        return UnblockUserResponse.newBuilder()
                .setUserId(dto.userId().toString())
                .setPreviousStatus(UserStatus.valueOf(dto.previousStatus()))
                .setCurrentStatus(UserStatus.valueOf(dto.currentStatus()))
                .setUpdatedAt(toTimestamp(dto.updatedAt()))
                .build();
    }



    private com.google.protobuf.Timestamp toTimestamp(OffsetDateTime dateTime) {
        Instant instant = dateTime.toInstant();
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
