package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.ResetCredentialLockoutRequest;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.BlockUserRequestBody;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequest;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequestBody;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequestBody;
import ru.taska.api.admin.v1.UserStatusResponse;
import ru.taska.api.auth.admin.management.v1.UserCredentialStateAuthResponse;
import ru.taska.api.auth.admin.management.v1.UserStatusAuthResponse;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.Header;
import ru.taska.api.common.v1.UserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.dto.AdminUserManagementDto.UserCredentialStateResponseDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.dto.AdminUserManagementDto.UserStatusResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AdminUserManagementMapper {

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(ru.taska.api.admin.v1.BlockUserRequest request) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(request.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(request.getBody().getActorUserId()))
                .actorLogin(request.getBody().getActorLogin())
                .role(toGlobalRole(request.getBody().getActorRole()))
                .reason(request.getBody().getReason())
                .build();
    }

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(ru.taska.api.admin.v1.UnblockUserRequest request) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(request.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(request.getBody().getActorUserId()))
                .actorLogin(request.getBody().getActorLogin())
                .role(toGlobalRole(request.getBody().getActorRole()))
                .reason(request.getBody().getReason())
                .build();
    }

    /**
     * GRPC request -> DTO request
     */
    public UserStatusRequestDto toRequestDto(ResetCredentialLockoutRequest request) {
        return UserStatusRequestDto.builder()
                .targetUserId(UUID.fromString(request.getBody().getTargetUserId()))
                .actorUserId(UUID.fromString(request.getBody().getActorUserId()))
                .actorLogin(request.getBody().getActorLogin())
                .role(toGlobalRole(request.getBody().getActorRole()))
                .reason(request.getBody().getReason())
                .build();
    }

    /**
     * DTO response -> GRPC response
     */
    public UserStatusResponse toProtoResponse(UserStatusResponseDto responseDto) {
        return UserStatusResponse.newBuilder()
                .setUserId(responseDto.userId().toString())
                .setPreviousStatus(toProtoStatus(responseDto.previousStatus()))
                .setCurrentStatus(toProtoStatus(responseDto.currentStatus()))
                .setChangedAt(toTimestamp(responseDto.changedAt()))
                .build();
    }

    /**
     * DTO response -> GRPC response
     * Поля credential state наружу не отдаем для единообразного контракта
     */
    public UserStatusResponse toProtoResponse(UserCredentialStateResponseDto responseDto) {
        return UserStatusResponse.newBuilder()
                .setUserId(responseDto.userId().toString())
                .setPreviousStatus(toProtoStatus(responseDto.previousStatus()))
                .setCurrentStatus(toProtoStatus(responseDto.currentStatus()))
                .setChangedAt(toTimestamp(responseDto.changedAt()))
                .build();
    }

    /**
     * DTO request (admin grpc-service-client) -> GRPC request (auth-service)
     */
    public ResetCredentialLockoutAuthRequest toAuthProtoResetRequest(String requestId, String nodeId, UserStatusRequestDto requestDto) {
        return ResetCredentialLockoutAuthRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(ResetCredentialLockoutAuthRequestBody.newBuilder()
                        .setTargetUserId(requestDto.targetUserId().toString())
                        .setActorUserId(requestDto.actorUserId().toString())
                        .setReason(requestDto.reason())
                        .build())
                .build();
    }

    /**
     * DTO request (admin grpc-service-client) -> GRPC request (auth-service)
     */
    public BlockUserRequest toAuthProtoBlockRequest(String requestId, String nodeId, UserStatusRequestDto requestDto) {
        return BlockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(BlockUserRequestBody.newBuilder()
                        .setTargetUserId(requestDto.targetUserId().toString())
                        .setActorUserId(requestDto.actorUserId().toString())
                        .setReason(requestDto.reason())
                        .build())
                .build();
    }

    /**
     * DTO request (admin grpc-service-client) -> GRPC request (auth-service)
     */
    public UnblockUserRequest toAuthProtoUnblockRequest(String requestId, String nodeId, UserStatusRequestDto requestDto) {
        return UnblockUserRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(requestId)
                        .setNodeId(nodeId)
                        .build())
                .setBody(UnblockUserRequestBody.newBuilder()
                        .setTargetUserId(requestDto.targetUserId().toString())
                        .setActorUserId(requestDto.actorUserId().toString())
                        .setReason(requestDto.reason())
                        .build())
                .build();
    }

    /**
     *  auth-service GRPC response -> DTO Response
     */
    public UserStatusResponseDto toUserStatusResponseDto(UserStatusAuthResponse grpcResponse) {
        return UserStatusResponseDto.builder()
                .userId(UUID.fromString(grpcResponse.getUserId()))
                .previousStatus(grpcResponse.getPreviousStatus().name())
                .currentStatus(grpcResponse.getCurrentStatus().name())
                .changedAt(toOffsetDateTime(grpcResponse.getChangedAt()))
                .build();
    }
    /**
     *  auth-service GRPC response -> DTO Response
     */
    public UserCredentialStateResponseDto toUserCredentialStateResponseDto(UserCredentialStateAuthResponse grpcResponse) {
        return UserCredentialStateResponseDto.builder()
                .userId(UUID.fromString(grpcResponse.getUserId()))
                .previousStatus(grpcResponse.getPreviousStatus().name())
                .currentStatus(grpcResponse.getCurrentStatus().name())
                .changedAt(toOffsetDateTime(grpcResponse.getChangedAt()))
                .oldCredentialState(
                        UserCredentialStateResponseDto.CredentialState.builder()
                                .failedAttempts(grpcResponse.getOldCredentialState().getFailedAttempts())
                                .lockedUntil(toOffsetDateTime(grpcResponse.getOldCredentialState().getLockedUntil()))
                                .lastFailedAt(toOffsetDateTime(grpcResponse.getOldCredentialState().getLastFailedAt()))
                                .build()
                )
                .newCredentialState(
                        UserCredentialStateResponseDto.CredentialState.builder()
                                .failedAttempts(grpcResponse.getNewCredentialState().getFailedAttempts())
                                .lockedUntil(toOffsetDateTime(grpcResponse.getNewCredentialState().getLockedUntil()))
                                .lastFailedAt(toOffsetDateTime(grpcResponse.getNewCredentialState().getLastFailedAt()))
                                .build()
                )
                .build();
    }

    ///===================== Utils ============================

    private GlobalRole toGlobalRole(GlobalRoleProto protoRole) {
        return switch (protoRole) {
            case GLOBAL_ROLE_GLOBAL_ADMIN -> GlobalRole.GLOBAL_ADMIN;
            case GLOBAL_ROLE_USER -> GlobalRole.USER;
            default -> GlobalRole.UNSPECIFIED;
        };
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    private Timestamp toTimestamp(OffsetDateTime dateTime) {
        Instant instant = dateTime.toInstant();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private UserStatus toProtoStatus(String status) {
        return switch (status) {
            case "ACTIVE" -> UserStatus.USER_STATUS_ACTIVE;
            case "BLOCKED" -> UserStatus.USER_STATUS_BLOCKED;
            case "INVITED" -> UserStatus.USER_STATUS_INVITED;
            case "LOCKED" -> UserStatus.USER_STATUS_LOCKED;
            default -> UserStatus.USER_STATUS_UNSPECIFIED;
        };
    }
}
