package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.auth.admin.management.v1.BlockUserRequest;
import ru.taska.api.auth.admin.management.v1.ResetCredentialLockoutAuthRequest;
import ru.taska.api.auth.admin.management.v1.UnblockUserRequest;
import ru.taska.api.auth.admin.management.v1.UserCredentialStateAuthResponse;
import ru.taska.api.auth.admin.management.v1.UserStatusAuthResponse;
import ru.taska.dto.AdminUserManagementDto.UserStatusRequestDto;
import ru.taska.mapper.AdminUserMapper;
import ru.taska.service.AdminUserManagementService;
import validator.GrpcRequestValidators;

import static ru.taska.transport.grpc.logging.GrpcAuthLogging.logOnError;
import static ru.taska.transport.grpc.logging.GrpcAuthLogging.logValidationError;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserManagementGrpcService {

    private final AdminUserManagementService adminUserManagementService;
    private final AdminUserMapper adminUserMapper;

    @TrackMetrics(counter = "auth-service_blockUser_grpc_counter",
            timer = "auth-service_blockUser_grpc_timer")
    public Mono<UserStatusAuthResponse> blockUser(Mono<BlockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.target_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "blockUser"
                        ))
                        .flatMap(tuple -> {
                                String requestId = tuple.getT1();
                                String nodeId = tuple.getT2();

                                log.info("[{}][{}] BlockUser request", requestId, nodeId);

                                UserStatusRequestDto requestDto = adminUserMapper.toRequestDto(req);
                                return adminUserManagementService.blockUser(requestId,nodeId,requestDto)
                                        .doOnSuccess(result ->
                                                log.info("[{}][{}] blockUser: successfully blockUser user",
                                                        requestId, nodeId)
                                        )
                                        .doOnError(logOnError(requestId, nodeId, "blockUser"));
                        })
                        .map(adminUserMapper::toProtoResponse)
                );
    }

    @TrackMetrics(counter = "auth-service_unblockUser_grpc_counter",
            timer = "auth-service_unblockUser_grpc_timer")
    public Mono<UserStatusAuthResponse> unblockUser(Mono<UnblockUserRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actor_user_id"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.target_user_id"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "unblockUser"
                        ))
                        .flatMap(tuple -> {
                                String requestId = tuple.getT1();
                                String nodeId = tuple.getT2();

                                log.info("[{}][{}] UnblockUser request", requestId, nodeId);

                                UserStatusRequestDto requestDto = adminUserMapper.toRequestDto(req);
                                return adminUserManagementService.unblockUser(requestId,nodeId,requestDto)
                                        .doOnSuccess(result ->
                                                log.info("[{}][{}] unblockUser: successfully unblock user",
                                                        requestId, nodeId)
                                        )
                                        .doOnError(logOnError(requestId, nodeId, "unblockUser"));
                        })
                        .map(adminUserMapper::toProtoResponse)
                );
    }

    @TrackMetrics(counter = "auth-service_reset-credential-lockout_grpc_counter",
            timer = "auth-service_reset-credential-lockout_grpc_timer")
    public Mono<UserCredentialStateAuthResponse> resetCredentialLockout(Mono<ResetCredentialLockoutAuthRequest> request){
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getTargetUserId(), "body.targetUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getActorUserId(), "body.actorUserId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getBody().getReason(), "body.reason")
                        )
                        .doOnError(logValidationError(
                                req.getHeader().getRequestId(), req.getHeader().getNodeId(), "resetCredentialLockout"
                        ))
                        .flatMap(tuple->{
                            String requestId = tuple.getT1();
                            String nodeId = tuple.getT2();

                            log.info("[{}][{}] resetCredentialLockout", requestId, nodeId);

                            UserStatusRequestDto requestDto = adminUserMapper.toRequestDto(req);
                            return adminUserManagementService.resetCredentialLockout(requestId,nodeId,requestDto)
                                    .map(adminUserMapper::toProtoResponse)
                                    .doOnSuccess(result ->
                                            log.info("[{}][{}] resetCredentialLockout: successfully reset credentials",
                                                requestId, nodeId)
                                    )
                                    .doOnError(logOnError(requestId, nodeId, "resetCredentialLockout"));
                        })
                );
    }
}
