package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.UserStatusAuthResponse;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;
import ru.taska.entity.UserStatus;

import java.time.Instant;
import java.util.UUID;

@Component
public class AdminUserMapper {

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(BlockUserRequest protoRequest) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(protoRequest.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(protoRequest.getBody().getActorUserId()))
                .reason(protoRequest.getBody().getReason())
                .build();
    }

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(UnblockUserRequest protoRequest) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(protoRequest.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(protoRequest.getBody().getActorUserId()))
                .reason(protoRequest.getBody().getReason())
                .build();
    }

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(ResetCredentialLockoutAuthRequest protoRequest) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(protoRequest.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(protoRequest.getBody().getActorUserId()))
                .reason(protoRequest.getBody().getReason())
                .build();
    }

    /**
     * DTO response -> GRPC response
     */
    public UserStatusAuthResponse toProtoResponse(UserStatusResponseDto responseDto) {
        return UserStatusAuthResponse.newBuilder()
                .setUserId(responseDto.userId().toString())
                .setPreviousStatus(toProtoStatus(responseDto.oldStatus()))
                .setCurrentStatus(toProtoStatus(responseDto.newStatus()))
                .setChangedAt(toTimestamp(responseDto.changedAt()))
                .build();
    }

    ///===================== Utils ===========================

    private Timestamp toTimestamp(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private ru.taska.api.common.v1.UserStatus toProtoStatus(UserStatus domainStatus) {
        return switch (domainStatus) {
            case ACTIVE -> ru.taska.api.common.v1.UserStatus.USER_STATUS_ACTIVE;
            case BLOCKED -> ru.taska.api.common.v1.UserStatus.USER_STATUS_BLOCKED;
            case INVITED -> ru.taska.api.common.v1.UserStatus.USER_STATUS_INVITED;
            case LOCKED -> ru.taska.api.common.v1.UserStatus.USER_STATUS_LOCKED;
        };
    }
}