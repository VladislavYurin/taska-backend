package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.auth.admin.management.v1.BlockUserResponse;
import ru.taska.api.auth.admin.management.v1.UnblockUserResponse;
import ru.taska.dto.UserStatusChangeDto;
import ru.taska.entity.UserStatus;

@Component
public class AdminUserMapper {

    public BlockUserResponse toBlockUserResponse(UserStatusChangeDto dto) {
        return BlockUserResponse.newBuilder()
                .setUserId(dto.userId().toString())
                .setPreviousStatus(toProtoStatus(dto.oldStatus()))
                .setCurrentStatus(toProtoStatus(dto.newStatus()))
                .build();
    }

    public UnblockUserResponse toUnblockUserResponse(UserStatusChangeDto dto) {
        return UnblockUserResponse.newBuilder()
                .setUserId(dto.userId().toString())
                .setPreviousStatus(toProtoStatus(dto.oldStatus()))
                .setCurrentStatus(toProtoStatus(dto.newStatus()))
                .build();
    }

    private ru.taska.api.common.v1.UserStatus toProtoStatus(UserStatus domainStatus) {
        return switch (domainStatus) {
            case ACTIVE -> ru.taska.api.common.v1.UserStatus.USER_STATUS_ACTIVE;
            case BLOCKED -> ru.taska.api.common.v1.UserStatus.USER_STATUS_BLOCKED;
            case INVITED -> ru.taska.api.common.v1.UserStatus.USER_STATUS_INVITED;
        };
    }
}