package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;

public interface AdminUserService {

    Mono<UserStatusResponseDto> blockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );

    Mono<UserStatusResponseDto> unblockUser(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );

    Mono<UserCredentialStateResponseDto> resetCredentialLockout(
            String requestId,
            String nodeId,
            UserStatusRequestDto requestDto
    );
}
